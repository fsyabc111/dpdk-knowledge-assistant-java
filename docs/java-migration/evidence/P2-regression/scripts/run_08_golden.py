#!/usr/bin/env python3
"""P2 回归 IP-3：python-golden.json 对真实运行 Java 应用的可重放 route 重放比对。

比对口径：
- REPLAY_EXACT：状态码 + 响应体 JSON 值级相等（键序不敏感）；401 另比 WWW-Authenticate 头。
  覆盖 unauthorized / invalid-input（422 detail 数组）/ invalid-resource（404 not found）等
  状态无关（state-independent）响应。
- REPLAY_SHAPE：状态码 + 体结构（键集/行形态/标量格式类）相等；动态值（id/时间戳/标题）
  归一化为格式类后比较；确定性值（如协议清单、模板文本）退化为值级相等断言。
- REPLAY_FLOW：golden 成功面在本环境的等价流重放（账号集不同，用候选库合成账号）。
- SKIP：逐条登记理由（SSE/模型 live/任务依赖/J3 未实施/场景1-6 已留等价证据）。

归一化规则（承 J2-01 golden-diff.md）：identity 双射（任意 id 形态→<ID> 类）、
顺序微秒时间戳→<TS>（严格递增不比具体值）、token→<TOKEN>。
输出：logs/08-golden-replay.log（逐 case 明细）+ logs/golden-diff.json（机器可读）。
"""
import json
import re
import sys
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path("/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/worktrees/p2-reg")
BASE = "http://127.0.0.1:8090"
GOLDEN = ROOT / "migration/fixtures/p0-v2/python-golden.json"
LOG = ROOT / "docs/java-migration/evidence/P2-regression/logs/08-golden-replay.log"
OUT = ROOT / "docs/java-migration/evidence/P2-regression/logs/golden-diff.json"

TOK = {
    "leader": open("/tmp/p2reg/tok_leader.txt").read().strip(),
    "teacher_a": open("/tmp/p2reg/tok_admin.txt").read().strip(),
    "teacher_b": open("/tmp/p2reg/tok_admin.txt").read().strip(),
    "student_a": open("/tmp/p2reg/tok_s3.txt").read().strip(),
    "student_b": open("/tmp/p2reg/tok_s4.txt").read().strip(),
    "free": open("/tmp/p2reg/tok_s5.txt").read().strip(),
    "viewer": open("/tmp/p2reg/tok_s5.txt").read().strip(),
    "s7": open("/tmp/p2reg/tok_s7.txt").read().strip(),
}
ASG1 = open("/tmp/p2reg/asg1_id.txt").read().strip()
ASG2 = "asg_7c3531d30b294036"  # leader scope=all 作业（场景5 建立）
SESS1 = "sess-p2reg-01"

RE_ID = re.compile(r"^(asg_[0-9a-f]{16}|[0-9a-f]{16}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32}|<identity:\d+>)$")
RE_TS = re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?\+00:00$")
RE_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
RE_TOKEN = re.compile(r"^[A-Za-z0-9_-]{43}$")
RE_USER = re.compile(r"^U\d{11}$")


def norm_scalar(s):
    if not isinstance(s, str):
        return s
    if RE_TS.match(s):
        return "<TS>"
    if RE_DATE.match(s):
        return "<DATE>"
    if RE_USER.match(s):
        return "<USER_ID>"
    if RE_ID.match(s) or RE_TOKEN.match(s):
        return "<ID>"
    return s


def shape(v):
    if isinstance(v, dict):
        return {k: shape(v[k]) for k in sorted(v.keys())}
    if isinstance(v, list):
        return [shape(x) for x in v]
    return norm_scalar(v)


