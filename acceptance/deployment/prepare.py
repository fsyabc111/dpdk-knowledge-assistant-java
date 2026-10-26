#!/usr/bin/env python3
"""生成J5-03全合成snapshot、新口令env和TLS；不创建容器/数据库。"""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
from snapshot import generate

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo',required=True,type=Path)
args = parser.parse_args(); repo = args.repo.resolve(strict=True)
root = repo/'.migration-artifacts'/'j5-03'
if not root.resolve().is_relative_to(repo):raise SystemExit('拒绝指向仓库外的准备目录')
if root.exists(): raise SystemExit('J5-03目录已存在，拒绝覆盖或复用旧环境')
root.mkdir(parents=True,mode=0o700)
generated = generate(repo/'backend/data/knowledge_sources.yml',root/'sources')
subprocess.run([str(repo/'scripts-java/generate-cert.sh'),str(root/'certs')],check=True,capture_output=True,text=True)
round_id=secrets.token_hex(6)
manifest={'version':1,'round_id':round_id,'project':'dpdk-java-isolated-'+round_id,'source_revision':subprocess.check_output(['git','rev-parse','f020efa'],cwd=repo,text=True).strip(),'images':{}}
fd=os.open(root/'deployment-manifest.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as stream:json.dump(manifest,stream,indent=2)
values = {'DPDK_JAVA_ENV_SCHEMA':'j5-03-isolated-v1','DPDK_JAVA_DB_PASSWORD':secrets.token_urlsafe(48),
    'DPDK_JAVA_HTTPS_PORT':'18443','DPDK_JAVA_AUTH_MODE':'account','DPDK_JAVA_MODEL_JUDGE_ENABLED':'false',
    'DPDK_JAVA_SOURCE_SNAPSHOT_DIR':str(root/'sources'),'DPDK_JAVA_CERT_DIR':str(root/'certs'),
    'DPDK_JAVA_PROXY_UID':str(os.getuid()),'DPDK_JAVA_PROXY_GID':str(os.getgid())}
fd = os.open(root/'runtime.env',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as output: output.write(''.join(key+'='+value+'\n' for key,value in values.items()))
print(json.dumps({'prepared':True,'runtime_env':str(root/'runtime.env'),'source_count':generated['source_count'],
    'include_count':generated['include_count'],'file_count':generated['file_count'],'containers_started':False,'databases_touched':False}))
