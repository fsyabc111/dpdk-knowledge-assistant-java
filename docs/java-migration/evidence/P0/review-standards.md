# P0 独立规范审查

- reviewer：p0_review_standards；仅审查与记录，不修改实现。
- 实现构建：`08158c6`；固定点：`80b29135faddd16d5e39fdbf0633291ea6a20489`；契约与 fixture：`p0-v1`，黄金冻结修订 2。
- 范围：`git diff 80b29135faddd16d5e39fdbf0633291ea6a20489...08158c6`，提交列表仅 `08158c6 冻结 P0 初版契约与合成差分 oracle，提交独立审查`。
- 结论：**失败，3 项 P2 待原 owner 修复**。实现、自动验证、场景验收、用户验收继续分开；本报告不放行阶段。
- 规范来源：根 `AGENTS.md`、`docs/java-migration/COORDINATION.md`、`templates/HANDOFF.md`、`templates/REVIEW.md`、`migration/contracts/README.md`；按需核对 J0-01/J0-02 任务卡与被其引用的身份契约。

## 实际检查

工作目录均为仓库根目录。纯内存 Python 使用 `.migration-artifacts/p0/venv/bin/python`，设置 `PYTHONDONTWRITEBYTECODE=1`。没有导入 `app` 或 `fastapi`，没有连接 PG、启动应用、运行动态 oracle、运行负载或调用上游。

| 验收项 | 实际方法 | 结果与证据 |
|---|---|---|
| 固定点与源码范围 | `git rev-parse`、`git log`、固定区间 `git diff --name-status/--check` | 固定点有效，diff 非空；检查通过；`backend/`、`frontend/` 无变更 |
| 新增源文件可解析 | 从 `git show 08158c6:<path>` 读取后 `ast.parse/json.loads` | 17 个 Python、33 个 JSON 均通过 |
| fixture 完整性 | 逐项计算提交内文件 SHA256，与提交内 manifest 比较 | 5 项全部一致 |
| 现有静态枚举护栏 | `unittest.defaultTestLoader.discover('migration/baseline', pattern='test_*.py')` | 5 项通过；没有调用会写 coverage-report 的 inventory CLI |
| 现有比较器自检 | 调用 `selfcheck.run()`，以 `patch.object(Path, 'write_text', ...)` 将证据写入重定向到内存 | 3 正例、19 反例、7 危险 URL 拒绝均通过；未改原证据 |
| 黄金可消费性 | 将 `<identity:n>` 统一物化为 32 位 hex，`<duration>` 物化为 0，再运行 `compare` 与内存候选的 `verify.main()` | 184 观测通过，CLI 返回 0 |
| 补充负例 | 下列三项纯内存复现 | 均复现；现有自检未覆盖这些边界 |

## 硬规范／公开工具契约问题

### S-01 [P2] 按字段保留生成身份的格式约束

- 位置：`migration/oracle/compare.py:101`（身份规则与归一化范围见第 83–106 行）。
- 现状：所有可归一化身份共用一个正则并集，32 位 hex、带连字符 UUID、`sess_`、`msg_`、`asg_`、`grade_` 等格式可以互换。只要跨引用统一替换，比较器即接受。
- 实证：最小 `body.task_id='a'*32` 与候选 `body.task_id='sess_aaaaaaaaaaaa'` 比较通过；对完整 184 观测的物化 fixture，将某个 task_id 及其全部引用统一替换成 `sess_aaaaaaaaaaaa`，仍通过。
- 影响：后续 Java 候选可违反 task_id wire 格式而取得绿色差分；引用双射正确不足以证明身份格式兼容。
- 规范：`AGENTS.md:21` 要求身份按冻结契约；`migration/contracts/identity-and-data.md:12` 明确 `task_id` 为独立 `uuid4().hex`；`migration/oracle/README.md:36` 承诺仅对“符合生成格式”的 ID 归一化。
- 修正：先按字段／实体契约校验格式，再进行双射归一化。保留每种合法来源的身份类型与生成格式；不能通过扩展全局格式并集消除失败。增加跨字段格式置换反例与完整 fixture 关联替换反例。
- 责任卡：J0-02。

### S-02 [P2] 枚举 Pydantic 模型时纳入传递继承

- 位置：`migration/baseline/inventory.py:143`（第 143–146 行）。
- 现状：仅收集直接以 `BaseModel` 为基类的 class，排除已有 `ManagerAssignment(AssignmentInfo)`。
- 实证：`backend/app/schemas/assignments.py:58` 定义该子类，其新增字段为 `submission_count`、`student_count`；第 63–64 行的 `ManagerAssignmentListResponse` 引用它，`backend/app/api/assignments.py:183` 实际构造并返回它。对该文件执行 `schemas_inventory`，父类与列表响应均在清单中，`ManagerAssignment` 不在。提交内 static-inventory 与 coverage-map 的 models 集合均漏掉该主键，而现有 5 项测试仍通过。
- 影响：宣称完整的 63 模型集合实际漏项，消费者无法按 `bases` 找到该子类的字段和 Java DTO 去向；作业管理列表模型成为悬空类型引用。
- 规范：`migration/contracts/README.md:10–11` 要求模型有 owner/Java 去向，子类经 `bases` 继承；`identity-and-data.md:3` 把该模型清单声明为完整字段来源；`foundation-slices.md:12,19` 要求关键 JSON 字段契约明确。
- 修正：静态建立直接／传递继承闭包，保留子类自身字段与 bases，重新枚举、映射并校验所有类型引用。应至少纳入此第 64 个 Pydantic 模型，不能只按名字补一个特例；增加间接继承的合成 AST 回归。
- 责任卡：J0-01。

