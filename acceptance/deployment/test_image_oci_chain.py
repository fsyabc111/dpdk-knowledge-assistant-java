"""模拟P4 containerd OCI index-ID（非config-ID），所有层仅合成小字节。"""
import gzip,hashlib,io,json,tarfile,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import sys
sys.path.insert(0,str(Path(__file__).parent))
import images
from guard import GuardError

class OciChainTest(unittest.TestCase):
 def fixture(self,path,*,manifest_id=False,tamper=None):
  blobs={}
  def put(data,kind):
   raw=json.dumps(data).encode() if isinstance(data,dict) else data;digest='sha256:'+hashlib.sha256(raw).hexdigest();name='blobs/sha256/'+digest[7:];blobs[name]=raw
   return {'digest':digest,'size':len(raw),'mediaType':kind}
  output=io.BytesIO()
  with tarfile.open(fileobj=output,mode='w') as tar:
   data=b'synthetic jar';member=tarfile.TarInfo('app/backend.jar');member.size=len(data);tar.addfile(member,io.BytesIO(data))
  raw=output.getvalue();layer=put(gzip.compress(raw),'application/vnd.oci.image.layer.v1.tar+gzip')
  config=put({'architecture':'arm64','os':'linux','rootfs':{'diff_ids':['sha256:'+hashlib.sha256(raw).hexdigest()]}},'application/vnd.oci.image.config.v1+json')
  manifest=put({'schemaVersion':2,'mediaType':'application/vnd.oci.image.manifest.v1+json','config':config,'layers':[layer]},'application/vnd.oci.image.manifest.v1+json')
  child={**manifest,'platform':{'os':'linux','architecture':'arm64'}}
  index=put({'schemaVersion':2,'mediaType':'application/vnd.oci.image.index.v1+json','manifests':[child,{'digest':'sha256:'+'1'*64,'size':1,'mediaType':manifest['mediaType'],'platform':{'os':'unknown','architecture':'unknown'}}]},'application/vnd.oci.image.index.v1+json')
  root=manifest if manifest_id else index
  docker={'Config':'blobs/sha256/'+config['digest'][7:],'Layers':['blobs/sha256/'+layer['digest'][7:]]}
  if tamper in ('root','child','config','layer_blob'):
   name='blobs/sha256/'+{'root':root,'child':manifest,'config':config,'layer_blob':layer}[tamper]['digest'][7:]
   blobs[name]=b'X'+blobs[name][1:]
  if tamper=='path':docker['Layers']=['other-layer.tar']
  if tamper=='layer_size':blobs['blobs/sha256/'+layer['digest'][7:]]+=b'x'
  blobs['index.json']=json.dumps({'schemaVersion':2,'manifests':[root]}).encode()
  blobs['manifest.json']=json.dumps([docker]).encode()
  with tarfile.open(path,'w') as tar:
   for name,data in blobs.items():
    m=tarfile.TarInfo(name);m.size=len(data);tar.addfile(m,io.BytesIO(data))
  return {'Id':root['digest'],'Os':'linux','Architecture':'arm64'},docker
 def check(self,*,tamper=None,manifest_id=False,wrong_platform=False):
  with tempfile.TemporaryDirectory() as tmp:
   path=Path(tmp)/'image.tar';info,descriptor=self.fixture(path,tamper=tamper,manifest_id=manifest_id)
   if wrong_platform:info['Architecture']='amd64'
   with tarfile.open(path) as saved:return images.archive_metadata(saved,info,descriptor)
 def test_actual_containerd_index_id_and_different_config_digest(self):
  config,layers=self.check();self.assertEqual(config['architecture'],'arm64');self.assertEqual(len(layers),1)
 def test_direct_manifest_id_is_also_bound(self):self.assertEqual(len(self.check(manifest_id=True)[1]),1)
 def test_root_child_config_tampering_rejected(self):
  for target in ('root','child','config'):
   with self.subTest(target=target),self.assertRaises(GuardError):self.check(tamper=target)
 def test_platform_and_docker_manifest_layer_binding_rejected(self):
  for target in ('path','layer_size'):
   with self.subTest(target=target),self.assertRaises(GuardError):self.check(tamper=target)
  with self.assertRaises(GuardError):self.check(wrong_platform=True)

 def record_fixture(self,*,tamper=None):
  with tempfile.TemporaryDirectory() as tmp:
   repo=Path(tmp);root=repo/'.migration-artifacts/j5-03';(root/'build').mkdir(parents=True)
   path=root/'build/candidate-backend.image.tar';info,_=self.fixture(path,tamper=tamper);path.chmod(0o600)
   manifest=root/'deployment-manifest.json';manifest.write_text(json.dumps({'round_id':'abcdef123456','project':'dpdk-java-isolated-abcdef123456','images':{}}));manifest.chmod(0o600)
   info.update(Config={'Labels':{'org.opencontainers.image.revision':'a'*40,'io.dpdk.validation-domain':'isolated-synthetic'}},RepoDigests=[],Size=123)
   with patch.object(images,'docker_identity'),patch.object(images.subprocess,'check_output',side_effect=['a'*40,json.dumps([info])]),patch.object(images.subprocess,'run',side_effect=AssertionError('must not save')):
    return images.record(repo,'candidate-backend',info['Id'],'HEAD')
 def test_complete_oci_record_checks_compressed_blob_and_uncompressed_layer(self):
  result=self.record_fixture();self.assertEqual(result['artifact_sha256']['app/backend.jar'],hashlib.sha256(b'synthetic jar').hexdigest())
 def test_same_size_layer_blob_tampering_is_rejected_before_extract(self):
  with self.assertRaisesRegex(GuardError,'OCI layer blob'):self.record_fixture(tamper='layer_blob')

if __name__=='__main__':unittest.main()
