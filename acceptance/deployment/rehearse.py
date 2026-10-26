#!/usr/bin/env python3
"""J5-03分步演练执行器。默认plan零Docker/PG操作；实际阶段仅在主Agent队列窗口执行。"""
from __future__ import annotations
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import subprocess
import time
from guard import GuardError,env_values,deployment_manifest,docker_identity
from docker_pg import DockerPg,BASE
from override import generate,validate
from probes import Probes

class Rehearsal:
    def __init__(self,repo):
        self.repo=Path(repo).resolve();self.root=self.repo/'.migration-artifacts/j5-03'
        self.values=env_values(self.repo,os.environ.get('DPDK_JAVA_ENV_FILE',''));self.manifest=deployment_manifest(self.repo)
        docker_identity();self.pg=DockerPg(self.repo)
        self.probes=Probes(Path(self.values['DPDK_JAVA_CERT_DIR'])/'server.crt')
        self.state_path=self.root/'scenario-state.json';self.report_path=self.root/'scenario-results.json'
        self.state=json.loads(self.state_path.read_text()) if self.state_path.exists() else {}
        self.report=json.loads(self.report_path.read_text()) if self.report_path.exists() else {'domain':'isolated_synthetic_tls_nginx_java_pg','events':[]}
    def persist(self):
        for path,value in ((self.state_path,self.state),(self.report_path,self.report)):
            fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
            with os.fdopen(fd,'w') as stream:json.dump(value,stream,indent=2)
    def compose(self,*args,overlay=None):
        env=dict(os.environ)
        if overlay:env['DPDK_JAVA_COMPOSE_OVERRIDE']=str(overlay)
        else:env.pop('DPDK_JAVA_COMPOSE_OVERRIDE',None)
        return subprocess.run([str(self.repo/'scripts-java/compose.sh'),'--apply',*args],env=env,capture_output=True,text=True,check=True).stdout
    def operation(self,*args):
        env=dict(os.environ);env['DPDK_JAVA_COMPOSE_OVERRIDE']=self.state['overlay']
        output=subprocess.run([str(self.repo/'scripts-java/operations-compose.sh'),*args],env=env,capture_output=True,text=True,check=True).stdout
        return json.loads(output)
    def overlay(self,stage,database=None,release='candidate'):
        path=generate(self.repo,stage,release+'-backend',release+'-frontend',database)
        self.state['overlay']=str(path);self.persist();return path
    def activate(self,overlay):
        started=time.monotonic()
        self.compose('stop','backend-java',overlay=overlay)
        self.compose('up','-d','--no-build','--pull','never','backend-java','frontend-java',overlay=overlay)
        self.probes.health(ready=False)
        return time.monotonic()-started
    def ingest(self):
        preview=self.operation('ingest','--manifest','/sources/knowledge_sources.yml')['result']
        if preview['desired']<1:raise GuardError('拒绝空索引')
        self.operation('ingest','--manifest','/sources/knowledge_sources.yml','--apply')
        self.probes.health();return preview['desired']
    def new_business(self,phase,username):
        self.operation('create-users','--id',username,'--roles','leader','--apply')
        self.state[phase]=self.probes.new_business(username);self.persist()
        return {'committed_chat':True,'citations':len(self.state[phase]['citation_ids']),'http_auth_readwrite':True}
    def cold(self):
        if 'cold' in self.state:raise GuardError('冷启已执行，不复用有状态卷')
        volume=self.manifest['project']+'_java-pg-data'
        result=subprocess.run(['docker','--context','colima-java-u12','volume','inspect',volume],capture_output=True)
        if result.returncode==0:raise GuardError('冷启必须全新卷；保留旧恢复资产另开round')
        overlay=self.overlay('cold')
        started=time.monotonic();self.compose('up','-d','--no-build','--pull','never',overlay=overlay)
        health_seconds=self.probes.health(ready=False)
        self.probes.request('/api/ready',expected=503)
        nodes=self.ingest();business=self.new_business('cold','U20260000061')
        return {'phase':'cold','new_volume':volume,'health_poll_seconds':health_seconds,'ready_before_ingest':503,'desired_nodes':nodes,
            'elapsed_seconds':time.monotonic()-started,'ingress':self.probes.ingress(),'business':business,'sse':self.probes.sse(self.state['cold'])}
    def upgrade(self):
        if 'cold' not in self.state or 'upgrade' in self.state:raise GuardError('先cold且upgrade不能重复')
        self.compose('stop','backend-java',overlay=self.state['overlay'])
        name=self.pg.create('upgrade');self.pg.synthetic0013(name);self.state['upgrade_database']=name;self.persist()
        overlay=self.overlay('upgrade',name);startup=self.activate(overlay)
        self.probes.request('/api/ready',expected=503)
        nodes=self.ingest();business=self.new_business('upgrade','U20260000062')
        rows=self.pg.read_assertions(name)
        if rows['grading_submissions']<1:raise GuardError('0013合成历史成绩丢失')
        self.state['upgrade_counts']=rows;self.persist()
        return {'phase':'upgrade','synthetic_source_schema':'0013_task_recovery','startup_seconds':startup,'desired_nodes':nodes,'business':business,'database_counts':rows,'legacy_grade_retained':True}
    def backup_restore(self):
        if 'upgrade' not in self.state or 'restore_database' in self.state:raise GuardError('先upgrade且恢复不能重复')
        self.compose('stop','backend-java',overlay=self.state['overlay'])
        source=self.state['upgrade_database'];self.pg.seed_temporary_sentinel(source)
        before=self.pg.read_assertions(source);self.pg.assert_no_longterm_sentinel(source)
        if before['rag_task_inputs']!=1:raise GuardError('临时源码兜底备份样例未成立')
        started=time.monotonic();backup=self.root/'business-backup.dump';summary=self.pg.backup(source,backup)
        name=self.pg.create('restore');self.pg.restore(name,backup);after=self.pg.read_assertions(name)
        if not after['task_input_table_present'] or after['rag_task_inputs']!=0:raise GuardError('恢复临时表结构缺失或源码数据泄漏')
        for key,value in before.items():
            if key!='rag_task_inputs' and after[key]!=value:raise GuardError('恢复业务计数/索引身份变化：'+key)
        self.pg.assert_no_longterm_sentinel(name)
        self.state['restore_database']=name;self.state['backup']=summary;self.persist()
        overlay=self.overlay('restore',name);self.activate(overlay);self.probes.health()
        read=self.probes.read_existing(self.state['upgrade'])
        self.new_business('restore','U20260000063')
        self.state['restored_counts']=self.pg.read_assertions(name);self.persist()
        return {'phase':'backup_restore','backup':summary,'before':before,'restored_before_new_write':after,'source_sentinel_longterm_matches':0,'existing_business':read,
            'elapsed_seconds':time.monotonic()-started,'rpo_domain':'controlled write-free backup snapshot; all retained business rows equal; temporary execution inputs intentionally excluded'}
    def rollback(self):
        if 'restore_database' not in self.state or 'rollback' in self.state:raise GuardError('先恢复，应用回退不能重复')
        old=self.manifest['images'].get('p4-backend');new=self.manifest['images'].get('candidate-backend')
        if not old or not new or old['image_id']==new['image_id'] or old['source_revision']==new['source_revision'] or old['artifact_sha256']['app/backend.jar']==new['artifact_sha256']['app/backend.jar']:
            raise GuardError('回退必须不同真实构建/源码/jar，不能只改tag')
        name=self.state['restore_database'];before=self.pg.read_assertions(name)
        overlay=self.overlay('rollback',name,release='p4');started=time.monotonic();self.activate(overlay);self.probes.health()
        original=self.probes.read_existing(self.state['upgrade']);latest=self.probes.read_existing(self.state['restore'])
        after=self.pg.read_assertions(name)
        for key,value in before.items():
            if key not in ('upstream_usage_records',) and after[key]!=value:raise GuardError('应用回退改变业务数据：'+key)
        self.new_business('rollback','U20260000064')
        return {'phase':'rollback','old_image_id':old['image_id'],'new_image_id':new['image_id'],'elapsed_seconds':time.monotonic()-started,
            'upgrade_data_readable':original,'post_restore_new_data_readable':latest,'schema_downgrade':False,'backup_overwrite':False,'old_application_new_write':True}
    def execute(self,phase):
        started=time.monotonic();started_at=datetime.now(timezone.utc).isoformat()
        try:
            result=getattr(self,phase)();result['execution_seconds']=time.monotonic()-started
        except Exception as error:
            self.report['events'].append({'phase':phase,'status':'failed','started_at':started_at,'ended_at':datetime.now(timezone.utc).isoformat(),'error_type':error.__class__.__name__,'execution_seconds':time.monotonic()-started})
            if isinstance(error,subprocess.CalledProcessError):
                fd=os.open(self.root/'command-failure.log',os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
                with os.fdopen(fd,'w') as log:
                    log.write((error.stdout or '')+(error.stderr or ''))
            self.persist();raise
        result['status']='passed';result['started_at']=started_at;result['ended_at']=datetime.now(timezone.utc).isoformat()
        self.report['events'].append(result);self.persist();return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',required=True,type=Path)
    parser.add_argument('phase',choices=['plan','cold','upgrade','backup_restore','rollback']);parser.add_argument('--execute',action='store_true')
    args=parser.parse_args()
    if args.phase=='plan' or not args.execute:
        manifest=deployment_manifest(args.repo)
        print(json.dumps({'status':'pending_main_execution_window','phase':args.phase,'project':manifest['project'],
            'required_images':['p4-backend','p4-frontend','candidate-backend','candidate-frontend'],'recorded_images':list(manifest['images']),
            'required_prior_gates':['J5-01 core','J5-02'],'docker_or_database_operations':False}));raise SystemExit(0)
    print(json.dumps(Rehearsal(args.repo).execute(args.phase),ensure_ascii=False))
