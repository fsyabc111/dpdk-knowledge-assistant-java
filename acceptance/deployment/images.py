#!/usr/bin/env python3
"""记录实际构建ID与源码label/归档内容摘要；不创建运行容器。"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
from guard import GuardError, deployment_manifest, docker_identity


def digest(stream):
    value=hashlib.sha256()
    while chunk:=stream.read(1024*1024):value.update(chunk)
    return value.hexdigest()


def record(repo,role,image,source):
    repo=Path(repo).resolve();docker_identity();manifest=deployment_manifest(repo)
    if role not in ('p4-backend','p4-frontend','candidate-backend','candidate-frontend'):raise GuardError('image角色非法')
    full_source=subprocess.check_output(['git','rev-parse',source],cwd=repo,text=True).strip()
    info=json.loads(subprocess.check_output(['docker','--context','colima-java-u12','image','inspect',image],text=True))[0]
    if info['Config'].get('Labels',{}).get('org.opencontainers.image.revision')!=full_source:raise GuardError('image实际源码label与指定提交不符')
    if info['Config'].get('Labels',{}).get('io.dpdk.validation-domain')!='isolated-synthetic':raise GuardError('image不是本轮隔离构建')
    expected_prefix='dpdk-java-isolated-'+('backend' if role.endswith('backend') else 'frontend')
    references=[value for value in info.get('RepoDigests',[]) if value.startswith(expected_prefix+'@sha256:')]
    if len(references)!=1:raise GuardError('缺少唯一实际image digest引用')
    archive=repo/'.migration-artifacts/j5-03/build'/(role+'.image.tar')
    if not archive.exists():
        subprocess.run(['docker','--context','colima-java-u12','image','save','--output',str(archive),image],check=True)
        archive.chmod(0o600)
    hashes={}
    with tarfile.open(archive,'r') as saved:
        descriptor=json.load(saved.extractfile('manifest.json'))
        if len(descriptor)!=1:raise GuardError('归档不是唯一image')
        for layer in descriptor[0]['Layers']:
            with tarfile.open(fileobj=saved.extractfile(layer),mode='r|*') as content:
                for item in content:
                    path=item.name.removeprefix('./').removeprefix('/')
                    selected=path=='app/backend.jar' if role.endswith('backend') else path.startswith('usr/share/nginx/html/') and item.isfile()
                    if selected:hashes[path]=digest(content.extractfile(item))
    required='app/backend.jar' if role.endswith('backend') else 'usr/share/nginx/html/index.html'
    if required not in hashes:raise GuardError('归档中缺少实际jar/静态首页')
    result={'image_id':info['Id'],'reference':references[0],'tag':image,'source_revision':full_source,
        'archive_name':archive.name,'archive_sha256':digest(archive.open('rb')),'artifact_sha256':hashes,'size':info['Size']}
    previous=manifest['images'].get(role)
    if previous and previous!=result:raise GuardError('已有不可变image记录不符，拒绝覆盖')
    manifest['images'][role]=result
    path=repo/'.migration-artifacts/j5-03/deployment-manifest.json';temporary=path.with_suffix('.tmp')
    fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as stream:json.dump(manifest,stream,indent=2)
    os.replace(temporary,path);return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',required=True,type=Path)
    parser.add_argument('--role',required=True);parser.add_argument('--image',required=True);parser.add_argument('--source',required=True)
    args=parser.parse_args();print(json.dumps(record(args.repo,args.role,args.image,args.source),ensure_ascii=False))
