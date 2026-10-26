#!/usr/bin/env python3
"""只允许owned image/数据库目标的JSON Compose overlay。"""
import argparse
import json
import os
from pathlib import Path
import re
from guard import GuardError, deployment_manifest


def validate(repo,path):
    repo=Path(repo).resolve();root=repo/'.migration-artifacts/j5-03';path=Path(path)
    if not path.is_absolute() or path.is_symlink() or not path.resolve(strict=True).is_relative_to(root) or path.stat().st_mode&0o777!=0o600:
        raise GuardError('override必须为本轮owned目录下的600文件')
    data=json.loads(path.read_text());manifest=deployment_manifest(repo)
    if set(data)!={'services'} or set(data['services'])!={'backend-java','frontend-java'}:raise GuardError('override只允许两应用服务')
    images=manifest['images'];allowed={r['reference']:r for r in images.values()}
    for service,kind in (('backend-java','backend'),('frontend-java','frontend')):
        spec=data['services'][service]
        expected={'image','build'}|({'environment'} if 'environment' in spec else set())
        if set(spec)!=expected or spec.get('build','absent') is not None:raise GuardError('override禁止改变部署安全/网络/挂载/build')
        image=spec['image']
        if image not in allowed or not any(role.endswith(kind) and record['reference']==image for role,record in images.items()):raise GuardError('override image未由本轮实际image ID/源码记录')
        if service=='frontend-java' and 'environment' in spec:raise GuardError('前端不允许environment override')
        if 'environment' in spec:
            environment=spec['environment']
            if set(environment)!={'DPDK_JAVA_DB_URL','DPDK_JAVA_DB_NAME','DPDK_JAVA_DB_COMMENT'}:raise GuardError('只允许三个DB目标字段，role/data_dir不得改变')
            owned_path=root/'owned-databases.json'
            owned=json.loads(owned_path.read_text())
            name=environment['DPDK_JAVA_DB_NAME'];record=owned['owned'].get(name)
            if owned['round_id']!=manifest['round_id'] or not record or record['state']=='reserved' or not re.fullmatch('p5_deployment_'+manifest['round_id']+'_(upgrade|restore)',name):raise GuardError('不是本轮owned upgrade/restore库')
            if environment['DPDK_JAVA_DB_URL']!='jdbc:postgresql://postgres-java:5432/'+name or environment['DPDK_JAVA_DB_COMMENT']!=record['comment']:raise GuardError('拒绝外部URL/旧库/comment不符')
    return data


def generate(repo,stage,backend_role,frontend_role,database=None):
    repo=Path(repo).resolve();manifest=deployment_manifest(repo);root=repo/'.migration-artifacts/j5-03'
    if stage not in ('cold','upgrade','restore','rollback'):raise GuardError('不允许的overlay阶段')
    try:
        backend=manifest['images'][backend_role];frontend=manifest['images'][frontend_role]
    except KeyError:raise GuardError('依赖image记录尚未提供') from None
    data={'services':{'backend-java':{'image':backend['reference'],'build':None},'frontend-java':{'image':frontend['reference'],'build':None}}}
    if database:
        owned=json.loads((root/'owned-databases.json').read_text())['owned'][database]
        data['services']['backend-java']['environment']={'DPDK_JAVA_DB_URL':'jdbc:postgresql://postgres-java:5432/'+database,'DPDK_JAVA_DB_NAME':database,'DPDK_JAVA_DB_COMMENT':owned['comment']}
    path=root/('compose-'+stage+'.json')
    if path.exists():raise GuardError('overlay已存在，拒绝覆盖恢复资产')
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as stream:json.dump(data,stream,indent=2)
    validate(repo,path);return path

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',required=True,type=Path);parser.add_argument('--stage',required=True)
    parser.add_argument('--backend-role',required=True);parser.add_argument('--frontend-role',required=True);parser.add_argument('--database')
    args=parser.parse_args();print(generate(args.repo,args.stage,args.backend_role,args.frontend_role,args.database))
