"""冻结Python代码的独立P5库时钟探针；不调用旧P0 runner，不写源代码/黄金。

先四项显式PG配置+manifest实例身份校验，创建p5_contract_ owned新库；
清ambient和.env后导入本副本backend；只运行真实ASGI前51个冻结请求，记录固定
clock调用者类方法栈（不含参数/业务内容）。用于定位Java多读墙钟，不放宽比较。
"""
import argparse
import asyncio
from contextvars import ContextVar
import importlib
import inspect
import json
import os
from pathlib import Path
import sys
import threading
from unittest.mock import patch
from acceptance.contract.isolation import PgIsolation

ROOT=Path(__file__).resolve().parents[2]
CASE=ContextVar('j5_clock_case',default='setup')
CLOCK_CALLS=[]
class ProbeComplete(Exception):pass

def configure(h,db,out):
    h.verify_owned(db)
    keep={k:os.environ[k] for k in ('PATH','HOME','TMPDIR','LANG','LC_ALL','SYSTEMROOT') if k in os.environ}
    os.environ.clear();os.environ.update(keep)
    os.environ.update(PYTHONDONTWRITEBYTECODE='1',PGPASSWORD=h.password,PGOPTIONS='-c timezone=UTC',DATABASE_BACKEND='postgres',
        DATABASE_URL=db.url.replace('://','://'+h.user+'@'),AUTH_MODE='account',BCRYPT_ROUNDS='4',APP_ENV='p5-synthetic-clock',LOG_LEVEL='ERROR',LOG_FILE='',
        LLM_API_BASE='http://127.0.0.1:9/v1',LLM_API_KEY='p5-synthetic',EMBEDDING_API_BASE='http://127.0.0.1:9/v1',EMBEDDING_API_KEY='p5-synthetic',
        HARNESS_MCP_URL='http://127.0.0.1:9/mcp',MAINTENANCE_API_KEY='p0-synthetic-maintenance',RATE_LIMIT_CHAT_PER_MINUTE='100000',
        LLM_DAILY_QUOTA_DEFAULT='100000',GRADING_DAILY_QUOTA='100000',DATABASE_POOL_MAX_SIZE='20',ASSISTANT_PROJECT_PATH=str(ROOT),
        DPDK_REPO_PATH=str(out/'no-external-repo'),KNOWLEDGE_CONFIG=str(ROOT/'migration/fixtures/p0-v1/knowledge.yml'))
    os.chdir(out);sys.path.insert(0,str(ROOT));sys.path.insert(0,str(ROOT/'backend'))
    from app.config import Settings,get_settings
    Settings.model_config['env_file']=None;get_settings.cache_clear()
    settings=Settings(_env_file=None)
    if settings.database_url!=os.environ['DATABASE_URL']:raise RuntimeError('Python目标设置不符')
    from migration.oracle.safety import install_network_guard
    install_network_guard()
    # 在本轮guard通过的空库建立冻结schema，不调用含P0硬编码目标/证据路径的旧run/configure。
    from app.db import init_db
    init_db()
    from alembic import command
    from alembic.config import Config
    import sqlalchemy
    cfg=Config(str(ROOT/'backend/alembic.ini'));cfg.set_main_option('script_location',str(ROOT/'backend/alembic'))
    original=sqlalchemy.engine_from_config
    def engine(section,*args,**kwargs):
        section=dict(section);section['sqlalchemy.url']=section['sqlalchemy.url'].replace('postgresql://','postgresql+psycopg://',1)
        return original(section,*args,**kwargs)
    with patch('sqlalchemy.engine_from_config',engine):command.upgrade(cfg,'head')
    init_db();h.verify_owned(db)

def install_clock_probe():
    from migration.oracle.runtime import freeze_clock
    patches=freeze_clock()
    fixed=importlib.import_module('app.services.session_service').datetime
    class ObservedDatetime(fixed):
        @classmethod
        def now(cls,tz=None):
            value=fixed.now(tz)
            frames=[]
            for frame in inspect.stack(context=0):
                path=Path(frame.filename)
                if path.is_relative_to(ROOT/'backend'):frames.append(str(path.relative_to(ROOT/'backend'))+':'+frame.function+':'+str(frame.lineno))
                if len(frames)>=8:break
            CLOCK_CALLS.append({'case_id':CASE.get(),'value':value.isoformat(),'thread':threading.current_thread().name,'callers':frames})
            return value
    for item in patches:
        if item.attribute=='datetime':setattr(item.target,'datetime',ObservedDatetime)
    return patches

async def probe(limit):
    from migration.oracle.runtime import seed
    from migration.oracle.ports import install_ports
    from migration.oracle.route_cases import Cases,success
    import httpx
    from app.main import app
    CASE.set('seed');seeds=await seed();ports=install_ports()
    class ProbeCases(Cases):
        async def request(self,route,kind,*args,**kwargs):
            if len(self.observations)>=limit:raise ProbeComplete()
            CASE.set(route+'::'+kind)
            return await super().request(route,kind,*args,**kwargs)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://p5-clock.test') as client:
        cases=ProbeCases(client)
        try:await success(cases,seeds)
        except ProbeComplete:pass
    for item in reversed(ports):item.stop()
    return {'observations':len(cases.observations),'cases':[o['case_id'] for o in cases.observations]}

def main():
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--limit',type=int,default=51);a=p.parse_args()
    out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
    h=PgIsolation.from_env(out/'python-isolation.json');identity=h.verify_instance();db=h.create('pythonclock')
    patches=[]
    try:
        configure(h,db,out);patches=install_clock_probe();result=asyncio.run(probe(a.limit));result['identity']=identity
        (out/'python-clock-calls.json').write_text(json.dumps(CLOCK_CALLS,ensure_ascii=False,indent=2));(out/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps({'observations':result['observations'],'clock_calls':len(CLOCK_CALLS)}))
    finally:
        for item in reversed(patches):item.stop()
        if 'app.db' in sys.modules:sys.modules['app.db'].close_database_provider()
        h.drop(db)
if __name__=='__main__':main()