### S-03 [P2] 输入损坏统一返回约定的退出码 2

- 位置：`migration/oracle/verify.py:22–24`；触发点另见 `compare.py:136,149`。
- 现状：读取 JSON 后不检查顶层／观测结构；`compare` 阶段只捕获 `Difference`。候选为合法 JSON `[]` 时抛出 `AttributeError: 'list' object has no attribute 'get'`；候选已有 `expires_at` 改为 `2026-99-24T04:00:00+00:00` 时抛出 `ValueError: month must be in 1..12`。
- 实证：以内存模拟候选路径并调用真实 `verify.main()`，两者均向调用者传播异常，而非返回 2；作为 CLI 运行会得到未处理异常退出 1 和 traceback。
- 影响：自动消费者无法按公开约定区分“输入损坏”与“业务差分”，也得不到约定 JSON 错误结果。
- 规范：`migration/oracle/verify.py:1`、`migration/oracle/README.md:36` 明确“差分退出 1、清单／输入损坏退出 2”；属于本次交付的 CLI 公开契约。
- 修正：在比较前验证 trace/observations/SSE 的结构；把无效日历、结构和 manifest 错误转为明确输入错误。仅真实的业务不一致返回 1，避免用无差别捕获掩盖程序缺陷。补充 CLI 返回码与输出 JSON 断言。
- 责任卡：J0-02。

## 可复现命令（无 PG／外部调用／文件写入）

```sh
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python - <<'PY'
import ast, copy, json, re, sys
from pathlib import Path
from unittest.mock import patch
from migration.baseline.inventory import schemas_inventory
from migration.oracle.compare import compare
from migration.oracle import verify

source = Path('backend/app/schemas/assignments.py')
models = schemas_inventory({source: ast.parse(source.read_text())})
print('ManagerAssignment 被枚举:', any(m['name'] == 'ManagerAssignment' for m in models))

golden = json.loads(Path('migration/fixtures/p0-v1/python-golden.json').read_text())
def materialize(value):
    if isinstance(value, dict): return {k: materialize(v) for k, v in value.items()}
    if isinstance(value, list): return [materialize(v) for v in value]
    if isinstance(value, str) and re.fullmatch(r'<identity:\d+>', value):
        return f'{int(value[10:-1]):032x}'
    return 0 if value == '<duration>' else value
candidate = materialize(golden)
compare(golden, candidate)
target = next(o['body']['task_id'] for o in candidate['observations']
              if isinstance(o.get('body'), dict) and o['body'].get('task_id'))
def replace_identity(value):
    if isinstance(value, dict): return {k: replace_identity(v) for k, v in value.items()}
    if isinstance(value, list): return [replace_identity(v) for v in value]
    return 'sess_aaaaaaaaaaaa' if value == target else value
compare(golden, replace_identity(candidate))
print('错误 task_id 格式仍通过:', len(candidate['observations']))

virtual = Path('/p0-standards-memory-candidate.json')
read_text = Path.read_text
def invoke(value):
    def read(path, *args, **kwargs):
        return json.dumps(value) if path == virtual else read_text(path, *args, **kwargs)
    with patch.object(Path, 'read_text', read), patch.object(sys, 'argv', ['verify', '--candidate', str(virtual)]):
        try: print('CLI 返回:', verify.main())
        except Exception as exc: print('未处理异常:', type(exc).__name__, str(exc))
invoke([])
def break_existing_time(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in {'created_at', 'updated_at', 'expires_at'} and isinstance(item, str):
                value[key] = '2026-99-24T04:00:00+00:00'
                return True
            if break_existing_time(item): return True
    elif isinstance(value, list):
        return any(break_existing_time(item) for item in value)
    return False
broken = copy.deepcopy(candidate)
assert break_existing_time(broken)
invoke(broken)
assert 'app' not in sys.modules and 'fastapi' not in sys.modules
PY
```

上述命令的实际断言摘要：遗漏模型为 false；错误身份格式的完整 184 观测仍通过；数组输入抛 AttributeError；既有日期字段损坏抛 ValueError。两项 CLI 负例均未得到返回码 2。

## 代码异味判断

没有独立提出需整改的 Fowler 异味。身份正则与继承漏项已按真实行为错误报告；未因脚本长度、少量测试辅助重复或命名偏好额外凑数。

## 限制、回退与下一步

- 本审查未执行数据库现场身份验证、DDL、应用、动态上游脚本、容量／恢复负载、有效 token 停用补验或 Java 候选验证；这些边界均未获得本报告的运行证明。
- 静态读取显示入口校验 URL／现场身份／comment／data_directory，并在应用导入前禁用 `.env`、清理 ambient 配置和注入替身。此次没有找到已证实的真实上游／旧库隔离绕过；这不等同于动态隔离验收。
- 正式三轮测量、量化阈值、最终报告状态及已安排的有效 token 停用补验属于原计划收口，不作为新缺陷。COMP003 已批准安全差异不在本报告重新决策；Python 现状不改。
- 工作区存在其他 owner 的实时文档和长测证据改动；固定提交中的源码与 fixture 为本审查对象，不覆盖或回滚这些修改。
- 本 reviewer 唯一写入本报告；无 commit/push，无进程或资源需停止。回退仅删除本新增审查文件，不操作实现、测试库或他人证据。
- 下一步：主 Agent 汇总两个独立轴的报告，交原 owner 修复这三项；纯内存回归可先做，需要 PG 的补证继续等待正式长测结束。修复后重新固定审查提交与 fixture 摘要，再判断规范门禁。
