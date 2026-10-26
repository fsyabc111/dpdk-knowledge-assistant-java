"""新Compose PG的owned数据库工具；先只读核验身份，保留所有恢复资产，不提供DROP。"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
from guard import GuardError, docker_identity, env_values, deployment_manifest
from business_proof import proof_sql,parse_proof

ROLE='dpdk_java_isolated'
BASE='dpdk_java_isolated'
DATA_DIR='/var/lib/postgresql/data/java-isolated'
BASE_COMMENT='dpdk-java-isolated-rehearsal'
PROJECT='dpdk-java-isolated'

class DockerPg:
    def __init__(self,repo,*,run=None):
        self.repo=Path(repo).resolve();self.run=run or subprocess.run
        self.values=env_values(self.repo,os.environ.get('DPDK_JAVA_ENV_FILE',''))
        self.identity=docker_identity(run=self.run)
        self.deployment=deployment_manifest(self.repo);self.project=self.deployment['project']
        self.root=self.repo/'.migration-artifacts/j5-03'
        self.manifest_path=self.root/'owned-databases.json'
        self.manifest=json.loads(self.manifest_path.read_text()) if self.manifest_path.exists() else {
            'version':1,'round_id':self.deployment['round_id'],'instance':self.identity,'owned':{}}
        if self.manifest['instance']!=self.identity:raise GuardError('owned manifest不是当前新daemon')
        self.container=None
    def _command(self,args,*,input=None,binary=False):
        result=self.run(['docker','--context','colima-java-u12',*args],input=input,capture_output=True,text=not binary,check=True)
        return result.stdout
    def _container(self):
        if self.container:return self.container
        result=self.run([str(self.repo/'scripts-java/compose.sh'),'--apply','ps','-q','postgres-java'],capture_output=True,text=True,check=True)
        container=result.stdout.strip()
        if not re.fullmatch('[a-f0-9]{12,64}',container):raise GuardError('没有唯一新Compose PG容器')
        info=json.loads(self._command(['inspect',container]))[0]
        labels=info['Config'].get('Labels',{})
        if labels.get('com.docker.compose.project')!=self.project or labels.get('com.docker.compose.service')!='postgres-java' or not info['State']['Running']:
            raise GuardError('拒绝非owned project/service或未运行PG容器')
        if not any(m.get('Type')=='volume' and m.get('Name')==self.project+'_java-pg-data' and m.get('Destination')=='/var/lib/postgresql/data' for m in info['Mounts']):
            raise GuardError('PG卷身份不符')
        self.container=container;return container
    def _sql(self,database,sql):
        if not re.fullmatch('[a-z0-9_]+',database):raise GuardError('数据库名字非法')
        return self._command(['exec','-i',self._container(),'psql','--no-psqlrc','-v','ON_ERROR_STOP=1','-U',ROLE,'-d',database,'-At'],input=sql)
    def _identity(self,database,comment=None):
        raw=self._sql(database,"SELECT json_build_object('database',current_database(),'user',current_user,'data_dir',current_setting('data_directory'),'comment',shobj_description(oid,'pg_database')) FROM pg_database WHERE datname=current_database();")
        actual=json.loads(raw)
        if actual['database']!=database or actual['user']!=ROLE or actual['data_dir']!=DATA_DIR or (comment is not None and actual['comment']!=comment):
            raise GuardError('PG数据库/role/comment/data_dir不符；零写入退出')
        return actual
    def verify_instance(self):
        # 每个写入阶段重新核验daemon，拒绝初始化后ambient/实例变更。
        docker_identity(run=self.run)
        # 维护连接核对实例，另核对唯一base库comment；任何CREATE/DDL之前调用。
        maintenance=self._identity('postgres');base=self._identity(BASE,BASE_COMMENT)
        return {'maintenance':maintenance,'base':base}
    def _persist(self):
        path=self.manifest_path.with_suffix('.tmp')
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
        with os.fdopen(fd,'w') as stream:json.dump(self.manifest,stream,indent=2)
        os.replace(path,self.manifest_path)
    def create(self,purpose):
        if purpose not in ('upgrade','restore','cold'):raise GuardError('不允许的deployment数据库用途')
        self.verify_instance()
        name='p5_deployment_'+self.manifest['round_id']+'_'+purpose
        if name in self.manifest['owned']:raise GuardError('owned库已登记，拒绝覆盖/自动收养')
        if self._sql('postgres',"SELECT count(*) FROM pg_database WHERE datname='"+name+"';").strip()!='0':raise GuardError('目标数据库已存在，拒绝收养')
        comment='j5-03:'+self.manifest['round_id']+':'+purpose
        self.manifest['owned'][name]={'purpose':purpose,'comment':comment,'state':'reserved'};self._persist()
        self._sql('postgres','CREATE DATABASE '+name+';')
        self._sql('postgres',"COMMENT ON DATABASE "+name+" IS '"+comment+"';")
        self._identity(name,comment)
        self.manifest['owned'][name]['state']='created';self._persist();return name
    def verify_owned(self,name):
        self.verify_instance()
        owned=self.manifest['owned'].get(name)
        if not owned or owned['state']=='reserved':raise GuardError('数据库不在本轮owned manifest')
        return self._identity(name,owned['comment'])
    def empty(self,name):
        self.verify_owned(name)
        count=self._sql(name,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S');").strip()
        if count!='0':raise GuardError('目标不是空库，禁止seed/恢复覆盖')
    def synthetic0013(self,name):
        self.empty(name)
        baseline=(self.repo/'backend-java/src/main/resources/db/migration/B0015__baseline.sql').read_text()
        # 与既有MigrationThreePathsIntegrationTest相同的合成0013构造；不是原库downgrade。
        sql=baseline+"\nALTER TABLE grading_submissions DROP COLUMN task_job_id;\nALTER TABLE rag_requests DROP COLUMN purpose;\nCREATE TABLE alembic_version(version_num varchar(32) PRIMARY KEY);\nINSERT INTO alembic_version VALUES ('0013_task_recovery');\n"
        sql+="INSERT INTO grading_submissions(id,user_id,session_id,protocol,code_hash,score,passed,total,results_json,created_at) VALUES ('j5_legacy_grade','U20260000061',NULL,'dns','synthetic_legacy_hash',80,1,1,'{}'::jsonb,now());\n"
        self._sql(name,sql)
        self.manifest['owned'][name].update(state='synthetic0013',schema_source_sha256=hashlib.sha256(baseline.encode()).hexdigest());self._persist()
    def backup(self,name,destination):
        self.verify_instance() if name==BASE else self.verify_owned(name)
        destination=Path(destination)
        if not destination.is_absolute() or destination.exists() or not destination.resolve().is_relative_to(self.root):raise GuardError('备份必须为本轮尚不存在的受控绝对路径')
        data=self._command(['exec',self._container(),'pg_dump','-U',ROLE,'-d',name,'--format=custom','--exclude-table-data=public.rag_task_inputs'],binary=True)
        if not data.startswith(b'PGDMP'):raise GuardError('不是有效custom dump标识')
        fd=os.open(destination,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as stream:stream.write(data)
        toc=self._command(['exec','-i',self._container(),'pg_restore','--list'],input=data,binary=True).decode()
        if 'TABLE public rag_task_inputs ' not in toc or 'TABLE DATA public rag_task_inputs ' in toc:raise GuardError('dump目录未保留临时输入表结构或仍含数据')
        return {'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'source_database':name,'excludes_data':['public.rag_task_inputs'],'schema_retained':True,'toc_verified':True}
    def restore(self,name,backup):
        self.empty(name);backup=Path(backup).resolve(strict=True)
        if not backup.is_relative_to(self.root):raise GuardError('只允许本轮受控备份')
        data=backup.read_bytes()
        if not data.startswith(b'PGDMP'):raise GuardError('无效custom dump')
        self._command(['exec','-i',self._container(),'pg_restore','-U',ROLE,'-d',name,'--exit-on-error','--single-transaction','--no-owner','--no-privileges'],input=data,binary=True)
        self.verify_owned(name)
        self.manifest['owned'][name].update(state='restored',backup_sha256=hashlib.sha256(data).hexdigest());self._persist()
    def read_assertions(self,name):
        self.verify_owned(name)
        # 只读汇总，不输出业务正文/源码/密码哈希/token。
        tables=('users','assistant_sessions','assistant_messages','assistant_citations','rag_requests','rag_task_jobs','rag_task_inputs','grading_submissions','upstream_usage_records','rag_releases','rag_release_nodes')
        counts={table:int(self._sql(name,'SELECT count(*) FROM '+table+';').strip()) for table in tables}
        counts['active_release']=self._sql(name,"SELECT coalesce(release_id,'') FROM rag_index_state WHERE name='active';").strip()
        counts['task_input_table_present']=self._sql(name,"SELECT to_regclass('public.rag_task_inputs') IS NOT NULL;").strip()=='t'
        return counts

    def business_proof(self,name):
        self.verify_owned(name)
        tables=self._sql(name,"SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename COLLATE \"C\";").splitlines()
        return parse_proof(self._sql(name,proof_sql(tables)),tables)

    def seed_temporary_sentinel(self,name):
        self.verify_owned(name)
        self._sql(name,"INSERT INTO rag_task_inputs(task_id,question,source_text,source_origin,created_at,expires_at) VALUES ('j5_backup_temporary_input','synthetic temporary input','J5_BACKUP_SOURCE_SENTINEL_819ac','synthetic',now()::text,(now()+interval '1 day')::text);")
    def assert_no_longterm_sentinel(self,name):
        self.verify_owned(name)
        tables=self._sql(name,"SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'rag_task_inputs' ORDER BY tablename;").splitlines()
        for table in tables:
            if not re.fullmatch('[a-zA-Z0-9_]+',table):raise GuardError('unexpected table identity')
            sql = 'SELECT count(*) FROM "'+table+'" t WHERE row_to_json(t)::text LIKE ' + "'%J5_BACKUP_SOURCE_SENTINEL_819ac%';"
            count=int(self._sql(name,sql).strip())
            if count:raise GuardError('源码哨兵进入长期表')
        return {'longterm_tables_checked':len(tables),'sentinel_matches':0}
