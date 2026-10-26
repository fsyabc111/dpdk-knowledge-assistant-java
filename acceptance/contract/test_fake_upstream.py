"""固定上游请求接收回归：JDK chunked请求必须得到真实输入，不得默认为空对象。"""
from io import BytesIO
from types import SimpleNamespace
import unittest
from acceptance.contract.fake_upstream import Handler

class BodyTests(unittest.TestCase):
    def parse(self,raw,headers):return Handler.request_body(SimpleNamespace(rfile=BytesIO(raw),headers=headers))
    def test_jdk_chunked_json_crosses_utf8_boundaries(self):
        payload='{"input":["DNS域名"],"model":"synthetic"}'.encode()
        pieces=[payload[:14],payload[14:16],payload[16:]]
        wire=b''.join(('%x'%len(p)).encode()+b'\r\n'+p+b'\r\n' for p in pieces)+b'0\r\n\r\n'
        self.assertEqual(payload,self.parse(wire,{'Transfer-Encoding':'chunked'}))
    def test_content_length_and_chunk_extension(self):
        self.assertEqual(b'{}',self.parse(b'{}',{'Content-Length':'2'}))
        self.assertEqual(b'{}',self.parse(b'2;extension=yes\r\n{}\r\n0\r\nX-Synthetic: yes\r\n\r\n',{'Transfer-Encoding':'chunked'}))
    def test_truncated_chunk_and_oversize_are_rejected(self):
        for wire in (b'4\r\n{}\r\n',b'100001\r\n'):
            with self.assertRaises(ValueError):self.parse(wire,{'Transfer-Encoding':'chunked'})
        with self.assertRaises(ValueError):self.parse(b'',{'Content-Length':'1048577'})
if __name__=='__main__':unittest.main()
