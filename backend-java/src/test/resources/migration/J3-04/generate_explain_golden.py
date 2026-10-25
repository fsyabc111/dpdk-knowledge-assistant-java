"""从冻结 Python 的纯函数生成讲解身份/context；不导入服务、不使用 Java 输出。"""
import ast
import hashlib
import json
import pathlib
import typing
import uuid

root = pathlib.Path(__file__).resolve().parents[6]
source = root / 'backend/app/services/rag_product_service.py'
tree = ast.parse(source.read_text())
names = {'explain_request_identity', 'build_explain_context'}
selected = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names]
ns = {'uuid': uuid, 'Dict': typing.Dict, 'Any': typing.Any,
      'Optional': typing.Optional, 'Tuple': typing.Tuple}
exec(compile(ast.Module(body=selected, type_ignores=[]), str(source), 'exec'), ns)
base = {'id': 'grade_abc', 'assignment_id': 'asg1', 'protocol': 'dns',
        'score': 80, 'passed': 4, 'total': 5, 'created_at': '2026-10-25 00:00:00+00:00'}
cases = []
for i, results in enumerate([None, False, 0, '', [], {}, [{'result': 'FAIL', 'name': 'dns'}]]):
    submission = dict(base, results=results, source_origin='uploaded')
    cases.append({'id': f'results_{i}', 'submission': submission, 'assignment': {'title': '作业一'},
                  'expected': ns['build_explain_context'](submission, {'title': '作业一'})})
cases.append({'id': 'missing_optional', 'submission': base, 'assignment': None,
              'expected': ns['build_explain_context'](base, None)})
identities = [{'submission_id': sid, 'expected': ns['explain_request_identity'](sid)}
              for sid in ['grade_abc', 'grade_xyz', 'grade_中文😀']]
result = {'oracle': 'frozen Python AST pure functions',
          'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
          'identities': identities, 'contexts': cases}
pathlib.Path(__file__).with_name('explain-golden.json').write_text(
    json.dumps(result, ensure_ascii=False, indent=2) + '\n')
