#!/usr/bin/env python3
"""现有artifact许可/NOTICE原字节去重归档；不运行npm/Maven/包代码，不下载或启动服务。"""
from __future__ import annotations
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import zipfile

PATTERN=re.compile(r'^(LICENSE|NOTICE|COPYING|COPYRIGHT)([._-]|$)',re.I)

def sha(data):return hashlib.sha256(data).hexdigest()
def blob(root,data):
    digest=sha(data);path=root/'sha256'/(digest+'.txt');path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        if path.read_bytes()!=data:raise ValueError('content-address collision')
    else:path.write_bytes(data)
    return {'sha256':digest,'bytes':len(data),'text_path':'sha256/'+path.name}

def collect(repo,maven_repository,npm_cache):
    inputs=repo/'docs/java-migration/evidence/J5-04/dependencies.json';inventory=json.loads(inputs.read_text())
    dest=repo/'docs/java-migration/delivery/third-party-texts';java=[];npm=[]
    for item in inventory['maven']:
        entry={'coordinate':item['coordinate'],'scope':item['scope'],'runtime_distribution_candidate':item['runtime_distribution_candidate'],'declared_licenses':item['licenses'],'files':[]}
        artifact=maven_repository/item['artifact']['path']
        if not artifact.is_file():entry['status']='missing_local_artifact'
        else:
            require=sha(artifact.read_bytes())==item['artifact']['sha256']
            if not require:raise ValueError('actual Maven artifact differs: '+item['coordinate'])
            entry['artifact_sha256_verified']=item['artifact']['sha256']
            with zipfile.ZipFile(artifact) as zipped:
                for notice in item['artifact']['notice_entries']:
                    data=zipped.read(notice['path'])
                    if sha(data)!=notice['sha256']:raise ValueError('JAR notice hash differs')
                    entry['files'].append({'original_entry':notice['path'],**blob(dest,data)})
            entry['status']='actual_JAR_texts_archived' if entry['files'] else 'no_license_notice_entry_in_actual_JAR; POM_declarations_only'
        java.append(entry)
    lock=json.loads((repo/'frontend/package-lock.json').read_text())
    for path,item in lock['packages'].items():
        if not path:continue
        entry={'package':item.get('name') or path.rsplit('node_modules/',1)[-1],'version':item.get('version'),'lock_path':path,'dev':bool(item.get('dev')),'optional':bool(item.get('optional')),'declared_license':item.get('license'),'locked_integrity':item.get('integrity'),'resolved':item.get('resolved'),'files':[]}
        integrity=(item.get('integrity') or '').split()
        if not integrity:entry['status']='missing_locked_integrity';npm.append(entry);continue
        algorithm,encoded=integrity[0].split('-',1);digest=base64.b64decode(encoded).hex()
        archive=npm_cache/'content-v2'/algorithm/digest[:2]/digest[2:4]/digest[4:]
        if not archive.is_file():entry['status']='exact_integrity_archive_not_in_local_cache';npm.append(entry);continue
        if archive.stat().st_size>4*1024*1024:entry['status']='deferred_large_archive_over_4MiB_in_light_window';npm.append(entry);continue
        raw=archive.read_bytes()
        if hashlib.new(algorithm,raw).hexdigest()!=digest:raise ValueError('npm integrity mismatch')
        entry['archive_integrity_verified']=True
        with tarfile.open(fileobj=io.BytesIO(raw),mode='r:*') as tar:
            candidates=[m for m in tar.getmembers() if m.isfile() and Path(m.name).name=='package.json' and len(Path(m.name).parts)==2]
            if len(candidates)!=1:entry['status']='archive_package_json_missing_or_ambiguous';npm.append(entry);continue
            metadata=candidates[0].name;package=json.load(tar.extractfile(metadata))
            if package.get('version')!=entry['version']:raise ValueError('npm version differs')
            entry['package_json_sha256']=sha(tar.extractfile(metadata).read())
            entry['actual_package_license']=package.get('license',package.get('licenses'))
            for member in tar:
                if member.isfile() and PATTERN.match(Path(member.name).name):
                    if member.size>2*1024*1024:raise ValueError('license file unexpectedly large')
                    entry['files'].append({'original_entry':member.name,**blob(dest,tar.extractfile(member).read())})
        entry['status']='exact_integrity_npm_archive_texts_archived' if entry['files'] else 'exact_integrity_archive_has_no_license_notice_file'
        npm.append(entry)
    output=repo/'docs/java-migration/evidence/J5-04/license-text-bundle-index.json'
    prior_public=json.loads(output.read_text()).get('public_versioned_sources',[]) if output.exists() else []
    result={'version':'j5-04-license-text-bundle-v1','domain':'existing_P4_application_dependency_inputs; final_P5_candidate_and_legal_signoff_pending','declarations_inventory_sha256':sha(inputs.read_bytes()),'frontend_lock_sha256':sha((repo/'frontend/package-lock.json').read_bytes()),'original_bytes_preserved':True,'storage':'docs/java-migration/delivery/third-party-texts/sha256/{sha256}.txt','maven':java,'npm':npm,'public_versioned_sources':prior_public,'limitations':['not_a_complete_redistribution_NOTICE_bundle_or_legal_compatibility_signoff','dependencies_without_embedded_text_require_source_or_publisher_followup','container_OS_and_JRE_original_texts_not_extracted_in_this_no_container_window','Node_and_pgvector_official_tag_sources_verified_but_original_download_may_remain_pending','final_candidate_artifact_set_and_transitive_bundled_scope_pending']}
    output=repo/'docs/java-migration/evidence/J5-04/license-text-bundle-index.json';output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    files=list((dest/'sha256').glob('*.txt'))
    print(json.dumps({'maven_components':len(java),'maven_archived_entries':sum(len(i['files']) for i in java),'npm_components':len(npm),'npm_archived_components':sum(bool(i['files']) for i in npm),'npm_archived_entries':sum(len(i['files']) for i in npm),'unique_text_objects':len(files),'stored_bytes':sum(p.stat().st_size for p in files)},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',required=True,type=Path);parser.add_argument('--maven-repository',required=True,type=Path);parser.add_argument('--npm-cache',required=True,type=Path);args=parser.parse_args()
    collect(args.repo.resolve(),args.maven_repository.resolve(),args.npm_cache.resolve())
