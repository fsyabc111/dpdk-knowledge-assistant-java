# P0 规范审查复审

- reviewer：p0_review_standards；仅复审原 3 项问题与相关修复。
- 固定修复提交：`c94f43d42838699e60d961d6c6f4fef4f4e066a1`。
- 对比：`git diff 08158c6...c94f43d`；初始 Python 行为基线仍为 `80b29135faddd16d5e39fdbf0633291ea6a20489`。
- 已读：J0-01、J0-02 的 `review-fixes.md`，相关枚举器、比较器、CLI、测试及契约说明。原报告保持不变。
- 结论：**S-01、S-02 关闭；S-03 部分修复、仍未关闭（P2）**。没有另提代码异味；本复审不放行 P0/P1。

## 原问题处置

| 原问题 | 复审结果 | 实际依据 |
|---|---|---|
| S-01 身份格式被全局格式并集掩盖 | 关闭 | `compare.py:25–43,181–194` 改为实体格式校验及跨实体类型关联检查。原完整 184 观测反例现于 `$.observations[56].body.task_id` 被拒绝，理由为 `task身份格式不符`；原 v1 raw 候选仍通过。 |
| S-02 继承模型漏枚举 | 关闭 | `inventory.py:135–274` 静态解析继承闭包、导入绑定和本地 C3。现为 64 模型；ManagerAssignment 自有 2 字段、继承 10 字段、有效 12 字段，列表响应的引用与 J2-03 owner/Java 去向均存在。原 63 模型的 id、bases、自有 fields 保持一致。 |
| S-03 输入损坏退出语义 | 部分修复，未关闭 | 顶层数组与非法日历已转 `InvalidTrace`、返回 2；合法业务差分仍返回 1。嵌套 state/request/transaction_cases 容器损坏仍传播普通异常，详见下项。 |

## [P2] S-03 仍须校验嵌套容器，避免输入损坏走未处理异常

- 位置：`migration/oracle/compare.py:74`、`:169`、`:240`；CLI 捕获边界为 `migration/oracle/verify.py:38–46`。
- 充分证据：从 `migration.oracle.test_review_fixes.trace()` 的有效 p0-v2 合成 trace 分别只作以下变更，使用真实 `verify.main()` 和内存文件系统读取，得到：

| 唯一变更 | 实际结果 | 按公开契约的期望 |
|---|---|---|
| `candidate['state'] = []` | `AttributeError: 'list' object has no attribute 'get'`，schema 第 74 行 | 返回 2 与稳定 JSON 错误结果 |
| `candidate['transaction_cases'] = None` | `TypeError: Value after * must be an iterable, not NoneType`，业务链第 240 行 | 返回 2 与稳定 JSON 错误结果 |
| `candidate['observations'][0]['request'] = []` | `AttributeError: 'list' object has no attribute 'get'`，归一化第 169 行 | 返回 2 与稳定 JSON 错误结果 |

- 影响：合法 JSON 的嵌套结构损坏仍使 CLI 产生 traceback/异常退出 1，消费者无法按约定区分输入损坏与业务差分。这是原 S-03 的剩余边界，不另开规格范围。
- 规范：`migration/oracle/README.md:53` 和 `verify.py:1` 承诺 manifest/JSON/schema/资源上限/非法日历错误返回 2；`compare.py:15–16` 将容器结构损坏归为 `InvalidTrace`。
- 修正建议：在使用 `.get`、成员访问或展开之前，显式校验 `state`、`request`、`request.input`、`transaction_cases` 等后续实际消费的容器与成员类型；按适用版本处理必填／可缺省差异。增加这些 CLI 负例，保持业务差分返回 1，不以无差别捕获掩盖程序错误。
- 责任卡：J0-02。

最小复现（纯内存，无文件写入）：

```sh
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python - <<'PY'
import contextlib, copy, hashlib, io, json, sys
from pathlib import Path
from unittest.mock import patch
from migration.oracle import verify
from migration.oracle.compare import normalize
from migration.oracle.test_review_fixes import trace

root = Path('/p0-standards-memory-root')
folder = root / 'migration/fixtures/p0-v2'
target = root / 'candidate.json'
original = trace()
files = {str(folder/'python-golden.json'): json.dumps(normalize(original))}
files[str(folder/'manifest.json')] = json.dumps({
    'fixture_id': 'p0-v2',
    'files': {'python-golden.json': hashlib.sha256(files[str(folder/'python-golden.json')].encode()).hexdigest()}})

def invoke(value):
    files[str(target)] = json.dumps(value)
    output = io.StringIO()
    try:
        with patch.object(verify, 'ROOT', root), \
             patch.object(Path, 'read_text', lambda p, *a, **k: files[str(p)]), \
             patch.object(Path, 'read_bytes', lambda p: files[str(p)].encode()), \
             patch.object(sys, 'argv', ['verify', '--fixture', 'p0-v2', '--candidate', str(target)]), \
             contextlib.redirect_stdout(output):
            code = verify.main()
        return code, json.loads(output.getvalue())
    except Exception as exc:
        return type(exc).__name__, str(exc)

for name, mutate in [
    ('state-array', lambda value: value.update(state=[])),
    ('transaction-cases-null', lambda value: value.update(transaction_cases=None)),
    ('request-array', lambda value: value['observations'][0].update(request=[])),
]:
    value = copy.deepcopy(original)
    mutate(value)
    print(name, invoke(value))
assert 'app' not in sys.modules and 'fastapi' not in sys.modules
PY
```

## 本次实际验证与边界

- 设置 `PYTHONDONTWRITEBYTECODE=1`，运行现行静态枚举 10 tests，全部通过。
- 运行 `ReviewFixTests` 中 6 组不写文件的比较器测试，全部通过。其 tempfile CLI 测试未由本 reviewer 重跑；使用上述内存文件系统独立补验真实 CLI，7 个样例的结果分别为：有效候选 0、顶层数组 2、非法日历 2、业务差异 1、三项嵌套损坏传播异常。主 Agent 已另行运行的完整 7 组结果不代替本次补充负例。
- 从已授权合成运行产物只读加载旧 v1 raw 候选：184 观测仍通过；将某个 task_id 及全部引用改为 `sess_aaaaaaaaaaaa` 后被 `Difference` 拒绝，没有打印令牌或 trace 内容。
- 核对 `08158c6...c94f43d` 下 `migration/fixtures/p0-v1` 无差异，manifest 的 5 个文件 SHA256 均一致。v2 动态采集仍待长测结束，本报告不把新增白名单 SQL、v2 黄金或补验视为已动态通过。
- 没有导入 app/fastapi、连接 PG、启动应用、运行 oracle/负载或调用上游；没有修改黄金、实现、状态、原审查或其他 owner 文件。
- Java 技术栈选择与 COMP003 同步属于文档／决策，本报告不宣称 Java 已构建或其安全目标已执行。
- 唯一写入本复审文件；无提交／推送／资源操作。回退仅移除本新增报告。下一步由原 owner 补齐 S-03 嵌套结构验证，再以新固定提交复审；v2 动态证据继续等待正式测量结束。
