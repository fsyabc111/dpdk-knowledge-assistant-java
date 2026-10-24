# P0 规范审查最终复审（099dca2）

- reviewer：p0_review_standards；只读复审与只读 CLI／纯测试，不改实现、状态或历史报告。
- 固定提交：`099dca2e2a1b44eb6b4319ca757822f41b9aa5b0`；差异范围：`git diff c94f43d...099dca2`；Python 行为基线仍为 `80b29135faddd16d5e39fdbf0633291ea6a20489`。
- 结论：**S-01、S-02 关闭；S-03 原有具体复现已修正，但同一错误输入契约仍留 1 个 P2 边界，暂不完全关闭。** 本文不自行放行阶段。

## 原问题与验证

| 原问题 | 结果 | 本次证据 |
|---|---|---|
| S-01 跨类型身份格式误放行 | 关闭 | 实际重放 v2 `task-all-cross-type-replacement` CLI，返回 1，明确拒绝 task 格式；8 组纯比较器测试含关联一致替换反例均通过。 |
| S-02 继承模型漏枚举 | 关闭 | 现行 10 项静态测试全部通过；64 模型、ManagerAssignment 自有 2／继承 10／有效 12 字段的断言通过；v2 两个 Manager 字段删除 CLI 均返回 1。 |
| S-03 损坏输入无稳定退出码／JSON | 原复现修正，仍有具体剩余 | `state=[]`、`transaction_cases=null`、`request=[]` 及 input/params/auth 等新增容器负例实际 CLI 现均返回 2，无 stderr；非法日历、顶层数组、深度／非有限数值亦按约定失败。以下 SSE 终态字段类型仍产生未处理异常。 |

## [P2] S-03 剩余：SSE done 的状态类型须先校验

- 位置：`migration/oracle/compare.py:131–137`，尤其第 137 行；CLI 异常边界为 `migration/oracle/verify.py:38–46`。
- 复现：只读加载已通过的真实 `candidate-trace-p0-v2.json`，取第一条 SSE 观测的 done 帧，仅把 `data.answer_status` 分别改成 `{}`、`[]`。候选通过 stdin 交给真实子进程 CLI，不写候选文件。
- 实际：两例均退出 1、stdout 为空，stderr 有 traceback，最后一行为 `TypeError: unhashable type: 'dict'`／`'list'`。
- 原因：SSE schema 验证只确认 data 是 object；进入终态集合成员判断前没有确认 status 为字符串。`InvalidTrace` 与 `Difference` 捕获均无法处理此普通 TypeError。
- 期望：先校验状态字段类型，返回已文档化的输入错误 2 或明确的业务差分 1，并始终给出 JSON 结果，不输出 traceback。该类型分类应由 owner 记录；不要用无差别异常捕获吞掉程序缺陷。
- 依据：`migration/oracle/README.md:53`、`verify.py:1` 的输入／业务错误分类及稳定 JSON 输出承诺。它属于原 S-03 的剩余错误输入边界，不要求新产品行为。
- 责任卡：J0-02；建议增加两项真实 CLI 负例后再复审。

本次实际执行的最小复现：

```sh
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python - <<'PY'
import copy, json, subprocess, sys
from pathlib import Path
base = json.loads(Path('.migration-artifacts/p0/candidate-trace-p0-v2.json').read_text())
for value in [{}, []]:
    candidate = copy.deepcopy(base)
    stream = next(o for o in candidate['observations'] if o['media_type'] == 'text/event-stream')
    done = next(frame for frame in stream['body'] if frame['event'] == 'done')
    done['data']['answer_status'] = value
    result = subprocess.run([
        sys.executable, '-m', 'migration.oracle.verify', '--fixture', 'p0-v2',
        '--candidate', '/dev/stdin'], input=json.dumps(candidate, ensure_ascii=False),
        capture_output=True, text=True)
    print(type(value).__name__, result.returncode, 'stdout为空:', not result.stdout,
          '存在traceback:', 'Traceback' in result.stderr)
PY
```

## 实际执行边界

- 按提交内 `auto/p0-v2-cli-regressions.json` 的 34 个 case_id，只读现存 `.migration-artifacts/p0/v2-cli-regressions/<case_id>.json`，逐一启动真实 `python -m migration.oracle.verify --fixture p0-v2 --candidate ...`：**34/34 预期一致，0×2／1×20／2×12，均为 JSON、stderr 为空**。未运行会改写候选和原证据的 `verify_v2_regressions.main()`。
- 静态枚举 10 tests 通过；`ReviewFixTests` 中 8 组不写文件的测试通过。会创建 tempfile 的 CLI 测试未重复运行，由上述实际既有文件 CLI 及额外 stdin 两负例直接验证。
- manifest：v1 的 5 项和 v2 的 4 项 SHA256 全部一致；v2 黄金为 `8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14`。`08158c6...099dca2` 的 v1 rev2 目录无差异。
- 已核对动态采集、有效 token 停用补验和数字阈值的最新证据入口；本 reviewer 没有重跑它们。186 观测、双库／PG、容量等运行结论属于对应 owner 的执行证据，不能算成本次重跑。
- 本次没有导入 app/fastapi、连接 PG、启动应用、运行动态 oracle／负载或调用外部上游；全部命令禁写 pycache。唯一持久写入为本文，没有 commit/push 或资源操作。
- U08 安全差异和 U09 数字门槛已接受，不重复请求决策；Java 尚未构建、P1 未授权。当前阶段报告的执行草稿由主 Agent 随后更新，不据其历史待办重复报缺失。
- 未新增独立代码异味问题。下一步由原 owner 修正状态类型边界并给出新固定提交；历史审查证据保持不变，回退仅撤销本新增报告。
