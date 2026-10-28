"""静态审查P2回归：无Docker/PG调用，只使用固定manifest与真实合成tar。"""
import hashlib,io,json,os,tarfile,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import sys
sys.path.insert(0,str(Path(__file__).parent))
from guard import GuardError
import images

class ReviewRegressionTest(unittest.TestCase):
 def setup_repo(self,tmp):
  repo=Path(tmp);root=repo/'.migration-artifacts/j5-03';root.mkdir(parents=True)
  manifest={'round_id':'abcdef123456','project':'dpdk-java-isolated-abcdef123456','images':{}}
  p=root/'deployment-manifest.json';p.write_text(json.dumps(manifest));p.chmod(0o600)
  return repo,root,manifest
 def test_backup_selects_current_state_and_rejects_stale_explicit_overlay(self):
  import backup_cli
  with tempfile.TemporaryDirectory() as tmp:
   repo,root,_=self.setup_repo(tmp);active=root/'compose-restore.json';active.write_text('{}');active.chmod(0o600)
   state=root/'scenario-state.json';state.write_text(json.dumps({'overlay':str(active)}));state.chmod(0o600)
   data={'services':{'backend-java':{'environment':{'DPDK_JAVA_DB_NAME':'p5_owned_restore'}}}}
   with patch.object(backup_cli,'validate',return_value=data):
    self.assertEqual(backup_cli.select_target(repo,{})[0],'p5_owned_restore')
    with self.assertRaises(GuardError):backup_cli.select_target(repo,{'DPDK_JAVA_COMPOSE_OVERRIDE':str(root/'compose-upgrade.json')})
   state.chmod(0o644)
   with self.assertRaises(GuardError):backup_cli.select_target(repo,{})
 def test_backup_without_state_never_silently_selects_base(self):
  import backup_cli
  with tempfile.TemporaryDirectory() as tmp:
   repo,_,_=self.setup_repo(tmp)
   with self.assertRaises(GuardError):backup_cli.select_target(repo,{})
   self.assertEqual(backup_cli.select_target(repo,{},explicit_base=True),(backup_cli.BASE,None))
   with self.assertRaises(GuardError):backup_cli.select_target(repo,{'DPDK_JAVA_COMPOSE_OVERRIDE':'/old.json'},explicit_base=True)
 def test_backend_database_mismatch_prevents_backup(self):
  import backup_cli
  with self.assertRaises(GuardError):backup_cli.check_backend_target({'Config':{'Env':['DPDK_JAVA_DB_NAME=dpdk_java_isolated','DPDK_JAVA_DB_URL=jdbc:postgresql://postgres-java:5432/dpdk_java_isolated']}},'p5_owned_restore','owned-comment')
 def archive(self,path,wrong_config=False):
  layer=io.BytesIO()
  with tarfile.open(fileobj=layer,mode='w') as tar:
   raw=b'actual synthetic jar';entry=tarfile.TarInfo('app/backend.jar');entry.size=len(raw);tar.addfile(entry,io.BytesIO(raw))
  layer=layer.getvalue();config=json.dumps({'rootfs':{'diff_ids':['sha256:'+hashlib.sha256(layer).hexdigest()]}}).encode();identity='sha256:'+hashlib.sha256(config).hexdigest()
  with tarfile.open(path,'w') as tar:
   for name,data in [('config.json',config if not wrong_config else b'wrong'),('layer.tar',layer),('manifest.json',json.dumps([{'Config':'config.json','Layers':['layer.tar'],'RepoTags':[]}]).encode())]:
    entry=tarfile.TarInfo(name);entry.size=len(data);tar.addfile(entry,io.BytesIO(data))
  path.chmod(0o600);return identity
 def test_fresh_round_creates_guarded_build_parent_and_uses_actual_id(self):
  with tempfile.TemporaryDirectory() as tmp:
   repo,root,manifest=self.setup_repo(tmp);prepared=root/'source.tar';identity=self.archive(prepared)
   info={'Id':identity,'Config':{'Labels':{'org.opencontainers.image.revision':'a'*40,'io.dpdk.validation-domain':'isolated-synthetic'}},'RepoDigests':[],'Size':123}
   def run(command,**kw):
    self.assertTrue((root/'build').is_dir());self.assertIn(identity,command)
    (root/'build/candidate-backend.image.tar').write_bytes(prepared.read_bytes())
   with patch.object(images,'docker_identity'),patch.object(images.subprocess,'check_output',side_effect=['a'*40,json.dumps([info])]),patch.object(images.subprocess,'run',side_effect=run):
    actual=images.record(repo,'candidate-backend','local:build','HEAD')
   self.assertEqual(actual['reference'],identity)
 def test_mismatched_existing_archive_is_rejected(self):
  with tempfile.TemporaryDirectory() as tmp:
   repo,root,_=self.setup_repo(tmp);(root/'build').mkdir();archive=root/'build/candidate-backend.image.tar';identity=self.archive(archive,wrong_config=True)
   info={'Id':identity,'Config':{'Labels':{'org.opencontainers.image.revision':'a'*40,'io.dpdk.validation-domain':'isolated-synthetic'}},'RepoDigests':[],'Size':123}
   with patch.object(images,'docker_identity'),patch.object(images.subprocess,'check_output',side_effect=['a'*40,json.dumps([info])]),patch.object(images.subprocess,'run',side_effect=AssertionError('must not overwrite')):
    with self.assertRaises(GuardError):images.record(repo,'candidate-backend',identity,'HEAD')

 def test_layer_tamper_rejected_even_when_config_identity_matches(self):
  with tempfile.TemporaryDirectory() as tmp:
   repo,root,_=self.setup_repo(tmp);(root/'build').mkdir();archive=root/'build/candidate-backend.image.tar';identity=self.archive(archive)
   with tarfile.open(archive) as tar:members={m.name:tar.extractfile(m).read() for m in tar if m.isfile()}
   members['layer.tar']=b'tampered'
   with tarfile.open(archive,'w') as tar:
    for name,data in members.items():
     item=tarfile.TarInfo(name);item.size=len(data);tar.addfile(item,io.BytesIO(data))
   info={'Id':identity,'Config':{'Labels':{'org.opencontainers.image.revision':'a'*40,'io.dpdk.validation-domain':'isolated-synthetic'}},'RepoDigests':[],'Size':123}
   with patch.object(images,'docker_identity'),patch.object(images.subprocess,'check_output',side_effect=['a'*40,json.dumps([info])]):
    with self.assertRaisesRegex(GuardError,'layer内容'):images.record(repo,'candidate-backend',identity,'HEAD')
 def test_build_symlink_cannot_write_outside_owned_directory(self):
  with tempfile.TemporaryDirectory() as tmp,tempfile.TemporaryDirectory() as outside:
   repo,root,_=self.setup_repo(tmp);(root/'build').symlink_to(outside,target_is_directory=True)
   info={'Id':'sha256:'+'b'*64,'Config':{'Labels':{'org.opencontainers.image.revision':'a'*40,'io.dpdk.validation-domain':'isolated-synthetic'}},'RepoDigests':[],'Size':1}
   with patch.object(images,'docker_identity'),patch.object(images.subprocess,'check_output',side_effect=['a'*40,json.dumps([info])]),patch.object(images.subprocess,'run',side_effect=AssertionError('no save')):
    with self.assertRaises(GuardError):images.record(repo,'candidate-backend',info['Id'],'HEAD')
 def test_valid_existing_p4_record_is_not_rewritten(self):
  with tempfile.TemporaryDirectory() as tmp:
   repo,root,manifest=self.setup_repo(tmp);(root/'build').mkdir();archive=root/'build/p4-backend.image.tar';identity=self.archive(archive)
   with tarfile.open(archive) as tar:
    with tarfile.open(fileobj=tar.extractfile('layer.tar')) as layer:jar=layer.extractfile('app/backend.jar').read()
   previous={'image_id':identity,'source_revision':'a'*40,'archive_name':archive.name,'archive_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'artifact_sha256':{'app/backend.jar':hashlib.sha256(jar).hexdigest()},'size':123,'reference':'retained-existing-reference','tag':'retained-existing-tag'}
   manifest['images']['p4-backend']=previous;p=root/'deployment-manifest.json';p.write_text(json.dumps(manifest))
   info={'Id':identity,'Config':{'Labels':{'org.opencontainers.image.revision':'a'*40,'io.dpdk.validation-domain':'isolated-synthetic'}},'RepoDigests':[],'Size':123}
   before=p.read_bytes()
   with patch.object(images,'docker_identity'),patch.object(images.subprocess,'check_output',side_effect=['a'*40,json.dumps([info])]):
    self.assertEqual(images.record(repo,'p4-backend',identity,'HEAD'),previous)
   self.assertEqual(p.read_bytes(),before)

if __name__=='__main__':unittest.main()
