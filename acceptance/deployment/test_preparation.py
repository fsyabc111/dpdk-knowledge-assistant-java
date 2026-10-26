"""不连接Docker/PG的隔离守卫反例与全源覆盖验证。"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import sys
sys.path.insert(0,str(Path(__file__).parent))
from snapshot import generate, source_entries
from guard import GuardError, docker_identity, env_values

REPO=Path(__file__).resolve().parents[2]
class PreparationTest(unittest.TestCase):
    def test_full_manifest_file_and_directory_includes_are_synthetic(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'sources'; result=generate(REPO/'backend/data/knowledge_sources.yml',target)
            expected=source_entries((REPO/'backend/data/knowledge_sources.yml').read_text())
            self.assertEqual(result['source_count'],len(expected)); self.assertEqual(result['source_count'],10)
            self.assertEqual(result['include_count'],sum(len(x['include']) for x in expected))
            self.assertEqual({c['kind'] for c in result['coverage']},{'file','directory'})
            for coverage in result['coverage']:
                self.assertTrue((target/coverage['generated']).is_file())
                self.assertIn('SYNTHETIC_J5_03_ONLY',(target/coverage['generated']).read_text())
            with self.assertRaises(ValueError): generate(REPO/'backend/data/knowledge_sources.yml',target)
    def test_missing_source_include_and_traversal_fail(self):
        for text in ('sources:\n  - name: a\n    root: ${DPDK_REPO_PATH}\n',
                     'sources:\n  - name: a\n    root: ${DPDK_REPO_PATH}\n    include:\n      - ../outside\n'):
            with self.assertRaises(ValueError): source_entries(text)
    def test_old_context_and_ambient_configuration_fail_before_docker(self):
        for env in ({},{'DPDK_JAVA_DOCKER_CONTEXT':'colima-dpdk-ext'},
            {'DPDK_JAVA_DOCKER_CONTEXT':'colima-java-u12','DOCKER_HOST':'unix:///old.sock'},
            {'DPDK_JAVA_DOCKER_CONTEXT':'colima-java-u12','DOCKER_CONTEXT':'colima-dpdk-ext'}):
            with self.assertRaises(GuardError): docker_identity(env,run=lambda *a,**k:self.fail('unexpected Docker read'))
    def test_endpoint_and_daemon_are_both_verified_and_all_calls_use_explicit_context(self):
        env={'DPDK_JAVA_DOCKER_CONTEXT':'colima-java-u12','DPDK_JAVA_DOCKER_ENDPOINT':'unix://'+str(Path.home()/'.colima/java-u12/docker.sock'),'DPDK_JAVA_DOCKER_DAEMON_ID':'new-daemon'}
        def run(args,**kwargs):
            self.assertEqual(args[:3],['docker','--context','colima-java-u12'])
            return subprocess.CompletedProcess(args,0,json.dumps({'Host':env['DPDK_JAVA_DOCKER_ENDPOINT'],'SkipTLSVerify':False} if 'inspect' in args else {'ID':'new-daemon','Name':'colima-java-u12','OSType':'linux'}))
        self.assertEqual(docker_identity(env,run=run)['daemon_id'],'new-daemon')
        for invalid in ({'ID':'old-daemon','Name':'colima-java-u12','OSType':'linux'}, {'ID':'new-daemon','Name':'colima-dpdk-ext','OSType':'linux'}):
            with self.assertRaises(GuardError): docker_identity(env,run=lambda args,**kw:subprocess.CompletedProcess(args,0,json.dumps({'Host':env['DPDK_JAVA_DOCKER_ENDPOINT']} if 'inspect' in args else invalid)))
    def test_old_env_and_nonignored_env_rejected_without_reading_credentials(self):
        for path in (REPO/'.env',REPO/'deploy/.env',REPO/'deploy-java/.env.example'):
            with self.assertRaises((GuardError,FileNotFoundError)): env_values(REPO,path)
    def test_owned_env_mode_marker_and_full_snapshot_are_mandatory(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo=Path(tmp); root=repo/'.migration-artifacts/j5-03';root.mkdir(parents=True)
            frozen=repo/'backend/data/knowledge_sources.yml';frozen.parent.mkdir(parents=True);frozen.write_bytes((REPO/'backend/data/knowledge_sources.yml').read_bytes())
            generate(frozen,root/'sources');(root/'certs').mkdir();(root/'certs/server.key').write_text('synthetic key placeholder');(root/'certs/server.key').chmod(0o600)
            values={'DPDK_JAVA_ENV_SCHEMA':'j5-03-isolated-v1','DPDK_JAVA_DB_PASSWORD':'synthetic-new-test-password-only-0000','DPDK_JAVA_HTTPS_PORT':'18443','DPDK_JAVA_SOURCE_SNAPSHOT_DIR':str(root/'sources'),'DPDK_JAVA_CERT_DIR':str(root/'certs'),'DPDK_JAVA_PROXY_UID':'1000','DPDK_JAVA_PROXY_GID':'1000'}
            env=root/'runtime.env';env.write_text(''.join(k+'='+v+'\n' for k,v in values.items()));env.chmod(0o600)
            with patch('guard.subprocess.run',return_value=subprocess.CompletedProcess([],0)):
                self.assertEqual(env_values(repo,env)['DPDK_JAVA_ENV_SCHEMA'],'j5-03-isolated-v1')
                env.chmod(0o644)
                with self.assertRaises(GuardError):env_values(repo,env)
                env.chmod(0o600)
                manifest=root/'sources/source-snapshot.json';snapshot=json.loads(manifest.read_text());snapshot['coverage'].pop();manifest.write_text(json.dumps(snapshot))
                with self.assertRaises(GuardError):env_values(repo,env)
    def test_backup_keeps_schema_and_excludes_only_task_input_data(self):
        text=(REPO/'acceptance/deployment/docker_pg.py').read_text()
        self.assertIn('--exclude-table-data=public.rag_task_inputs',text)
        self.assertNotIn('--exclude-table=',text);self.assertNotIn('--schema-only',text)

if __name__=='__main__':unittest.main()
