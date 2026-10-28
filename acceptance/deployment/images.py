#!/usr/bin/env python3
"""记录实际构建ID与源码label/归档内容摘要；不创建运行容器。"""
from __future__ import annotations
import argparse
import gzip
import hashlib
import re
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
    if not re.fullmatch('sha256:[a-f0-9]{64}',info['Id']):raise GuardError('actual image ID非法')
    references=[value for value in info.get('RepoDigests',[]) if value.startswith(expected_prefix+'@sha256:') and re.fullmatch(r'[^@]+@sha256:[a-f0-9]{64}',value)]
    reference=references[0] if len(references)==1 else info['Id']
    root=repo/'.migration-artifacts/j5-03';build=root/'build'
    if not root.resolve().is_relative_to(repo) or build.is_symlink() or not build.resolve().is_relative_to(root.resolve()):raise GuardError('归档目录越界/symlink')
    build.mkdir(mode=0o700,exist_ok=True)
    archive=build/(role+'.image.tar')
    if archive.is_symlink():raise GuardError('归档不能是symlink')
    if not archive.exists():
        fd=os.open(archive,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600);os.close(fd)
        subprocess.run(['docker','--context','colima-java-u12','image','save','--output',str(archive),info['Id']],check=True)
        archive.chmod(0o600)
    if not archive.is_file() or archive.stat().st_uid!=os.getuid() or archive.stat().st_mode&0o777!=0o600:raise GuardError('归档必须为owned mode600普通文件')
    hashes={}
    with tarfile.open(archive,'r') as saved:
        descriptor=json.load(saved.extractfile('manifest.json'))
        if len(descriptor)!=1:raise GuardError('归档不是唯一image')
        config=saved.extractfile(descriptor[0]['Config']).read()
        if 'sha256:'+hashlib.sha256(config).hexdigest()!=info['Id']:raise GuardError('归档config与actual image ID不匹配')
        diff_ids=json.loads(config).get('rootfs',{}).get('diff_ids',[])
        if len(diff_ids)!=len(descriptor[0]['Layers']):raise GuardError('归档layer清单与config不匹配')
        for layer,expected in zip(descriptor[0]['Layers'],diff_ids):
            with saved.extractfile(layer) as raw:
                magic=raw.read(2);raw.seek(0)
                if magic==b'\x1f\x8b':
                    with gzip.GzipFile(fileobj=raw) as decoded:actual=digest(decoded)
                else:actual=digest(raw)
            if 'sha256:'+actual!=expected:raise GuardError('归档layer内容与config不匹配')
            with tarfile.open(fileobj=saved.extractfile(layer),mode='r|*') as content:
                for item in content:
                    path=item.name.removeprefix('./').removeprefix('/')
                    selected=path=='app/backend.jar' if role.endswith('backend') else path.startswith('usr/share/nginx/html/') and item.isfile()
                    if selected:hashes[path]=digest(content.extractfile(item))
    required='app/backend.jar' if role.endswith('backend') else 'usr/share/nginx/html/index.html'
    if required not in hashes:raise GuardError('归档中缺少实际jar/静态首页')
    with archive.open('rb') as stream:archive_hash=digest(stream)
    result={'image_id':info['Id'],'reference':reference,'tag':image,'source_revision':full_source,
        'archive_name':archive.name,'archive_sha256':archive_hash,'artifact_sha256':hashes,'size':info['Size']}
    previous=manifest['images'].get(role)
    if previous:
        # 保留已有效P4记录的reference/tag；不把旧记录盲改为新格式。
        stable=('image_id','source_revision','archive_name','archive_sha256','artifact_sha256','size')
        if any(previous.get(key)!=result[key] for key in stable):raise GuardError('已有不可变image记录不符，拒绝覆盖')
        return previous
    manifest['images'][role]=result
    path=repo/'.migration-artifacts/j5-03/deployment-manifest.json';temporary=path.with_suffix('.tmp')
    fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as stream:json.dump(manifest,stream,indent=2)
    os.replace(temporary,path);return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',required=True,type=Path)
    parser.add_argument('--role',required=True);parser.add_argument('--image',required=True);parser.add_argument('--source',required=True)
    args=parser.parse_args();print(json.dumps(record(args.repo,args.role,args.image,args.source),ensure_ascii=False))
