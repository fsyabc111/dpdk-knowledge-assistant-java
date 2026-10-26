"""Docker PG/overlay拒绝反例；完全不连接Docker/PG。"""
import json
from pathlib import Path
import tempfile
import unittest
import sys
sys.path.insert(0,str(Path(__file__).parent))
from docker_pg import DockerPg
from guard import GuardError
from override import validate

class DockerPgTest(unittest.TestCase):
    def test_failed_preflight_prevents_create(self):
        pg=DockerPg.__new__(DockerPg)
        pg.verify_instance=lambda:(_ for _ in ()).throw(GuardError('wrong identity'))
        pg._sql=lambda *args:self.fail('SQL must not be written')
        with self.assertRaises(GuardError):pg.create('upgrade')
    def test_nonempty_restore_target_prevents_restore_command(self):
        pg=DockerPg.__new__(DockerPg);pg.verify_owned=lambda name:None
        pg._sql=lambda *args:'1';pg._command=lambda *args,**kwargs:self.fail('restore must not execute')
        with self.assertRaises(GuardError):pg.restore('owned',Path('/not/read'))
    def test_unowned_database_is_never_adopted(self):
        pg=DockerPg.__new__(DockerPg);pg.verify_instance=lambda:None;pg.manifest={'owned':{}}
        pg._identity=lambda *args:self.fail('unowned database must not be queried')
        with self.assertRaises(GuardError):pg.verify_owned('legacy_business')
    def test_sentinel_scan_is_read_only_and_uses_literal_marker(self):
        pg=DockerPg.__new__(DockerPg);pg.verify_owned=lambda name:None
        calls=[]
        def sql(name,statement):
            calls.append(statement)
            return 'users\nrag_requests' if statement.startswith('SELECT tablename') else '0'
        pg._sql=sql
        self.assertEqual(pg.assert_no_longterm_sentinel('owned')['sentinel_matches'],0)
        self.assertIn("LIKE '%J5_BACKUP_SOURCE_SENTINEL_819ac%'",calls[1])
        self.assertTrue(all(statement.startswith('SELECT') for statement in calls))
    def test_restore_only_targets_verified_empty_owned_db_and_never_cleans(self):
        with tempfile.TemporaryDirectory() as tmp:
            pg=DockerPg.__new__(DockerPg);pg.root=Path(tmp).resolve();pg.verify_owned=lambda name:None
            calls=[];pg.empty=lambda name:calls.append(('empty',name));pg._container=lambda:'owned-container'
            pg._command=lambda args,**kwargs:calls.append(('command',args,kwargs))
            pg.manifest={'owned':{'p5_owned':{}}};pg._persist=lambda:None
            backup=pg.root/'backup.dump';backup.write_bytes(b'PGDMPsynthetic')
            pg.restore('p5_owned',backup)
            self.assertEqual(calls[0],('empty','p5_owned'))
            args=calls[1][1];self.assertIn('--single-transaction',args);self.assertIn('--exit-on-error',args)
            self.assertNotIn('--clean',args);self.assertNotIn('--create',args)
    def test_dump_toc_requires_input_table_structure_without_table_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            pg=DockerPg.__new__(DockerPg);pg.root=Path(tmp).resolve();pg.verify_owned=lambda name:None;pg._container=lambda:'owned-container'
            commands=[]
            def command(args,**kwargs):
                commands.append(args)
                return b'PGDMPsynthetic' if 'pg_dump' in args else b'1; TABLE public rag_task_inputs owner\n'
            pg._command=command
            result=pg.backup('p5_owned',pg.root/'backup.dump')
            self.assertTrue(result['toc_verified']);self.assertIn('--exclude-table-data=public.rag_task_inputs',commands[0])
            self.assertEqual((pg.root/'backup.dump').stat().st_mode&0o777,0o600)
    def test_only_recorded_images_and_owned_local_db_overlay_are_allowed(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo=Path(tmp);root=repo/'.migration-artifacts/j5-03';root.mkdir(parents=True)
            manifest={'round_id':'abcdef123456','project':'dpdk-java-isolated-abcdef123456','images':{
                'p4-backend':{'reference':'dpdk-java-isolated-backend@sha256:one'},'p4-frontend':{'reference':'dpdk-java-isolated-frontend@sha256:two'}}}
            path=root/'deployment-manifest.json';path.write_text(json.dumps(manifest));path.chmod(0o600)
            db='p5_deployment_abcdef123456_restore';comment='j5-03:abcdef123456:restore'
            (root/'owned-databases.json').write_text(json.dumps({'round_id':'abcdef123456','owned':{db:{'comment':comment,'state':'created'}}}))
            data={'services':{'backend-java':{'image':'dpdk-java-isolated-backend@sha256:one','build':None,'environment':{'DPDK_JAVA_DB_URL':'jdbc:postgresql://postgres-java:5432/'+db,'DPDK_JAVA_DB_NAME':db,'DPDK_JAVA_DB_COMMENT':comment}},'frontend-java':{'image':'dpdk-java-isolated-frontend@sha256:two','build':None}}}
            overlay=root/'compose-restore.json'
            def write():overlay.write_text(json.dumps(data));overlay.chmod(0o600)
            write();self.assertEqual(validate(repo,overlay),data)
            for key,value in [('image','legacy-app:latest'),('build',{'context':'/old'}),('privileged',True)]:
                altered=json.loads(json.dumps(data));altered['services']['backend-java'][key]=value
                overlay.write_text(json.dumps(altered))
                with self.assertRaises(GuardError):validate(repo,overlay)
            write();data['services']['backend-java']['environment']['DPDK_JAVA_DB_URL']='jdbc:postgresql://production:5432/'+db;write()
            with self.assertRaises(GuardError):validate(repo,overlay)

if __name__=='__main__':unittest.main()
