"""使用冻结 pydantic 2.10.4 从 Python ChatRequest 生成输入边界；无网络。"""
import json,pathlib,sys
root=pathlib.Path(__file__).resolve().parents[6]
sys.path.insert(0,str(root/'backend'))
from app.schemas.chat import ChatRequest
from pydantic import ValidationError
cases=[{}, {'message':''},{'message':None},{'message':3},{'message':'x','session_id':3},
       {'message':'x','context':[]},{'message':'x','context':None},{'message':'x','request_id':3},
       {'message':'x','request_id':'bad'},{'message':'x','request_id':'z'*32},
       {'message':'x','request_id':'12345678-1234-1234-1234-123456789ab'},
       {'message':'x','request_id':'{12345678-1234-1234-1234-123456789abc}'},
       {'message':'𠀀'*12001}, {'message':'  空白保留  ','context':{'lesson':1}},
       {'message':'x','request_id':'12345678123412341234123456789abc'},
       {'message':'x','request_id':'urn:uuid:12345678-1234-1234-1234-123456789abc'},
       {'session_id':1,'request_id':2,'message':None,'context':False}]
out=[]
for i,b in enumerate(cases):
    try:
        req=ChatRequest(**b)
        result={'status':200,'input':json.loads(req.model_dump_json())}
    except ValidationError as e:
        result={'status':422,'detail':e.errors(include_url=False,include_context=False)}
    out.append({'id':f'chat-input-{i+1:02d}','body':b,'expected':result})
pathlib.Path(__file__).with_name('request-golden.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
