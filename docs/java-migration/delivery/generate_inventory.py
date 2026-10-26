#!/usr/bin/env python3
"""从实际Maven resolved列表/本地artifact POM/JAR与npm lock生成依赖声明清单。"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

NS={'m':'http://maven.apache.org/POM/4.0.0'}
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def xml_text(element,path):
    node=element.find(path,NS);return node.text.strip() if node is not None and node.text else ''
def pom_licenses(repository,group,artifact,version,seen=()):
    gav=(group,artifact,version);relative=Path(*group.split('.'))/artifact/version/(artifact+'-'+version+'.pom');path=repository/relative
    if gav in seen or len(seen)>=12 or not path.is_file():return [],None,'pom_missing_or_parent_unresolved'
    root=ET.parse(path).getroot()
    licenses=[{'name':xml_text(x,'m:name'),'url':xml_text(x,'m:url'),'distribution':xml_text(x,'m:distribution')} for x in root.findall('m:licenses/m:license',NS)]
    if licenses:return licenses,{'gav':':'.join(gav),'path':relative.as_posix(),'sha256':sha(path)},'declared_in_pom' if not seen else 'inherited_parent_pom'
    parent=root.find('m:parent',NS)
    if parent is None:return [],{'gav':':'.join(gav),'path':relative.as_posix(),'sha256':sha(path)},'license_not_declared'
    props={x.tag.split('}')[-1]:(x.text or '').strip() for x in root.findall('m:properties/*',NS)}
    props.update({'project.groupId':group,'project.artifactId':artifact,'project.version':version})
    values=[xml_text(parent,'m:'+key) for key in ('groupId','artifactId','version')]
    for index,value in enumerate(values):
        values[index]=re.sub(r'\$\{([^}]+)\}',lambda m:props.get(m[1],m[0]),value)
    if any(not value or '${' in value for value in values):return [],None,'parent_coordinate_unresolved'
    return pom_licenses(repository,*values,seen=(*seen,gav))

def maven_inventory(resolved,repository):
    result=[]
    for raw in resolved.read_text().splitlines():
        coordinate=raw.strip().split(' -- module ',1)[0]
        pieces=coordinate.split(':')
        if len(pieces) not in (5,6) or pieces[-1] not in ('compile','runtime','provided','test','system'):continue
        group,artifact,packaging=pieces[:3];version,scope=pieces[-2:];classifier=pieces[3] if len(pieces)==6 else None
        licenses,source,status=pom_licenses(repository,group,artifact,version)
        filename=artifact+'-'+version+('-'+classifier if classifier else '')+'.'+packaging
        relative=Path(*group.split('.'))/artifact/version/filename;jar=repository/relative
        notices=[]
        if jar.is_file() and packaging=='jar':
            with zipfile.ZipFile(jar) as zipped:
                for name in sorted(zipped.namelist()):
                    if not name.endswith('/') and re.match(r'^(LICENSE|NOTICE|COPYING)([._-]|$)',Path(name).name,re.I):
                        content=zipped.read(name);notices.append({'path':name,'sha256':hashlib.sha256(content).hexdigest(),'bytes':len(content)})
        result.append({'coordinate':coordinate,'group':group,'artifact':artifact,'version':version,'classifier':classifier,'scope':scope,
            'runtime_distribution_candidate':scope in ('compile','runtime'),'licenses':licenses,'license_status':status,'license_source':source,
            'artifact':{'path':relative.as_posix(),'sha256':sha(jar) if jar.is_file() else None,'notice_entries':notices}})
    if not result:raise ValueError('Maven resolved列表为空，不得发布空依赖清单')
    return result

def frontend_inventory(lock):
    data=json.loads(lock.read_text());result=[]
    for path,value in sorted(data['packages'].items()):
        if not path:continue
        package=value.get('name') or path.rsplit('node_modules/',1)[-1]
        declaration=value.get('license')
        result.append({'package':package,'version':value.get('version'),'lock_path':path,'dev':value.get('dev',False),
            'optional':value.get('optional',False),'integrity':value.get('integrity'),'license':declaration,
            'license_status':'declared_in_lockfile' if declaration else 'not_declared_in_lockfile'})
    if not result:raise ValueError('npm lock为空')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',type=Path,required=True)
    parser.add_argument('--maven-repository',type=Path,required=True);args=parser.parse_args();repo=args.repo.resolve()
    evidence=repo/'docs/java-migration/evidence/J5-04';resolved=evidence/'maven-resolved.txt'
    java=maven_inventory(resolved,args.maven_repository);frontend=frontend_inventory(repo/'frontend/package-lock.json')
    inventory={'version':'j5-04-preparation-v1','source_baseline':'f020efa951b816cc3315f169c483af0998875800',
        'inputs':{'pom_sha256':sha(repo/'backend-java/pom.xml'),'frontend_lock_sha256':sha(repo/'frontend/package-lock.json'),'maven_resolved_sha256':sha(resolved),'root_license_sha256':sha(repo/'LICENSE')},
        'scope':'Resolved dependency/license declarations and artifact hashes; not a complete container OS SBOM or redistribution approval',
        'maven':java,'frontend':frontend}
    (evidence/'dependencies.json').write_text(json.dumps(inventory,ensure_ascii=False,indent=2)+'\n')
    summary={'maven_total':len(java),'maven_scopes':dict(Counter(x['scope'] for x in java)),
        'maven_missing_license_declarations':[x['coordinate'] for x in java if not x['licenses']],
        'maven_missing_artifacts':[x['coordinate'] for x in java if x['artifact']['sha256'] is None],
        'frontend_total':len(frontend),'frontend_declared_licenses':dict(Counter(str(x['license']) for x in frontend)),
        'frontend_missing_license_declarations':[x['lock_path'] for x in frontend if not x['license']]}
    (evidence/'dependency-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    lines=['# 依赖与许可证声明清单','', '自动依据：Maven实际resolved列表、本地artifact POM（可追溯父POM继承）及JAR NOTICE元数据、npm lock。完整坐标/hash/出处在 evidence/J5-04/dependencies.json。以下是声明核对，未代签再分发或法律兼容结论。','',f"Maven {len(java)}项；npm lock {len(frontend)}项（含开发/可选/平台变体，不等于浏览器最终bundle组件数）。",'', '## Maven resolved','', '| 坐标 | scope | 声明许可证 | 出处/状态 |','|---|---|---|---|']
    for x in java:
        names=' / '.join(v['name'] for v in x['licenses']) or '待核实：未取得声明'
        lines.append('| '+x['coordinate']+' | '+x['scope']+' | '+names.replace('|','\\|')+' | '+x['license_status']+' |')
    lines+=['','## npm lock声明','', '| 包/版本 | 类型 | 声明许可证 |','|---|---|---|']
    for x in frontend:
        lines.append('| '+x['package']+'@'+str(x['version'])+' | '+('dev' if x['dev'] else '非dev')+('/optional' if x['optional'] else '')+' | '+str(x['license'] or '待核实：lock无声明').replace('|','\\|')+' |')
    lines+=['','本项目根LICENSE文本为Apache License 2.0；根许可证保留，不推断它替代第三方各自条款。双许可证保留原声明，未自动替使用者选择授权路径。JDK/JRE、nginx、Node、PG/pgvector镜像的OS层完整SBOM/NOTICE未在本次本地应用清单核实，单列待补。']
    (repo/'docs/java-migration/delivery/DEPENDENCIES.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps(summary,ensure_ascii=False))
