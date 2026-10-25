#!/usr/bin/env python3
"""J5-03 Docker/env fail-closed守卫；无容器写入，无secret输出。"""
from __future__ import annotations
import json
import hashlib
import os
from pathlib import Path
import stat
import subprocess
import sys

CONTEXT = 'colima-java-u12'
ENV_SCHEMA = 'j5-03-isolated-v1'
REQUIRED = {'DPDK_JAVA_DB_PASSWORD','DPDK_JAVA_HTTPS_PORT','DPDK_JAVA_SOURCE_SNAPSHOT_DIR',
    'DPDK_JAVA_CERT_DIR','DPDK_JAVA_PROXY_UID','DPDK_JAVA_PROXY_GID','DPDK_JAVA_ENV_SCHEMA'}
ALLOWED = REQUIRED | {'DPDK_JAVA_AUTH_MODE','DPDK_JAVA_MODEL_JUDGE_ENABLED','DPDK_JAVA_KNOWLEDGE_CONFIG',
    'DPDK_JAVA_DPDK_ROOT','DPDK_JAVA_ASSISTANT_ROOT','DPDK_JAVA_INDEX_WORKING_DIRECTORY'}

class GuardError(RuntimeError): pass

def env_values(repo, filename):
    repo = Path(repo).resolve(); path = Path(filename)
    boundary = repo/'.migration-artifacts'/'j5-03'
    if not path.is_absolute() or path.is_symlink(): raise GuardError('env必须为显式绝对路径且不能为symlink')
    path = path.resolve(strict=True)
    if not path.is_relative_to(boundary.resolve()) or path.name != 'runtime.env': raise GuardError('拒绝旧.env或非J5-03专属runtime.env')
    if stat.S_IMODE(path.stat().st_mode) != 0o600 or path.stat().st_uid != os.getuid(): raise GuardError('env必须由当前用户所有且mode600')
    ignored = subprocess.run(['git','check-ignore','-q',str(path)],cwd=repo,capture_output=True)
    if ignored.returncode: raise GuardError('env必须被Git忽略')
    values = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith('#'): continue
        key, sep, value = line.partition('=')
        if not sep or key not in ALLOWED or key in values or not value or any(c in value for c in ('\n','\r','\x00')): raise GuardError('env包含未知/重复/缺值配置')
        values[key] = value
    if REQUIRED-values.keys() or values['DPDK_JAVA_ENV_SCHEMA'] != ENV_SCHEMA: raise GuardError('env缺少隔离schema marker或必需项')
    if len(values['DPDK_JAVA_DB_PASSWORD']) < 24 or 'replace' in values['DPDK_JAVA_DB_PASSWORD'].lower(): raise GuardError('必须使用新生成的隔离口令')
    if values['DPDK_JAVA_PROXY_UID'] == '0' or not values['DPDK_JAVA_PROXY_UID'].isdigit() or not values['DPDK_JAVA_PROXY_GID'].isdigit(): raise GuardError('代理UID/GID必须显式非root')
    if values['DPDK_JAVA_HTTPS_PORT'] != '18443': raise GuardError('J5-03仅允许独立18443端口')
    for key in ('DPDK_JAVA_SOURCE_SNAPSHOT_DIR','DPDK_JAVA_CERT_DIR'):
        p = Path(values[key]); resolved = p.resolve(strict=True)
        if not p.is_absolute() or p.is_symlink() or not resolved.is_relative_to(boundary.resolve()): raise GuardError('源或证书必须为本轮专属绝对目录')
    source = json.loads((Path(values['DPDK_JAVA_SOURCE_SNAPSHOT_DIR'])/'source-snapshot.json').read_text())
    from snapshot import source_entries
    frozen = (repo/'backend/data/knowledge_sources.yml').read_bytes()
    entries = source_entries(frozen.decode())
    expected = {(entry['name'],entry['root'],include) for entry in entries for include in entry['include']}
    actual = {(item['source'],item['root'],item['include']) for item in source.get('coverage',[])}
    if source.get('synthetic') is not True or source.get('source_count') != len(entries) or actual != expected or source.get('original_manifest_sha256') != hashlib.sha256(frozen).hexdigest():
        raise GuardError('拒绝缺失/非全源或manifest版本不符的合成snapshot')
    snapshot_root = Path(values['DPDK_JAVA_SOURCE_SNAPSHOT_DIR']).resolve()
    for relative,digest in source.get('files',{}).items():
        item = snapshot_root/relative
        if item.is_symlink() or not item.resolve().is_relative_to(snapshot_root) or hashlib.sha256(item.read_bytes()).hexdigest() != digest or b'SYNTHETIC_J5_03_ONLY' not in item.read_bytes():
            raise GuardError('合成源文件边界/摘要/标记不符')
    key = Path(values['DPDK_JAVA_CERT_DIR'])/'server.key'
    if stat.S_IMODE(key.stat().st_mode) != 0o600: raise GuardError('TLS私钥必须mode600')
    return values


def docker_identity(environ=None, run=None):
    environ = os.environ if environ is None else environ
    run = subprocess.run if run is None else run
    if environ.get('DPDK_JAVA_DOCKER_CONTEXT') != CONTEXT: raise GuardError('只允许显式DPDK_JAVA_DOCKER_CONTEXT=colima-java-u12')
    if any(environ.get(key) for key in ('DOCKER_HOST','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH')) or environ.get('DOCKER_CONTEXT',CONTEXT) != CONTEXT:
        raise GuardError('拒绝ambient Docker host/TLS/旧context环境')
    expected_endpoint = 'unix://'+str(Path.home()/'.colima/java-u12/docker.sock')
    if environ.get('DPDK_JAVA_DOCKER_ENDPOINT') != expected_endpoint or not environ.get('DPDK_JAVA_DOCKER_DAEMON_ID'):
        raise GuardError('必须显式提供新profile endpoint及daemon ID')
    def read(args):
        result = run(['docker','--context',CONTEXT,*args],capture_output=True,text=True,check=True)
        return json.loads(result.stdout)
    endpoint = read(['context','inspect',CONTEXT,'--format','{{json .Endpoints.docker}}'])
    daemon = read(['info','--format','{{json .}}'])
    if endpoint.get('Host') != expected_endpoint or endpoint.get('SkipTLSVerify'):
        raise GuardError('Docker endpoint不是新java-u12 profile')
    if daemon.get('ID') != environ['DPDK_JAVA_DOCKER_DAEMON_ID'] or daemon.get('Name') != CONTEXT or daemon.get('OSType') != 'linux':
        raise GuardError('daemon身份不符，零容器写入退出')
    return {'context':CONTEXT,'endpoint':expected_endpoint,'daemon_id':daemon['ID'],'daemon_name':daemon['Name']}


if __name__ == '__main__':
    try:
        values = env_values(sys.argv[1],os.environ.get('DPDK_JAVA_ENV_FILE',''))
        identity = docker_identity()
        # stdout只给shell确认成功，绝不输出env/口令。
        print(json.dumps(identity))
    except Exception as error:
        print('J5-03隔离守卫拒绝：'+(str(error) if isinstance(error,GuardError) else error.__class__.__name__),file=sys.stderr)
        sys.exit(1)
