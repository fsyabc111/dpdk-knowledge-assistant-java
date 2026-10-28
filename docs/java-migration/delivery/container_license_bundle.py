#!/usr/bin/env python3
"""只从既有7镜像库存路径读取许可原字节，禁网readonly短tar容器，零包代码执行。"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path,PurePosixPath
import subprocess
import sys
import tarfile
from container_inventory import IMAGES
from license_bundle import blob
REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'acceptance/deployment'))
from guard import docker_identity,GuardError


def collect(repo):
    evidence=repo/'docs/java-migration/evidence/J5-04';source=evidence/'container-inventory-p4.json'
    inventory=json.loads(source.read_text());summary=json.loads((evidence/'container-inventory-summary.json').read_text())
    if hashlib.sha256(source.read_bytes()).hexdigest()!=summary['inventory_sha256']:raise GuardError('库存SHA与既有summary不一致')
    if {i['name']:i['reference'] for i in inventory['images']}!=IMAGES:raise GuardError('拒绝库存外镜像')
    identity=docker_identity();result={'version':'container-original-license-bytes-v1','identity':identity,'inventory_sha256':summary['inventory_sha256'],'images':[],
      'capture':'existing immutable allowlist only; /bin/tar entrypoint; no network; readonly; nobody; capdrop; no mounts/ports; no package execution',
      'application_or_PG_runtime_started':False,'final_P5_candidate':'pending','complete_notice_or_legal_signoff':False}
    output=repo/'docs/java-migration/delivery/third-party-texts'
    for image in inventory['images']:
        docker_identity()
        inspected=json.loads(subprocess.check_output(['docker','--context','colima-java-u12','image','inspect',image['reference'],'--format','{{json .Id}}'],text=True))
        if inspected!=image['image_id']:raise GuardError('实际image ID与库存不一致')
        files=image['license_files_sha256']
        for path in files:
            parts=PurePosixPath(path)
            if not parts.is_absolute() or '..' in parts.parts or any(ord(c)<32 for c in path) or not path.startswith(('/usr/share/','/usr/local/','/opt/','/LICENSE','/NOTICE')):raise GuardError('库存文本路径越界')
        command=['docker','--context','colima-java-u12','run','--rm','-i','--pull','never','--network','none','--read-only','--user','65534:65534',
          '--cap-drop','ALL','--security-opt','no-new-privileges','--pids-limit','32','--memory','128m','--cpus','0.25','--env','TAR_OPTIONS=',
          '--entrypoint','/bin/tar',image['reference'],'-c','-h','-f','-','-T','-']
        captured=subprocess.run(command,input=('\n'.join(sorted(files))+'\n').encode(),capture_output=True,check=True).stdout
        with tarfile.open(fileobj=io.BytesIO(captured),mode='r:') as tar:
            members=tar.getmembers();names=['/'+m.name.lstrip('/') for m in members]
            if len(names)!=len(set(names)) or set(names)!=set(files):raise GuardError('原文字节tar路径与库存不一致')
            entries=[]
            for member,path in zip(members,names):
                if not (member.isfile() or member.islnk()):raise GuardError('原文tar包含非文件条目')
                stream=tar.extractfile(member)
                if stream is None:raise GuardError('原文不可读')
                data=stream.read()
                if hashlib.sha256(data).hexdigest()!=files[path]:raise GuardError('实物文本hash与库存不一致')
                entries.append({'original_image_path':path,**blob(output,data)})
        result['images'].append({'name':image['name'],'image_id':image['image_id'],'files':entries,'all_inventory_hashes_equal':True})
    text_objects={e['sha256']:e['bytes'] for i in result['images'] for e in i['files']}
    result['captured_file_records']=sum(len(i['files']) for i in result['images']);result['unique_container_text_objects']=len(text_objects);result['unique_container_text_bytes']=sum(text_objects.values())
    result['limitations']=['only existing inventoried paths; absence in image remains absence','license declarations and artifact provenance are not legal compatibility approval','final candidate image inventory and complete redistribution obligation review pending']
    path=evidence/'container-license-text-index.json';path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    report={k:v for k,v in result.items() if k!='images'};report['image_count']=len(result['images']);report['index_sha256']=hashlib.sha256(path.read_bytes()).hexdigest();report['collector_sha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    (evidence/'container-license-text-summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'captured_file_records':result['captured_file_records'],'unique_container_text_objects':len(text_objects),'unique_container_text_bytes':sum(text_objects.values()),'images':len(result['images'])}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',required=True,type=Path);collect(parser.parse_args().repo.resolve())
