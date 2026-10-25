#!/usr/bin/env python3
"""ST-03 身份收集器：登录本轮各合成角色账号，把动态 token 写入身份文件。

前置：run_00_setup.sh（建库+身份文件）→ 应用已启动 → seed_accounts.sh（种 U1..U5）。
职责：
- 以 admin（U90000000002）登录，经批量创建接口确保 U90000000006/U90000000007 存在
  （与场景2 S2.1 同一契约：{"ids":[...],"roles":["viewer"]}，重复创建返回 skipped，幂等；
  历史轮 U7 为临时建号，本脚本把该步骤收编为可复现入口）；
- 逐一登录角色账号（合成约定口令=学号；可用 DPDK_REPLAY_ROLE_PASSWORD_<账号> 覆盖），
  把 8 个重放角色 token 写入身份文件 tokens.*；
- 绝不把口令/PG 口令写入身份文件；stdout 只打 token 前 6 位。

角色→账号映射（与 run_08_golden.py TOK 语义一致）：
  leader←U1, teacher_a←U2, teacher_b←U2(同 token), student_a←U3, student_b←U4,
  free←U5, viewer←U5(同 token), s7←U7。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
STATE_DEFAULT = SCRIPT_DIR.parent / "logs" / "replay-state.json"

ROLES = {
    "leader": "U90000000001",
    "teacher_a": "U90000000002",
    "teacher_b": "U90000000002",   # 与 teacher_a 同一 token（历史 TOK 语义）
    "student_a": "U90000000003",
    "student_b": "U90000000004",
    "free": "U90000000005",
    "viewer": "U90000000005",      # 与 free 同一 token（历史 TOK 语义）
    "s7": "U90000000007",
}


def load_state(path):
    if not Path(path).exists():
        raise SystemExit("FAIL-FAST: 身份文件不存在：%s（先跑 run_00_setup.sh）" % path)
    st = json.loads(Path(path).read_text())
    base = st.get("base_url")
    if not base:
        raise SystemExit("FAIL-FAST: 身份文件缺 base_url（run_00_setup.sh 写入）")
    return st, base


def req(base, method, path, token=None, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    r = urllib.request.Request(base + path, method=method, data=data)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data=data) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def login(base, username, password):
    st, raw = req(base, "POST", "/api/auth/login",
                  payload={"username": username, "password": password})
    if st != 200:
        raise SystemExit("FAIL-FAST: 账号 %s 登录失败 HTTP %d：%s"
                         % (username, st, raw.decode(errors="replace")[:200]))
    body = json.loads(raw)
    tok = body.get("token") or ""
    if len(tok) < 20:
        raise SystemExit("FAIL-FAST: 账号 %s 登录响应无有效 token" % username)
    return tok


def state_set(path, dotted, value):
    st = json.loads(Path(path).read_text()) if Path(path).exists() else {}
    cur = st
    keys = dotted.split(".")
    for k in keys[:-1]:
        cur = cur.setdefault(k, {})
    cur[keys[-1]] = value
    tmp = str(path) + ".tmp"
    with open(tmp, "w") as f:
        json.dump(st, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)
    os.chmod(path, 0o600)


def main():
    ap = argparse.ArgumentParser(description="收集本轮角色 token 到身份文件")
    ap.add_argument("--state", default=os.environ.get("DPDK_REPLAY_STATE", str(STATE_DEFAULT)))
    args = ap.parse_args()
    state_path = Path(args.state)
    st, base = load_state(state_path)
    print("== 身份收集：BASE %s（token 只打前 6 位）==" % base)

    def role_password(username):
        return os.environ.get("DPDK_REPLAY_ROLE_PASSWORD_" + username) or username  # 合成约定口令=学号

    admin = login(base, "U90000000002", role_password("U90000000002"))
    # 确保 U6/U7 存在（批量创建契约：重复 id → skipped，幂等；见场景2 S2.1）
    st2, raw2 = req(base, "POST", "/api/admin/users", token=admin,
                    payload={"ids": ["U90000000006", "U90000000007"], "roles": ["viewer"]})
    print("ensure U6/U7 via POST /api/admin/users -> HTTP %d %s"
          % (st2, raw2.decode(errors="replace")[:120]))

    tokens = {}
    seen = {}
    # 登录限流为冻结契约（10 次/分/IP，含全部登录尝试）：collect 连发 10 次登录会把
    # 60 秒窗口填满，后续场景立即 429。默认每次登录间隔 7 秒（DPDK_REPLAY_LOGIN_PACE=0 可关）。
    import time
    pace = float(os.environ.get("DPDK_REPLAY_LOGIN_PACE", "9"))
    first = True
    for role, username in ROLES.items():
        if username in seen:
            tokens[role] = seen[username]     # 同账号别名共用同一 token（历史语义）
            continue
        if not first and pace > 0:
            time.sleep(pace)
        first = False
        tokens[role] = login(base, username, role_password(username))
        seen[username] = tokens[role]
        print("  %-10s <- %s  token=%s…" % (role, username, tokens[role][:6]))

    for role, tok in tokens.items():
        state_set(state_path, "tokens." + role, tok)
    print("已写入身份文件 %s（tokens.%s；文件权限 600，不含口令）"
          % (state_path, "/".join(tokens.keys())))
    print("下一步：run_03_assignments.sh（建 ASG1 并写入身份文件）→ run_08_golden.py。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
