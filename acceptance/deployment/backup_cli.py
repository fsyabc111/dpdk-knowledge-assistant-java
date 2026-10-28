#!/usr/bin/env python3
"""专属Docker PG备份：绑定可信当前state与实际backend目标，默认零daemon dry-run。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
from guard import GuardError,env_values,docker_configuration,deployment_manifest
from docker_pg import DockerPg,BASE,BASE_COMMENT
from override import validate


def trusted_file(repo,path):
    root=Path(repo).resolve()/'.migration-artifacts/j5-03';path=Path(path)
    if not path.is_absolute() or path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve()) or not root.resolve().is_relative_to(Path(repo).resolve()):
        raise GuardError('state/overlay必须为本轮普通owned文件')
    if path.stat().st_uid!=os.getuid() or path.stat().st_mode&0o777!=0o600:raise GuardError('state/overlay必须当前用户所有mode600')
    return path.resolve()


def select_target(repo,environ,*,explicit_base=False):
    state=Path(repo).resolve()/'.migration-artifacts/j5-03/scenario-state.json'
    explicit=environ.get('DPDK_JAVA_COMPOSE_OVERRIDE')
    if not state.exists() and not state.is_symlink():
        if not explicit_base or explicit:raise GuardError('无可信state时只允许显式--base且无overlay；apply仍核对实际backend')
        return BASE,None
    state=trusted_file(repo,state);value=json.loads(state.read_text());overlay=trusted_file(repo,value.get('overlay',''))
    if explicit and trusted_file(repo,explicit)!=overlay:raise GuardError('显式overlay与当前scenario-state不符')
    data=validate(repo,overlay);name=data['services']['backend-java'].get('environment',{}).get('DPDK_JAVA_DB_NAME',BASE)
    if explicit_base and name!=BASE:raise GuardError('--base与当前活动state数据库不符')
    return name,overlay


def check_backend_target(info,name,comment):
    env={}
    for entry in info.get('Config',{}).get('Env',[]):
        key,sep,value=entry.partition('=')
        if sep:
            if key in env and key in ('DPDK_JAVA_DB_NAME','DPDK_JAVA_DB_URL','DPDK_JAVA_DB_COMMENT'):raise GuardError('实际backend数据库配置重复')
            env[key]=value
    expected={'DPDK_JAVA_DB_NAME':name,'DPDK_JAVA_DB_URL':'jdbc:postgresql://postgres-java:5432/'+name,'DPDK_JAVA_DB_COMMENT':comment}
    if any(env.get(key)!=value for key,value in expected.items()):raise GuardError('实际backend数据库目标与备份选择不一致；拒绝备错库')


def verify_backend(repo,pg,name,overlay):
    manifest=deployment_manifest(repo);env=dict(os.environ)
    if overlay:env['DPDK_JAVA_COMPOSE_OVERRIDE']=str(overlay)
    else:env.pop('DPDK_JAVA_COMPOSE_OVERRIDE',None)
    output=subprocess.run([str(Path(repo)/'scripts-java/compose.sh'),'--apply','ps','-q','backend-java'],env=env,capture_output=True,text=True,check=True).stdout.strip()
    import re
    if not re.fullmatch('[a-f0-9]{12,64}',output):raise GuardError('无唯一运行backend，无法确认备份目标')
    info=json.loads(subprocess.run(['docker','--context','colima-java-u12','inspect',output],capture_output=True,text=True,check=True).stdout)[0]
    labels=info.get('Config',{}).get('Labels',{})
    if labels.get('com.docker.compose.project')!=manifest['project'] or labels.get('com.docker.compose.service')!='backend-java' or not info.get('State',{}).get('Running'):
        raise GuardError('实际backend不是本轮运行服务')
    if overlay:
        image=validate(repo,overlay)['services']['backend-java']['image']
        records=[v for k,v in manifest['images'].items() if k.endswith('backend') and v['reference']==image]
        if len(records)!=1 or info.get('Image')!=records[0]['image_id']:raise GuardError('实际backend image与活动overlay不一致')
    comment=BASE_COMMENT if name==BASE else pg.manifest['owned'][name]['comment']
    check_backend_target(info,name,comment)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',required=True,type=Path)
    parser.add_argument('--apply',action='store_true');parser.add_argument('--base',action='store_true',help='仅无state的显式base目标，apply核对运行backend')
    parser.add_argument('destination',type=Path);args=parser.parse_args();repo=args.repo.resolve();root=repo/'.migration-artifacts/j5-03'
    if not args.destination.is_absolute() or args.destination.exists() or not args.destination.resolve().is_relative_to(root):raise GuardError('只允许本轮尚不存在的绝对备份路径')
    env_values(repo,os.environ.get('DPDK_JAVA_ENV_FILE',''));docker_configuration();deployment_manifest(repo)
    name,overlay=select_target(repo,os.environ,explicit_base=args.base)
    if not args.apply:
        print(json.dumps({'dry_run':True,'database':name,'overlay':str(overlay) if overlay else None,'exclude_table_data':'public.rag_task_inputs','schema_retained':True,'database_connected':False,'actual_backend_verified':False}));return
    pg=DockerPg(repo);verify_backend(repo,pg,name,overlay)
    print(json.dumps(pg.backup(name,args.destination)))

if __name__=='__main__':main()
