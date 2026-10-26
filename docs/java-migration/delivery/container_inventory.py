#!/usr/bin/env python3
"""离线读取本轮allowlist镜像；只读禁网短查询容器，无服务/PG/stack启动。"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'acceptance/deployment'))
from guard import docker_identity

IMAGES={
 'p4-backend':'sha256:c6f4799e40d5e5d201de66a04bf2ba62a7a4a9e1fd65ba57af92926fb8d28f11',
 'p4-frontend':'sha256:a862bcc150314cc85655e4675035cecef12cb464d56122912cb2800df56cfe52',
 'jdk-base':'eclipse-temurin@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d',
 'jre-base':'eclipse-temurin@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99',
 'node-base':'node@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914',
 'nginx-base':'nginxinc/nginx-unprivileged@sha256:65e3e85dbaed8ba248841d9d58a899b6197106c23cb0ff1a132b7bfe0547e4c0',
 'pgvector-base':'pgvector/pgvector:0.8.4-pg17@sha256:dd467f03ca5c5581222490e5217e48a262864ccb659be559f8491bbafdc97da0',
}
# 只查询包数据库/版本/许可证文件；不运行镜像原entrypoint，不读取应用env/config。
QUERY=r'''
printf '@@OS@@\n'; cat /etc/os-release
printf '@@DPKG@@\n'
if command -v dpkg-query >/dev/null; then dpkg-query -W -f='${binary:Package}\t${Version}\t${Architecture}\t${source:Package}\t${db:Status-Status}\n'; fi
printf '@@APK@@\n'; if test -r /lib/apk/db/installed; then cat /lib/apk/db/installed; fi
printf '@@VERSIONS@@\n'
if command -v java >/dev/null; then java -XX:-UsePerfData -version 2>&1; fi
if command -v node >/dev/null; then node --version; fi
if command -v npm >/dev/null; then npm --version; fi
if command -v nginx >/dev/null; then nginx -v 2>&1; fi
for p in /usr/lib/postgresql/*/bin/postgres; do if test -x "$p"; then "$p" --version; fi; done
if command -v pg_config >/dev/null; then pg_config --version; fi
printf '@@NODE_RUNTIME@@\n'
if command -v node >/dev/null; then node -p 'JSON.stringify(process.versions)'; fi
printf '@@NODE_PACKAGES@@\n'
if command -v node >/dev/null; then node -e '
const fs=require("fs"),crypto=require("crypto"),out=[];
function scan(p){if(!fs.existsSync(p))return;for(const ent of fs.readdirSync(p,{withFileTypes:true})){
 const q=p+"/"+ent.name;if(ent.isDirectory())scan(q);else if(ent.isFile()&&ent.name==="package.json"){
  const raw=fs.readFileSync(q),v=JSON.parse(raw);if(v.name&&v.version)out.push({path:q,name:v.name,version:v.version,license_declaration:v.license||v.licenses||null,license_declaration_field:v.license?"license":v.licenses?"licenses":null,package_json_sha256:crypto.createHash("sha256").update(raw).digest("hex")});
 }}}
scan("/usr/local/lib/node_modules");scan("/opt");console.log(JSON.stringify(out));'; fi
printf '@@JAVA_RELEASE@@\n' 
if test -n "$JAVA_HOME" && test -r "$JAVA_HOME/release"; then cat "$JAVA_HOME/release"; fi
printf '@@VECTOR_CONTROL@@\n'
for p in /usr/share/postgresql/*/extension/vector.control; do if test -r "$p"; then printf '%s\n' "$p"; cat "$p"; fi; done
printf '@@LICENSE_DECLARATIONS@@\n'
if test -d /usr/share/doc; then find -L /usr/share/doc -type f -name copyright -exec awk '/^License:/ {sub(/^License: */,"");print FILENAME "\t" $0}' {} + 2>/dev/null; fi
printf '@@LICENSE_FILES@@\n'
for p in /usr/share/doc /usr/share/common-licenses /usr/share/licenses /usr/local/lib/node_modules/npm /usr/local/share /opt; do
 if test -d "$p"; then find -L "$p" -type f \( -iname 'license*' -o -iname 'notice*' -o -iname 'copying*' -o -iname 'copyright*' \) -exec sha256sum {} + 2>/dev/null; fi
done
if test -n "$JAVA_HOME"; then
 if test -d "$JAVA_HOME/legal"; then find -L "$JAVA_HOME/legal" -type f -exec sha256sum {} + 2>/dev/null; fi
 for p in "$JAVA_HOME/release" "$JAVA_HOME/LICENSE" "$JAVA_HOME/NOTICE"; do if test -f "$p"; then sha256sum "$p"; fi; done
fi
for p in /usr/local/LICENSE /usr/local/NOTICE /LICENSE /NOTICE; do if test -f "$p"; then sha256sum "$p"; fi; done
printf '@@RUNTIME_LICENSE_DECLARATIONS@@\n'
for p in /opt/java/openjdk/legal/java.base/LICENSE /opt/java/openjdk/legal/java.base/ADDITIONAL_LICENSE_INFO /opt/java/openjdk/legal/java.base/ASSEMBLY_EXCEPTION /usr/local/lib/node_modules/npm/LICENSE /opt/yarn*/LICENSE /usr/share/licenses/nginx/COPYRIGHT /usr/share/doc/postgresql-17/copyright; do
 if test -r "$p"; then awk '/GNU GENERAL PUBLIC LICENSE|Version 2, June 1991|Classpath|MIT License|Permission is hereby granted|Redistribution and use in source and binary forms|PostgreSQL License/ {print FILENAME "\t" $0}' "$p"; fi
done
printf '@@COMPONENT_FILES@@\n' 
for p in /usr/lib/postgresql/*/lib/vector.so /usr/share/postgresql/*/extension/vector.control; do if test -f "$p"; then sha256sum "$p"; fi; done
'''


def sections(text):
    result={};current=None
    for line in text.splitlines():
        if line.startswith('@@') and line.endswith('@@'):
            current=line[2:-2];result[current]=[]
        elif current is not None:result[current].append(line)
    return result


def pairs(lines):
    result={}
    for line in lines:
        key,sep,value=line.partition('=')
        if sep:result[key]=value.strip('"')
    return result


def hashes(lines):
    result={}
    for line in lines:
        digest,sep,path=line.partition('  ')
        if not sep or len(digest)!=64:raise ValueError('unexpected sha256sum record')
        result[path]=digest
    return result


def inventory(name,ref):
    def docker(args):
        return subprocess.run(['docker','--context','colima-java-u12',*args],capture_output=True,text=True,check=True).stdout
    docker_identity()  # 每次短容器前重新检查endpoint/daemon，禁止ambient漂移。
    image=json.loads(docker(['image','inspect',ref,'--format','{{json .}}']))
    expected=ref.rsplit('@',1)[-1]
    if image['Id']!=expected:raise ValueError('allowlisted immutable image ID differs')
    args=['run','--rm','--pull','never','--network','none','--read-only','--user','65534:65534','--cap-drop','ALL',
          '--security-opt','no-new-privileges','--pids-limit','64','--memory','192m','--cpus','0.25',
          '--env','NODE_OPTIONS=','--env','JAVA_TOOL_OPTIONS=','--env','_JAVA_OPTIONS=','--env','JDK_JAVA_OPTIONS=',
          '--entrypoint','/bin/sh',ref,'-c',QUERY]
    raw=docker(args);parts=sections(raw)
    license_files=hashes(parts['LICENSE_FILES']);declarations={}
    for line in parts['LICENSE_DECLARATIONS']:
        file,sep,value=line.partition('\t')
        if sep and value:declarations.setdefault(file,[]).append(value)
    declarations={file:sorted(set(values)) for file,values in sorted(declarations.items())}
    packages=[];noninstalled=[]
    if parts['DPKG']:
        for line in parts['DPKG']:
            package,version,arch,source,status=line.split('\t')
            if status!='installed':
                noninstalled.append({'name':package,'version':version,'architecture':arch,'status':status});continue
            copyright='/usr/share/doc/'+package.split(':')[0]+'/copyright'
            packages.append({'name':package,'version':version,'architecture':arch,'source_package':source,
              'license_declarations':declarations.get(copyright,[]),'license_file':copyright if copyright in license_files else None,
              'license_file_sha256':license_files.get(copyright),'declaration_status':'actual_copyright_fields' if declarations.get(copyright) else 'unverified_no_structured_license_field'})
        manager='dpkg'
    elif parts['APK']:
        records='\n'.join(parts['APK']).split('\n\n')
        for record in records:
            fields={line[:1]:line[2:] for line in record.splitlines() if len(line)>1 and line[1]==':' and line[0] in 'PVLAo'}
            if 'P' in fields:packages.append({'name':fields['P'],'version':fields['V'],'architecture':fields.get('A'),'source_package':fields.get('o'),
                'license_declarations':[fields['L']] if fields.get('L') else [],'declaration_status':'actual_apk_installed_metadata' if fields.get('L') else 'unverified_no_license_field',
                'per_package_license_text':'not_verified_by_apk_metadata'})
        manager='apk'
    else:manager='unknown'
    for package in packages:
        if manager=='apk':
            prefix='/usr/share/licenses/'+package['name']+'/'
            linked={path:digest for path,digest in license_files.items() if path.startswith(prefix)}
            package['license_text_files_sha256']=linked
            package['per_package_license_text']='present_at_package_license_directory' if linked else 'not_found_at_package_license_directory; metadata_declaration_only'
    node_packages=json.loads(parts['NODE_PACKAGES'][0]) if parts['NODE_PACKAGES'] else []
    for package in node_packages:
        parent=str(Path(package['path']).parent)+'/'
        package['license_text_files_sha256']={path:digest for path,digest in license_files.items() if path.startswith(parent) and '/' not in path[len(parent):]}
        package['license_text_status']='present' if package['license_text_files_sha256'] else 'not_found_at_package_root_in_scanned_paths'
    return {'name':name,'reference':ref,'image_id':image['Id'],'repository_digests':image.get('RepoDigests',[]),
      'architecture':image['Architecture'],'os':image['Os'],'os_release':pairs(parts['OS']),
      'package_manager':manager,'packages':sorted(packages,key=lambda item:item['name']),
      'package_database_noninstalled_entries':noninstalled,
      'actual_version_output':parts['VERSIONS'],'java_release':pairs(parts['JAVA_RELEASE']),
      'node_bundled_runtime_versions':json.loads(parts['NODE_RUNTIME'][0]) if parts['NODE_RUNTIME'] else {},
      'actual_node_distribution_packages':node_packages,
      'runtime_license_identification_lines':[{'file':line.split('\t',1)[0],'declaration_line':line.split('\t',1)[1]} for line in parts['RUNTIME_LICENSE_DECLARATIONS'] if '\t' in line],
      'vector_control_actual':parts['VECTOR_CONTROL'],'component_file_sha256':hashes(parts['COMPONENT_FILES']),
      'license_files_sha256':dict(sorted(license_files.items())),'copyright_license_declarations':declarations,
      'query_sha256':hashlib.sha256(QUERY.encode()).hexdigest(),'package_count':len(packages),
      'license_file_count':len(license_files),'metadata_missing_license_count':sum(not p['license_declarations'] for p in packages),
      'scope':'P4 rollback or pinned base filesystem evidence; final P5 candidate pending',
      'complete_redistribution_notice_bundle':'not_generated_not_legal_signoff'}


def main(output):
    if output.exists():raise ValueError('new output required; do not overwrite evidence')
    identity=docker_identity()
    result={'version':'container-filesystem-inventory-v1','identity':identity,
      'capture_method':'allowlisted immutable images; explicit context; entrypoint /bin/sh; no network; read-only; nobody; no mounts/ports; resources bounded',
      'services_or_database_started':False,'application_source':'f020efa951b816cc3315f169c483af0998875800',
      'final_P5_candidate':'pending_not_inventoried','images':[inventory(name,ref) for name,ref in IMAGES.items()]}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'images':len(result['images']),'packages':sum(i['package_count'] for i in result['images']),
                     'license_files':sum(i['license_file_count'] for i in result['images']), 'output_sha256':hashlib.sha256(output.read_bytes()).hexdigest()}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path)
    main(parser.parse_args().output)
