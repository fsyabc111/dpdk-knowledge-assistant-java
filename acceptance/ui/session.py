"""真实前端浏览器验收的专属资源生命周期；不加载.env，不借用其他验收库。"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from acceptance.contract.isolation import PgIsolation
from acceptance.contract.seed import seed
from acceptance.contract.run import wait_state

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--port', type=int, default=15174)
    args = parser.parse_args()
    out = args.out.resolve()
    if not out.is_relative_to(ROOT / '.migration-artifacts') or out.exists():
        raise RuntimeError('必须使用全新gitignored产物目录')
    out.mkdir(parents=True)
    isolated = PgIsolation.from_env(out / 'isolation.json', allowed_prefixes=('p5_ui_',))
    identity = isolated.verify_instance()
    database = isolated.create('browser')
    processes = []
    logs = []
    def start(command, env, name, cwd=ROOT):
        log = (out / (name + '.log')).open('w'); logs.append(log)
        proc = subprocess.Popen(command, env=env, cwd=cwd, stdout=log, stderr=subprocess.STDOUT)
        processes.append(proc); return proc
    def stop(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        env = {**os.environ, 'PYTHONDONTWRITEBYTECODE':'1'}
        fake = start([sys.executable, '-m', 'acceptance.contract.fake_upstream', '--state', str(out/'upstream-state.json')], env, 'upstream')
        upstream = wait_state(out/'upstream-state.json', fake)['base_url']
        server_env = {**env, 'J5_DATABASE_NAME':database.name, 'J5_DATABASE_URL':'jdbc:'+database.url,
                      'J5_DATABASE_COMMENT':database.comment, 'J5_UPSTREAM_BASE':upstream,
                      'J5_SERVER_STATE':str(out/'server-state.json')}
        server = start([str(ROOT/'acceptance/contract/start_server.sh')], server_env, 'candidate')
        state = wait_state(out/'server-state.json', server)
        accounts = seed(isolated, database, state['control_url'], 'ui')
        config = out/'vite.config.mjs'
        config.write_text('import base from '+json.dumps(str(ROOT/'frontend/vite.config.js'))+';\n'
                          +'export default {...base,server:{host:"127.0.0.1",port:'+str(args.port)
                          +',strictPort:true,proxy:{"/api":'+json.dumps(state['base_url'])+'}}};\n')
        start(['node', str(ROOT/'frontend/node_modules/vite/bin/vite.js'), '--config', str(config)],
              {**env, 'VITE_API_BASE':''}, 'frontend', ROOT/'frontend')
        (out/'session.json').write_text(json.dumps({'source':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
              'url':'http://127.0.0.1:'+str(args.port), 'state':state, 'upstream':upstream,
              'database':database.name, 'identity':identity, 'seed':accounts,
              'pids':[proc.pid for proc in processes]},ensure_ascii=False,indent=2))
        os.chmod(out/'session.json',0o600)
        print('ui_session_ready',out/'session.json',flush=True)
        while all(proc.poll() is None for proc in processes): time.sleep(.25)
        raise RuntimeError('UI专属进程异常退出，保留本轮log')
    except KeyboardInterrupt:
        pass
    finally:
        for proc in reversed(processes):
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=10)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()
        for log in logs: log.close()
        isolated.drop(database)

if __name__ == '__main__': main()