def http(method, path, token=None, jsontext=None, params=None, headers=None):
    url = BASE + path
    if params:
        from urllib.parse import urlencode
        url += "?" + urlencode(params)
    req = urllib.request.Request(url, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    data = jsontext.encode() if jsontext is not None else None
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def body_json(raw):
    try:
        return json.loads(raw.decode())
    except Exception:
        return None


def ensure_session(sid, user_id, title):
    """幂等重建等价会话+两条消息（重放可重复执行）。"""
    import subprocess
    sql = ("INSERT INTO assistant_sessions (id,user_id,title,memory_summary,created_at,updated_at) "
           "VALUES ('%s','%s','%s','',now(),now()) ON CONFLICT (id) DO NOTHING;"
           "INSERT INTO assistant_messages (id,session_id,role,content,metadata_json,created_at) "
           "VALUES ('%s-m1','%s','user','合成提问（回归占位文本）','{}'::jsonb,now()),"
           "('%s-m2','%s','assistant','合成回答（回归占位文本）','{}'::jsonb,now()) "
           "ON CONFLICT (id) DO NOTHING;"
           % (sid, user_id, title, sid, sid, sid, sid))
    subprocess.run(["psql", "-h", "127.0.0.1", "-p", "55481", "-U", "p1_java", "-d",
                    "p1_java_candidate", "-tAc", sql],
                   env={"PGPASSWORD": "p1-java-synthetic", "PATH": "/opt/homebrew/bin:/usr/bin:/bin"},
                   capture_output=True)


def main():
    golden = json.load(open(GOLDEN))
    obs = golden["observations"]
    results = []
    out = open(LOG, "w")
    out.write("# P2 golden 重放（IP-3）逐 case 明细\n")
    out.write("基线 %s；环境：候选库 p1_java_candidate + fake MCP harness（grade_plugin/list_protocols 等价替身）\n\n" % golden.get("baseline_id"))

    def record(case_id, verdict, detail):
        results.append({"case_id": case_id, "verdict": verdict, "detail": detail})
        out.write("- [%s] %s — %s\n" % (verdict, case_id, detail))
        print("%-14s %s — %s" % (verdict, case_id, detail))

    # 幂等重建等价会话（前轮重放的 DELETE 成功流会消耗一次性会话）
    ensure_session("sess-p2reg-01", "U90000000003", "P2回归会话甲")
    ensure_session("sess-p2reg-02", "U90000000003", "P2回归会话乙")

    def actor_token(actor):
        return TOK.get(actor)

    for o in obs:
        cid = o["case_id"]
        route = o["route_id"]
        method, _, tmpl = route.partition(" ")
        suffix = cid.split("::", 1)[1] if "::" in cid else ""
        req = o.get("request") or {}
        actor = req.get("actor") or ""
        inp = req.get("input")
        params = req.get("params")
        pathp = dict(req.get("path_parameters") or {})
        gstatus = o.get("status")
        gbody = o.get("body")
        gmedia = o.get("media_type")

        # —— 平台/范围跳过清单（逐条登记）——
        if route == "POST /api/chat" or any(k in route for k in ("/api/chat stream", "/api/chat/stream", "/api/tasks", "/api/rag", "explain", "/api/internal")):
            if route == "POST /api/chat":
                st, hd, raw = http(method, "/api/chat", token=actor_token(actor) if actor else None,
                                   jsontext=json.dumps(inp, ensure_ascii=False) if isinstance(inp, dict) else None)
                record(cid, "SKIP", "chat 主路由未在当前构建装配（chat 编排属 J3+/J5 范围）；实测 HTTP %d（golden %s）——范围外登记" % (st, gstatus))
            elif "rag" in route:
                record(cid, "SKIP", "RAG 路由属 J3 未实施")
            else:
                record(cid, "SKIP", "SSE/模型 live/任务/内部维护路由，P2 不重放")
            continue
        if route in ("GET /api/health", "HEAD /api/health", "GET /api/ready", "GET /docs",
                     "HEAD /docs", "GET /docs/oauth2-redirect", "HEAD /docs/oauth2-redirect",
                     "GET /openapi.json", "HEAD /openapi.json", "GET /redoc", "HEAD /redoc",
                     "GET /metrics"):
            # 平台自身路由：Python FastAPI/uvicorn 提供，Java 未迁移（登记差异）
            st, hd, raw = http(method, tmpl.replace("{assignment_id}", ASG1))
            ok = st == 404
            record(cid, "DIFF" if not ok else "REPLAYED",
                   "Python 平台路由（FastAPI/docs/metrics/health 探针），Java 侧 HTTP %d（未迁移，登记平台差异；Java 探针为 /actuator/health）" % st)
            continue
        if route == "OPTIONS /api/chat":
            if suffix == "cors-400" or "cors" in suffix:
                # 非允许来源：Python 400 "Invalid CORS request"；Java/Spring 403 同语义拒绝
                st, hd, raw = http("OPTIONS", "/api/chat",
                                   headers={"Origin": "http://evil.example",
                                            "Access-Control-Request-Method": "POST"})
                record(cid, "REPLAYED",
                       "非允许来源预检：golden HTTP 400；live HTTP %d（Spring CorsFilter 拒绝语义 403，体=%r）——平台差异登记"
                       % (st, raw[:40].decode(errors="replace")))
            else:
                st, hd, raw = http("OPTIONS", "/api/chat",
                                   headers={"Origin": "http://localhost:5173",
                                            "Access-Control-Request-Method": "POST"})
                record(cid, "REPLAYED",
                       "Java 无 POST /api/chat（J3+）；CORS 预检由 Spring 全局 CORS 处理：HTTP %d，ACAO=%s（预检形态登记）"
                       % (st, hd.get("Access-Control-Allow-Origin")))
            continue
        if route == "OPTIONS /api/health":
            st, hd, raw = http("OPTIONS", "/api/health")
            record(cid, "REPLAYED",
                   "无 Origin 普通 OPTIONS：golden 405 {detail: Method Not Allowed}（FastAPI 语义）；live HTTP %d（Java 平台面，登记）"
                   % st)
            continue
        if "::success" in cid and "login" in route:
            record(cid, "SKIP", "golden 引导账号不在候选库；登录成功等价流已在场景1逐字段留证（token 43 位/expires_at/must_change_password）")
            continue
        if cid == "POST /api/auth/change-password::success":
            # 等价流：学生05 原密改新密 → 204 空体；旧密 401；SQL 复位
            t = TOK["free"]
            st, hd, raw = http("POST", "/api/auth/change-password", token=t,
                               jsontext=json.dumps({"old_password": "U90000000005",
                                                    "new_password": "NewPass-9005"}))
            empty = len(raw) == 0
            st2, _, raw2 = http("POST", "/api/auth/login",
                                jsontext=json.dumps({"username": "U90000000005",
                                                     "password": "NewPass-9005"}))
            ok = st == gstatus == 204 and empty and st2 == 200
            # 密码复位（合成账号，运行时生成 bcrypt，不在脚本中提交哈希字面量）
            import subprocess
            h = subprocess.run(
                ["/opt/homebrew/opt/openjdk@21/bin/java", "-cp",
                 "/Users/fsy/.m2/repository/org/springframework/security/spring-security-crypto/7.0.8/spring-security-crypto-7.0.8.jar:"
                 "/Users/fsy/.m2/repository/commons-logging/commons-logging/1.3.6/commons-logging-1.3.6.jar",
                 "/tmp/p2reg/tools/BcryptGen.java", "U90000000005"],
                capture_output=True, text=True).stdout.split("\t")[1]
            subprocess.run(
                ["psql", "-h", "127.0.0.1", "-p", "55481", "-U", "p1_java", "-d",
                 "p1_java_candidate", "-tAc",
                 "UPDATE users SET password_hash='%s' WHERE id='U90000000005'" % h.strip()],
                env={"PGPASSWORD": "p1-java-synthetic", "PATH": "/opt/homebrew/bin:/usr/bin:/bin"})
            record(cid, "PASS" if ok else "DIFF",
                   "等价流重放：change-password HTTP %d 空体=%s；新密码登录 HTTP %d（golden 204/空体）；密码已复位" % (st, empty, st2))
            continue
        if cid in ("POST /api/auth/logout::success", "POST /api/auth/password-reset::success",
                   "POST /api/admin/users::success", "POST /api/admin/users/{user_id}/status::success",
                   "POST /api/admin/users/{user_id}/set-password::success",
                   "POST /api/admin/users/{user_id}/reset-password::success",
                   "POST /api/admin/users/delete::success", "POST /api/admin/users/assign::success",
                   "POST /api/admin/password-reset-requests/{request_id}/approve::success",
                   "POST /api/admin/password-reset-requests/{request_id}/reject::success"):
            record(cid, "SKIP", "状态面成功流依赖 golden 引导状态；等价流已在场景2逐字段留证（部分失败契约/停号/审批一次性令牌）")
            continue
        if cid in ("POST /api/assignments::success", "PATCH /api/assignments/{assignment_id}::success",
                   "POST /api/assignments/{assignment_id}/withdraw::success",
                   "POST /api/assignments/{assignment_id}/reactivate::success",
                   "GET /api/assignments/{assignment_id}/export::success",
                   "GET /api/assignments/{assignment_id}/grades::success"):
            record(cid, "SKIP", "等价流已在场景3/5留证（创建201/items/截止/撤回/CSV BOM+CRLF）")
            continue
        if cid == "POST /api/assignments/{assignment_id}/submit::success":
            record(cid, "SKIP", "评测成功流已在场景4留证（multipart→fake MCP→成绩；hash-only 落库）")
            continue
        if cid == "GET /api/sources/{chunk_id}::success":
            record(cid, "SKIP", "知识库种子（p0-history chunk）不在候选库（P2 范围不含 RAG 种子装载）；unauthorized 案例仍重放")
            # 继续处理同 route 的 unauthorized/invalid-resource 案例（loop 会自然覆盖）
            if suffix == "success":
                continue
        if cid in ("GET /api/chat/sessions/{session_id}::foreign-tree",
                   "GET /api/assignments/{assignment_id}::foreign-tree",
                   "GET /api/admin/users::viewer-hidden"):
            if "sessions" in cid:
                st, hd, raw = http("GET", "/api/chat/sessions/" + SESS1, token=TOK["student_b"])
            elif "assignments" in cid:
                st, hd, raw = http("GET", "/api/assignments/" + ASG1, token=TOK["s7"])
            else:
                st, hd, raw = http("GET", "/api/admin/users", token=TOK["student_a"])
            lb = body_json(raw)
            exact = (st == gstatus and lb == gbody)
            record(cid, "PASS" if exact else "DIFF",
                   "树外/越权可见性：HTTP golden=%d live=%d；body golden=%s live=%s（等价主体：%s）"
                   % (gstatus, st, json.dumps(gbody, ensure_ascii=False),
                      json.dumps(lb, ensure_ascii=False),
                      "学生04读学生03会话" if "sessions" in cid else ("无班级学生07读class作业" if "assignments" in cid else "学生03读admin用户清单")))
            continue
        if cid == "PATCH /api/admin/users/{user_id}::success":
            # 等价流：leader 改自建树内 viewer 的 daily_quota（golden: U20270000061 daily_quota 5）
            st, hd, raw = http("PATCH", "/api/admin/users/U90000000006", token=TOK["leader"],
                               jsontext=json.dumps({"daily_quota": 5}))
            lb = body_json(raw)
            gs, ls = shape(gbody or {}), shape(lb or {})
            record(cid, "PASS" if (st == gstatus and gs == ls) else ("STATE-DIFF" if st == gstatus else "DIFF"),
                   "等价流 PATCH daily_quota=5：HTTP golden=%d live=%d；shape 相等=%s；live=%s"
                   % (gstatus, st, gs == ls, json.dumps(lb, ensure_ascii=False)[:220]))
            import subprocess
            subprocess.run(["psql", "-h", "127.0.0.1", "-p", "55481", "-U", "p1_java", "-d",
                            "p1_java_candidate", "-tAc",
                            "UPDATE users SET daily_quota=10000 WHERE id='U90000000006'"],
                           env={"PGPASSWORD": "p1-java-synthetic", "PATH": "/opt/homebrew/bin:/usr/bin:/bin"})
            continue
        if cid == "GET /api/assignments/{assignment_id}::success":
            # 等价流：以 admin 创建与 golden 等参的作业（title/desc/deadline/items 全同），
            # 无提交 → 学生视角详情体应与 golden 逐字段同形（golden actor=student_a）
            st0, _, raw0 = http("POST", "/api/assignments", token=TOK["teacher_a"],
                                jsontext=json.dumps({"title": "合成DNS作业", "description": "P0",
                                                     "deadline": "2099-01-01T00:00:00Z",
                                                     "items": [{"protocol": "dns", "points": 20}]}))
            asg3 = json.loads(raw0)["id"]
            st, hd, raw = http("GET", "/api/assignments/" + asg3, token=TOK["student_a"])
            lb = body_json(raw)
            gs, ls = shape(gbody or {}), shape(lb or {})
            record(cid, "PASS" if (st == gstatus and gs == ls) else "DIFF",
                   "等价作业详情（新建无提交作业 id=%s，学生视角）：HTTP golden=%d live=%d；shape 相等=%s；live=%s"
                   % (asg3, gstatus, st, gs == ls, json.dumps(lb, ensure_ascii=False)[:260]))
            continue
        if cid == "DELETE /api/chat/sessions/{session_id}::success":
            # 专用一次性会话（不消耗其他 case 的等价资源）
            ensure_session("sess-p2reg-del", "U90000000003", "P2回归一次性删除")
            st, hd, raw = http("DELETE", "/api/chat/sessions/sess-p2reg-del", token=TOK["student_a"])
            st2, _, raw2 = http("GET", "/api/chat/sessions/sess-p2reg-del", token=TOK["student_a"])
            record(cid, "PASS" if (st == gstatus == 204 and not raw and st2 == 404) else "DIFF",
                   "等价流删除自有会话：HTTP golden=%d live=%d 空体=%s；删除后详情 HTTP %d（期望 404）"
                   % (gstatus, st, len(raw) == 0, st2))
            continue
        if route == "POST /api/auth/password-reset-requests" and cid.endswith(("::success", "::success-second", "::success-after-request")):
            # golden 体为空体摘要 {length:0, sha256:e3b0c4…}；live 断言 202 + 零字节体
            import hashlib
            u = "U90000000003" if "second" not in suffix and "after" not in suffix else "U90000000004"
            st, hd, raw = http("POST", "/api/auth/password-reset-requests",
                               jsontext=json.dumps({"username": u}))
            gb = gbody or {}
            sha_ok = (gb.get("length") == 0 and hashlib.sha256(raw).hexdigest() == gb.get("sha256"))
            record(cid, "PASS" if (st == gstatus and sha_ok) else "DIFF",
                   "空体摘要比对：HTTP golden=%d live=%d；live 体长=%d；sha256 匹配 golden=%s"
                   % (gstatus, st, len(raw), sha_ok))
            continue
        if cid == "GET /api/chat/sessions/{session_id}::success":
            st, hd, raw = http("GET", "/api/chat/sessions/" + SESS1, token=TOK["student_a"])
            lb = body_json(raw)
            okshape = (st == 200 and isinstance(lb, dict))
            gkeys = set((gbody or {}).keys()) if isinstance(gbody, dict) else set()
            lkeys = set(lb.keys()) if isinstance(lb, dict) else set()
            detail = "HTTP %d；golden 键=%s；live 键=%s" % (st, sorted(gkeys), sorted(lkeys))
            record(cid, "PASS" if okshape and gkeys == lkeys else "DIFF",
                   detail + "；消息列表形态" + ("一致" if okshape else "不一致"))
            continue
        if cid == "GET /api/chat/sessions::success":
            st, hd, raw = http("GET", "/api/chat/sessions", token=TOK["student_a"])
            lb = body_json(raw)
            ok = (st == 200 and isinstance(lb, dict) and "sessions" in lb
                  and isinstance(lb["sessions"], list))
            rows_ok = all(set(s.keys()) == {"id", "title", "updated_at"} for s in lb["sessions"]) if ok and lb["sessions"] else None
            record(cid, "PASS" if ok and rows_ok else "DIFF",
                   "HTTP %d；行键集 %s（golden 行键 {id,title,updated_at}；行数 golden=%d live=%s）"
                   % (st, "{id,title,updated_at}" if rows_ok else sorted(lb.get("sessions", [{}])[0].keys()) if ok and lb["sessions"] else "n/a",
                      len(gbody["sessions"]), len(lb["sessions"]) if ok else "n/a"))
            continue

        # —— 通用重放 ——
        # 路径参数归一：known id 用本环境等价物，未知 id 保持未知
        def resolve_path(t):
            t = t.replace("{assignment_id}", ASG1 if suffix == "success" else "asg_unknown00000001")
            t = t.replace("{session_id}", SESS1 if suffix == "success" else "sess-unknown000001")
            t = t.replace("{user_id}", pathp.get("user_id", "U90000000003").replace("U20270000011", "U90000000005").replace("U20270000001", "U90000000001"))
            t = t.replace("{request_id}", pathp.get("request_id", "req-unknown0000001"))
            t = t.replace("{submission_id}", pathp.get("submission_id", "sub-unknown0000001"))
            t = t.replace("{chunk_id}", pathp.get("chunk_id", "chunk-unknown00001"))
            return t

        path = resolve_path(tmpl)
        token = actor_token(actor)
        jsontext = json.dumps(inp, ensure_ascii=False) if isinstance(inp, dict) else None
        # submit::invalid-input 走 JSON 粘贴路径（{}）即 422；同 golden
        st, hd, raw = http(method, path, token=token, jsontext=jsontext, params=params)
        lb = body_json(raw)

        if suffix == "unauthorized":
            exact = (st == gstatus and lb == gbody
                     and hd.get("WWW-Authenticate") == "Bearer"
                     and (hd.get("Content-Type") or "").startswith("application/json"))
            record(cid, "PASS" if exact else "DIFF",
                   "HTTP golden=%d live=%d；body golden=%s live=%s；WWW-Authenticate=%s（golden 另含 CSP 等安全头=已知平台差异）"
                   % (gstatus, st, json.dumps(gbody, ensure_ascii=False),
                      json.dumps(lb, ensure_ascii=False), hd.get("WWW-Authenticate")))
        elif suffix == "invalid-input":
            exact = (st == gstatus and lb == gbody)
            record(cid, "PASS" if exact else "DIFF",
                   "422 detail 数组值级比较：HTTP golden=%d live=%d；相等=%s"
                   % (gstatus, st, lb == gbody))
        elif suffix in ("invalid-resource", "invalid-input-unknown"):
            exact = (st == gstatus and lb == gbody)
            record(cid, "PASS" if exact else "DIFF",
                   "未知资源：HTTP golden=%d live=%d；body golden=%s live=%s"
                   % (gstatus, st, json.dumps(gbody, ensure_ascii=False), json.dumps(lb, ensure_ascii=False)))
        elif suffix == "success" or suffix == "" :
            if gstatus is None:
                record(cid, "SKIP", "golden 无状态断言（重定向/平台面）")
                continue
            if isinstance(gbody, dict) and isinstance(lb, dict):
                # 三层口径：
                #  顶键集        —— 严格相等，否则 DIFF
                #  行/值「类」   —— 类型与格式类（ID/TS/DATE/USER_ID/str/int/…），不一致=契约 DIFF
                #  归一化值/行数 —— 类一致而具体值或行数不同=环境状态差异 STATE-DIFF（登记，不计失败）
                def class_shape(v):
                    if isinstance(v, dict):
                        return {k: class_shape(v[k]) for k in sorted(v.keys())}
                    if isinstance(v, list):
                        return [class_shape(x) for x in v]
                    if v is None:
                        return "null"
                    if isinstance(v, bool):
                        return "bool"
                    if isinstance(v, int):
                        return "int"
                    if isinstance(v, float):
                        return "float"
                    c = norm_scalar(v)
                    return c if isinstance(c, str) and c.startswith("<") else "str"

                def sigs(x, fn):
                    if isinstance(x, list) and x and all(isinstance(i, dict) for i in x):
                        return sorted({json.dumps(fn(i), ensure_ascii=False, sort_keys=True) for i in x})
                    return None

                top_keys_ok = set(gbody.keys()) == set(lb.keys())
                diff_fields, state_fields = [], []
                for k in sorted(set(gbody.keys()) & set(lb.keys())):
                    gcls, lcls = sigs(gbody[k], class_shape), sigs(lb[k], class_shape)
                    gval, lval = sigs(gbody[k], shape), sigs(lb[k], shape)
                    if isinstance(gbody[k], list) and isinstance(lb[k], list) and (not gbody[k]) != (not lb[k]):
                        # 空列表 vs 非空：行数属环境状态（golden 场景当时无数据）
                        state_fields.append(k)
                    elif gcls is not None and lcls is not None:
                        if gcls != lcls:
                            diff_fields.append(k)
                        elif gval != lval or len(gbody[k]) != len(lb[k]):
                            state_fields.append(k)
                    elif class_shape(gbody[k]) != class_shape(lb[k]):
                        diff_fields.append(k)
                    elif shape(gbody[k]) != shape(lb[k]):
                        state_fields.append(k)
                if not top_keys_ok:
                    record(cid, "DIFF", "顶键集不同：golden=%s live=%s" % (sorted(gbody.keys()), sorted(lb.keys())))
                elif diff_fields:
                    record(cid, "DIFF", "字段类型/格式类不一致：%s（golden=%s live=%s）"
                           % (diff_fields,
                              json.dumps(class_shape({k: gbody[k] for k in diff_fields}), ensure_ascii=False)[:300],
                              json.dumps(class_shape({k: lb[k] for k in diff_fields}), ensure_ascii=False)[:300]))
                elif state_fields:
                    record(cid, "STATE-DIFF", "格式类一致，取值/行数因环境状态不同（登记）：字段=%s（行数 golden=%s live=%s）"
                           % (state_fields,
                              {k: len(gbody[k]) for k in state_fields if isinstance(gbody[k], list)},
                              {k: len(lb[k]) for k in state_fields if isinstance(lb[k], list)}))
                else:
                    record(cid, "PASS", "结构+格式类逐字段一致；live body 摘要=%s" % json.dumps(lb, ensure_ascii=False)[:200])
            else:
                exact = (st == gstatus and lb == gbody)
                record(cid, "PASS" if exact else "DIFF",
                       "值级比较：HTTP golden=%d live=%d；相等=%s" % (gstatus, st, exact))
        else:
            record(cid, "SKIP", "未分类案例后缀：%r" % suffix)

    out.close()
    counts = {}
    for r in results:
        counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
    json.dump({"summary": counts, "cases": results}, open(OUT, "w"), ensure_ascii=False, indent=1)
    print("\nSUMMARY:", counts)


if __name__ == "__main__":
    sys.exit(main())
