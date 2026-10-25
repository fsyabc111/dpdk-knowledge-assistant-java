"""从冻结 Python 函数生成产品裁决样例；不导入原服务、不联网、不改 oracle。"""
import ast
import asyncio
import dataclasses
import json
import logging
import pathlib
import re
import types

root = pathlib.Path(__file__).resolve().parents[6]
source = root / 'backend/app/services/rag_product_service.py'
module = ast.parse(source.read_text())
names = {'Verdict', 'UNANSWERED', 'UNAVAILABLE', '_EVIDENCE_MIN_CHARS', 'bound_evidence',
         'judge_budget', '_precheck_verdict', '_postcheck_verdict', 'validate_answer'}
selected = []
for node in module.body:
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and node.name in names:
        if isinstance(node, ast.AsyncFunctionDef) and node.name == 'validate_answer':
            node.body = [line for line in node.body if not isinstance(line, ast.ImportFrom)]
        selected.append(node)
    elif isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in names for t in node.targets):
        selected.append(node)
ns = {'asyncio': asyncio, 'json': json, 're': re, 'dataclass': dataclasses.dataclass,
      'Optional': object, 'logger': logging.getLogger('golden'), 'time': __import__('time'),
      'get_settings': lambda: types.SimpleNamespace(rag_online_judge_enabled=False),
      'record_rag_validation': lambda *args: None}
# 注解仅为静态辅助，不参与冻结行为；避免导入typing和第三方运行栈。
ns['Optional'] = __import__('typing').Optional
exec(compile(ast.fix_missing_locations(ast.Module(body=selected, type_ignores=[])), str(source), 'exec'), ns)

class Judge:
    def __init__(self, raw): self.raw = raw
    async def generate(self, *args, **kwargs): return self.raw

async def main():
    chunk = {'ref': 1, 'source': 'src/dns.c', 'content': 'DNS knowledge'}
    cases = []
    shapes = [('empty', ' ', [], []), ('invalid', 'answer [2]', [chunk], []),
              ('zero', 'answer [0]', [chunk], []), ('no_evidence', 'answer', [], []),
              ('missing_citation', 'answer', [chunk], []), ('answered', 'answer [1]', [chunk], []),
              ('tool_only', 'tool answer', [], [{'tool':'list_protocols', 'result':{'ok':True}}])]
    for name, answer, chunks, tools in shapes:
        evidence = {'chunks': chunks, 'tools': tools}
        result = await ns['validate_answer']('DNS', answer, evidence, None, 2, judge_enabled=False)
        cases.append({'id':name,'question':'DNS','answer':answer,'evidence':evidence,'judge':None,'expected':dataclasses.asdict(result)})
    for status in ['answered','partial','needs_clarification','unanswered','not_applicable']:
        for supported in [True,False]:
            raw=json.dumps({'status':status,'reason':'conflicting_evidence','supported':supported})
            evidence={'chunks':[chunk],'tools':[]}
            result=await ns['validate_answer']('DNS','answer [1]',evidence,Judge(raw),2,judge_enabled=True)
            cases.append({'id':status+'_'+str(supported),'question':'DNS','answer':'answer [1]','evidence':evidence,'judge':raw,'expected':dataclasses.asdict(result)})
    for name,raw in [('malformed','not-json'),('wrong_boolean','{"status":"answered","supported":1}'),('unknown_status','{"status":"invented","supported":true}')]:
        evidence={'chunks':[chunk],'tools':[]}
        result=await ns['validate_answer']('DNS','answer [1]',evidence,Judge(raw),2,judge_enabled=True)
        cases.append({'id':name,'question':'DNS','answer':'answer [1]','evidence':evidence,'judge':raw,'expected':dataclasses.asdict(result)})
    bounded=[]
    for limit in [500,900,1600,5000]:
        evidence={'chunks':[{'ref':i+1,'source':'src/dns.c','content':('𠀀DNS协议'*800)[:1500]} for i in range(3)],'tools':[]}
        bounded.append({'limit':limit,'evidence':evidence,'expected':ns['bound_evidence'](evidence,limit)})
    return {'source':'backend/app/services/rag_product_service.py@80b29135faddd16d5e39fdbf0633291ea6a20489','validation':cases,'bound_evidence':bounded}
path=pathlib.Path(__file__).with_name('validation-golden.json')
path.write_text(json.dumps(asyncio.run(main()),ensure_ascii=False,indent=2)+'\n')
