"""账号leader/operator与静态leader/operator必须是不同主体，不复用旧token。"""
import unittest
from acceptance.contract.extensions import Extensions,multipart,MAX_BYTES,response_json
class ExtensionInputTests(unittest.TestCase):
    def test_static_and_account_tokens_do_not_alias(self):
        e=Extensions(None,None,{},'http://127.0.0.1:1','.')
        for role in ('leader','operator'):
            e.tokens[role]='runtime-account-'+role
            self.assertEqual('runtime-account-'+role,e.actor_token(role))
            self.assertEqual(e.static[role],e.actor_token('static/'+role))
            self.assertNotEqual(e.actor_token(role),e.actor_token('static/'+role))
    def test_204_json_media_type_keeps_empty_body_and_200_empty_is_invalid(self):
        self.assertEqual((None,True),response_json('DELETE',204,{'content-type':'application/json'},b''))
        self.assertEqual((None,False),response_json('GET',200,{'content-type':'application/json'},b''))
        self.assertEqual((None,True),response_json('GET',200,{'content-type':'application/json'},b'null'))
    def test_multipart_preserves_filename_and_raw_bytes(self):
        raw,headers=multipart({'protocol':'dns'},'café.c',b'\xff\x00')
        self.assertIn('café.c'.encode(),raw);self.assertIn(b'\xff\x00\r\n',raw)
        self.assertTrue(headers['Content-Type'].startswith('multipart/form-data; boundary='))
        code=b'int plugin_main(void){return 0;}'
        self.assertEqual(MAX_BYTES,len(b' '*(MAX_BYTES-len(code))+code))
if __name__=='__main__':unittest.main()
