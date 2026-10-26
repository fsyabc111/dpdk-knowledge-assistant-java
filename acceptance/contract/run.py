"""显式隔离资源生命周期；只运行本轮owned候选库，所有原始产物gitignored。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from acceptance.contract.isolation import PgIsolation
from acceptance.contract.seed import seed
from acceptance.contract.collect import Collector,ROOT,write

def wait_state(path,process):
    for _ in range(300):
        if path.exists():return json.loads(path.read_text())
        if process.poll() is not None:raise RuntimeError('本轮进程启动失败，见脱敏本地log')
        time.sleep(.1)
    raise RuntimeError('进程启动超时')

def main():
    p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=Path);p.add_argument('--limit',type=int);p.add_argument('--keep',action='store_true');p.add_argument('--extended',action='store_true');a=p.parse_args()
    out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
    if (out/'isolation.json').exists():raise RuntimeError('产物轮次目录已存在；请新建输出目录，不覆盖既有证据')
    from migration.oracle.verify import load_manifest
    load_manifest(ROOT/'migration/fixtures/p0-v2','p0-v2')
    h=PgIsolation.from_env(out/'isolation.json');write(out/'instance-identity.json',h.verify_instance());db=h.create('candidate');write(out/'database.json',db.__dict__)
    procs=[];logs=[]
    try:
        flog=(out/'upstream.log').open('w');logs.append(flog)
        fake=subprocess.Popen([sys.executable,'-m','acceptance.contract.fake_upstream','--state',str(out/'upstream-state.json')],stdout=flog,stderr=subprocess.STDOUT,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'},cwd=ROOT);procs.append(fake)
        upstream=wait_state(out/'upstream-state.json',fake)['base_url']
        env={**os.environ,'J5_DATABASE_NAME':db.name,'J5_DATABASE_URL':'jdbc:'+db.url,'J5_DATABASE_COMMENT':db.comment,'J5_UPSTREAM_BASE':upstream,'J5_SERVER_STATE':str(out/'server-state.json')}
        log=(out/'candidate.log').open('w');logs.append(log)
        server=subprocess.Popen([str(ROOT/'acceptance/contract/start_server.sh')],env=env,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT);procs.append(server)
        state=wait_state(out/'server-state.json',server)
        write(out/'seed.json',seed(h,db,state['control_url'],'ui' if a.extended else 'p0'))
        if a.extended:
            from acceptance.contract.extensions import Extensions
            return 0 if Extensions(h,db,state,upstream,out).run() else 1
        golden=json.loads((ROOT/'migration/fixtures/p0-v2/python-golden.json').read_text())
        collector=Collector(h,db,state,upstream,out);trace,verdict=collector.collect(golden,a.limit)
        status,_,raw=collector.http('GET','/verify-diagnostic',base=state['control_url'])
        write(out/'verify-diagnostic.json',{'http_status':status,'result':json.loads(raw)})
        from acceptance.contract.collect import snapshot
        before=snapshot(h,db)
        status,_,raw=collector.http('GET','/fingerprint-negative',base=state['control_url'])
        after=snapshot(h,db)
        write(out/'fingerprint-negative.json',{'http_status':status,'result':json.loads(raw),'business_unchanged':before==after})
        if status!=200 or before!=after:raise RuntimeError('fingerprint negative or restoration failed')
        return 0 if verdict['status']=='pass' and not collector.blocked else 1
    finally:
        if not a.keep:
            for proc in reversed(procs):
                if proc.poll() is None:
                    proc.terminate()
                    try:proc.wait(timeout=10)
                    except subprocess.TimeoutExpired:proc.kill();proc.wait()
            for log in logs:log.close()
            h.drop(db)
        else:write(out/'processes.json',{'pids':[p.pid for p in procs],'database':db.name})
if __name__=='__main__':raise SystemExit(main())
