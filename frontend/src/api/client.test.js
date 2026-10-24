import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

import {
  authProbeReady,
  getApiToken,
  getLastSessionId,
  setApiToken,
  setLastSessionId,
  getUserSubject,
  getUserRoles,
  setUserIdentity,
  buildChatFormData,
  sendChat,
  sendChatStream,
  loadChatSession,
  getMyUsage,
  getSubjectUsage,
  login,
  logout,
  changePassword,
  listSessions,
  listUsers,
  createUsers,
  resetUserPassword,
  setUserStatus,
  updateUser,
  deleteSession,
  deleteUsers,
  assignStudents,
  listAuditLog,
  getGradeSubmissions,
  getGradeSummary,
  listAssignmentProtocols,
  listAssignments,
  createAssignment,
  getAssignment,
  updateAssignment,
  setAssignmentStatus,
  submitAssignment,
  getAssignmentGrades,
  exportAssignmentGrades,
  explainSubmission,
  getTask,
  getActiveTask,
  cancelTask,
  streamTaskEvents
} from "./client.js";

// In-memory localStorage stub: client.js touches the browser global
// directly, so we replace it with a vi.fn-backed implementation.
function createLocalStorageStub() {
  let store = {};
  return {
    getItem: vi.fn((key) => (key in store ? store[key] : null)),
    setItem: vi.fn((key, value) => {
      store[key] = String(value);
    }),
    removeItem: vi.fn((key) => {
      delete store[key];
    }),
    clear: vi.fn(() => {
      store = {};
    })
  };
}

let storage;
let dispatchEvent;
let fetchMock;

function jsonResponse(body, { ok = true, status = 200, text = "" } = {}) {
  return {
    ok,
    status,
    // client.js 的错误路径会读 Retry-After 响应头，mock 提供空 Headers 语义。
    headers: { get: () => null },
    json: async () => body,
    // client.js 成功路径改用 text() 再 JSON.parse（容忍 202 空响应体）：
    // 未显式给 text 时回退为 body 的 JSON 序列化，贴近真实 fetch 行为。
    text: async () => text || (body === null || body === undefined ? "" : JSON.stringify(body))
  };
}

// Minimal ReadableStream-style body from a list of Uint8Array chunks.
function streamBody(chunks) {
  return {
    getReader() {
      let index = 0;
      return {
        read: vi.fn(async () =>
          index < chunks.length
            ? { done: false, value: chunks[index++] }
            : { done: true, value: undefined }
        )
      };
    }
  };
}

const encode = (text) => new TextEncoder().encode(text);

beforeEach(() => {
  storage = createLocalStorageStub();
  dispatchEvent = vi.fn();
  fetchMock = vi.fn();
  vi.stubGlobal("localStorage", storage);
  vi.stubGlobal("window", { dispatchEvent });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("token storage", () => {
  it("returns empty string when no token is stored", () => {
    expect(getApiToken()).toBe("");
  });

  it("stores and reads back the token", () => {
    setApiToken("secret-token");
    expect(storage.setItem).toHaveBeenCalledWith("assistant_api_token", "secret-token");
    expect(getApiToken()).toBe("secret-token");
  });

  it("clears the token when set to empty/null", () => {
    setApiToken("secret-token");
    setApiToken("");
    expect(storage.removeItem).toHaveBeenCalledWith("assistant_api_token");
    expect(getApiToken()).toBe("");
  });
});

describe("Authorization header", () => {
  it("sends Bearer token when one is stored", async () => {
    setApiToken("abc123");
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await sendChat({ question: "hi" });

    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers.Authorization).toBe("Bearer abc123");
  });

  it("omits the header when no token is stored", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await sendChat({ question: "hi" });

    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers).not.toHaveProperty("Authorization");
  });
});

describe("401 handling", () => {
  it("clears token, dispatches assistant-auth-required and throws", async () => {
    setApiToken("expired");
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 401, text: "unauthorized" }));

    await expect(sendChat({ question: "hi" })).rejects.toThrow("unauthorized");

    expect(storage.removeItem).toHaveBeenCalledWith("assistant_api_token");
    expect(getApiToken()).toBe("");
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
    expect(dispatchEvent.mock.calls[0][0]).toBeInstanceOf(Event);
    expect(dispatchEvent.mock.calls[0][0].type).toBe("assistant-auth-required");
  });
});

describe("authProbeReady (S4-d)", () => {
  it("is blocked after a 401 until a new token is set", async () => {
    setApiToken("expired");
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 401, text: "unauthorized" }));

    await expect(sendChat({ question: "hi" })).rejects.toThrow("unauthorized");
    expect(authProbeReady()).toBe(false);

    setApiToken("fresh-token");
    expect(authProbeReady()).toBe(true);
  });

  it("stays ready while requests succeed (local mode never blocks)", async () => {
    setApiToken("valid");
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await sendChat({ question: "hi" });
    expect(authProbeReady()).toBe(true);
  });
});

describe("last session id storage (S4-d)", () => {
  it("stores, reads back and clears the last session id", () => {
    expect(getLastSessionId()).toBe("");
    setLastSessionId("sess_abc");
    expect(storage.setItem).toHaveBeenCalledWith("assistant_last_session", "sess_abc");
    expect(getLastSessionId()).toBe("sess_abc");
    setLastSessionId("");
    expect(storage.removeItem).toHaveBeenCalledWith("assistant_last_session");
    expect(getLastSessionId()).toBe("");
  });
});

describe("non-401 errors", () => {
  it("throws but keeps the token and does not dispatch the event", async () => {
    setApiToken("still-valid");
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 500, text: "boom" }));

    await expect(sendChat({ question: "hi" })).rejects.toThrow("boom");

    expect(storage.removeItem).not.toHaveBeenCalled();
    expect(getApiToken()).toBe("still-valid");
    expect(dispatchEvent).not.toHaveBeenCalled();
  });
});

describe("sendChatStream SSE parsing", () => {
  it("delivers multiple frames from a single chunk", async () => {
    const payload =
      "event: token\ndata: {\"text\":\"hello\"}\n\n" +
      "event: done\ndata: {\"ok\":true}\n\n";
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamBody([encode(payload)])
    });
    const onEvent = vi.fn();

    await sendChatStream({ question: "hi" }, { onEvent });

    expect(onEvent).toHaveBeenCalledTimes(2);
    expect(onEvent).toHaveBeenNthCalledWith(1, "token", { text: "hello" });
    expect(onEvent).toHaveBeenNthCalledWith(2, "done", { ok: true });
  });

  it("reassembles a frame split across two reads", async () => {
    const full = "event: token\ndata: {\"text\":\"split\"}\n\n";
    const cut = full.indexOf("\"split");
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamBody([encode(full.slice(0, cut)), encode(full.slice(cut))])
    });
    const onEvent = vi.fn();

    await sendChatStream({ question: "hi" }, { onEvent });

    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledWith("token", { text: "split" });
  });

  it("throws on a 401 streaming response and clears the token", async () => {
    setApiToken("expired");
    fetchMock.mockResolvedValue({
      ok: false,
      status: 401,
      body: null,
      text: async () => "unauthorized"
    });

    await expect(
      sendChatStream({ question: "hi" }, { onEvent: vi.fn() })
    ).rejects.toThrow("unauthorized");

    expect(storage.removeItem).toHaveBeenCalledWith("assistant_api_token");
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
  });
});

describe("sendChatStream tool events", () => {
  it("routes tool_start/tool_end to the optional tool callbacks, not onEvent", async () => {
    const payload =
      "event: tool_start\ndata: {\"tool\":\"grade_plugin\",\"label\":\"正在评测 DNS 插件…\"}\n\n" +
      "event: delta\ndata: {\"delta\":\"部分\"}\n\n" +
      "event: tool_end\ndata: {\"tool\":\"grade_plugin\",\"ok\":true,\"summary\":\"5/8 通过，得分 62\"}\n\n";
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamBody([encode(payload)])
    });
    const onEvent = vi.fn();
    const onToolStart = vi.fn();
    const onToolEnd = vi.fn();

    await sendChatStream({ message: "hi" }, { onEvent, onToolStart, onToolEnd });

    expect(onToolStart).toHaveBeenCalledTimes(1);
    expect(onToolStart).toHaveBeenCalledWith({ tool: "grade_plugin", label: "正在评测 DNS 插件…" });
    expect(onToolEnd).toHaveBeenCalledTimes(1);
    expect(onToolEnd).toHaveBeenCalledWith({ tool: "grade_plugin", ok: true, summary: "5/8 通过，得分 62" });
    // 非工具事件仍走 onEvent；工具事件不再重复投递。
    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledWith("delta", { delta: "部分" });
  });

  it("falls back to onEvent for tool events when no tool callbacks are given", async () => {
    const payload =
      "event: tool_start\ndata: {\"tool\":\"kb_search\",\"label\":\"正在检索知识库…\"}\n\n" +
      "event: tool_end\ndata: {\"tool\":\"kb_search\",\"ok\":false,\"summary\":\"未命中\"}\n\n";
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamBody([encode(payload)])
    });
    const onEvent = vi.fn();

    await sendChatStream({ message: "hi" }, { onEvent });

    // 向后兼容：旧调用方不传工具回调时，仍能从 onEvent 收到这两类事件。
    expect(onEvent).toHaveBeenCalledTimes(2);
    expect(onEvent).toHaveBeenNthCalledWith(1, "tool_start", { tool: "kb_search", label: "正在检索知识库…" });
    expect(onEvent).toHaveBeenNthCalledWith(2, "tool_end", { tool: "kb_search", ok: false, summary: "未命中" });
  });
});

describe("multipart chat upload", () => {
  const pluginFile = () => new File(["int main(void){return 0;}"], "U20260000001_张三_DNS.c", { type: "text/x-c" });

  it("buildChatFormData appends message, session_id, JSON context and the file", () => {
    const form = buildChatFormData(
      { session_id: "sess_1", message: "帮我评测", context: { page: "assistant" } },
      pluginFile()
    );

    expect(form.get("message")).toBe("帮我评测");
    expect(form.get("session_id")).toBe("sess_1");
    expect(JSON.parse(form.get("context"))).toEqual({ page: "assistant" });
    expect(form.get("file")).toBeInstanceOf(File);
    expect(form.get("file").name).toBe("U20260000001_张三_DNS.c");
  });

  it("buildChatFormData omits optional fields when unset", () => {
    const form = buildChatFormData({ message: "" }, pluginFile());

    expect(form.get("message")).toBe("");
    expect(form.get("session_id")).toBeNull();
    expect(form.get("context")).toBeNull();
    expect(form.get("file")).toBeInstanceOf(File);
  });

  it("sendChat with a file posts FormData without a manual Content-Type", async () => {
    setApiToken("abc123");
    fetchMock.mockResolvedValue(jsonResponse({ answer: "ok" }));

    const result = await sendChat({ session_id: "sess_1", message: "hi", context: { page: "assistant" } }, pluginFile());

    expect(result).toEqual({ answer: "ok" });
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/chat");
    expect(options.method).toBe("POST");
    expect(options.body).toBeInstanceOf(FormData);
    expect(options.body.get("message")).toBe("hi");
    expect(options.body.get("file")).toBeInstanceOf(File);
    // boundary 由运行时生成，不能手动设置 Content-Type。
    expect(options.headers).not.toHaveProperty("Content-Type");
    expect(options.headers.Authorization).toBe("Bearer abc123");
  });

  it("sendChat without a file keeps the legacy JSON request", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ answer: "ok" }));

    await sendChat({ message: "hi" });

    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(JSON.parse(options.body)).toEqual({ message: "hi" });
  });

  it("sendChatStream with a file streams SSE from the multipart response", async () => {
    setApiToken("abc123");
    const payload = "event: done\ndata: {\"ok\":true}\n\n";
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamBody([encode(payload)])
    });
    const onEvent = vi.fn();

    await sendChatStream(
      { message: "", context: { page: "assistant" } },
      { onEvent },
      pluginFile()
    );

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/chat/stream");
    expect(options.body).toBeInstanceOf(FormData);
    expect(options.body.get("message")).toBe("");
    expect(options.headers).not.toHaveProperty("Content-Type");
    expect(options.headers.Authorization).toBe("Bearer abc123");
    expect(onEvent).toHaveBeenCalledWith("done", { ok: true });
  });

  it("sendChatStream with a file throws on a 401 and clears the token", async () => {
    setApiToken("expired");
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 401, text: "unauthorized" }));

    await expect(
      sendChatStream({ message: "hi" }, { onEvent: vi.fn() }, pluginFile())
    ).rejects.toThrow("unauthorized");

    expect(storage.removeItem).toHaveBeenCalledWith("assistant_api_token");
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
  });
});

describe("loadChatSession", () => {
  it("URL-encodes the session id and returns the parsed body", async () => {
    const body = { messages: [{ role: "user", content: "hi" }] };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await loadChatSession("a b/c?d");

    expect(result).toEqual(body);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8090/api/chat/sessions/a%20b%2Fc%3Fd?limit=100",
      expect.objectContaining({ headers: expect.any(Object) })
    );
  });

  it("passes a custom limit", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ messages: [] }));

    await loadChatSession("s1", 5);

    expect(fetchMock.mock.calls[0][0]).toContain("/api/chat/sessions/s1?limit=5");
  });
});

describe("usage endpoints", () => {
  it("getMyUsage requests /api/usage/me with the stored token and parses the body", async () => {
    setApiToken("abc123");
    const body = {
      subject: "alice", roles: ["viewer"], daily_quota: 50,
      today: { day: "2026-08-21", chat_count: 3 },
      recent_days: [{ day: "2026-08-21", chat_count: 3 }]
    };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await getMyUsage();

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/usage/me");
    expect(options.headers.Authorization).toBe("Bearer abc123");
  });

  it("getSubjectUsage requests /api/usage/subjects and parses the body", async () => {
    const body = {
      subjects: [
        { subject: "alice", roles: ["viewer"], daily_quota: 50, today_count: 3, total_count: 120, last_active_day: "2026-08-21" }
      ]
    };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await getSubjectUsage();

    expect(result).toEqual(body);
    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/usage/subjects");
  });

  it("a 404 on getSubjectUsage throws without touching the token", async () => {
    setApiToken("viewer-token");
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 404, text: "not found" }));

    await expect(getSubjectUsage()).rejects.toThrow("not found");

    expect(storage.removeItem).not.toHaveBeenCalled();
    expect(dispatchEvent).not.toHaveBeenCalled();
  });
});

describe("user identity storage", () => {
  it("stores and reads back subject and roles", () => {
    setUserIdentity("U20260000001", ["viewer", "admin"]);

    expect(getUserSubject()).toBe("U20260000001");
    expect(getUserRoles()).toEqual(["viewer", "admin"]);
    expect(storage.setItem).toHaveBeenCalledWith("assistant_user_subject", "U20260000001");
    expect(storage.setItem).toHaveBeenCalledWith("assistant_user_roles", "[\"viewer\",\"admin\"]");
  });

  it("clears subject and roles when set to null", () => {
    setUserIdentity("U20260000001", ["viewer"]);
    setUserIdentity(null);

    expect(getUserSubject()).toBe("");
    expect(getUserRoles()).toEqual([]);
  });

  it("returns empty roles when nothing is stored or the JSON is broken", () => {
    expect(getUserRoles()).toEqual([]);

    storage.setItem("assistant_user_roles", "{oops");
    expect(getUserRoles()).toEqual([]);
  });
});

describe("login", () => {
  it("posts credentials and returns the body without storing the token", async () => {
    const body = {
      token: "tok-1", subject: "U20260000001",
      roles: ["viewer"], expires_at: "2026-08-28T00:00:00Z"
    };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await login("U20260000001", "secret");

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/auth/login");
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ username: "U20260000001", password: "secret" });
    // Token/identity persistence is the caller's job.
    expect(storage.setItem).not.toHaveBeenCalled();
  });

  it("a 401 dispatches assistant-auth-required and throws", async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 401, text: "unauthorized" }));

    await expect(login("U20260000001", "wrong")).rejects.toThrow("unauthorized");

    expect(dispatchEvent).toHaveBeenCalledTimes(1);
  });
});

describe("logout", () => {
  it("posts to /api/auth/logout with the Bearer token", async () => {
    setApiToken("abc123");
    fetchMock.mockResolvedValue({ ok: true, status: 204 });

    await logout();

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/auth/logout");
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe("Bearer abc123");
  });

  it("never throws, even when the request fails", async () => {
    fetchMock.mockRejectedValue(new Error("network down"));
    await expect(logout()).resolves.toBeUndefined();

    fetchMock.mockResolvedValue({ ok: false, status: 500 });
    await expect(logout()).resolves.toBeUndefined();
  });
});

describe("changePassword", () => {
  it("posts old/new password with snake_case fields", async () => {
    setApiToken("abc123");
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await changePassword("old-pass", "new-pass");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/auth/change-password");
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ old_password: "old-pass", new_password: "new-pass" });
    expect(options.headers.Authorization).toBe("Bearer abc123");
  });

  it("a 400 (wrong old password) keeps the token and does not dispatch the event", async () => {
    setApiToken("still-valid");
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 400, text: "旧密码错误" }));

    await expect(changePassword("bad", "new-pass")).rejects.toThrow("旧密码错误");

    expect(getApiToken()).toBe("still-valid");
    expect(dispatchEvent).not.toHaveBeenCalled();
  });
});

describe("listSessions", () => {
  it("normalizes a {sessions: [...]} envelope to an array", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ sessions: [{ id: "s1", title: "t" }] }));

    const result = await listSessions();

    expect(result).toEqual([{ id: "s1", title: "t" }]);
    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/chat/sessions?limit=50&offset=0");
  });

  it("accepts a bare array response and passes limit/offset", async () => {
    fetchMock.mockResolvedValue(jsonResponse([{ id: "s2" }]));

    const result = await listSessions(10, 20);

    expect(result).toEqual([{ id: "s2" }]);
    expect(fetchMock.mock.calls[0][0]).toContain("limit=10&offset=20");
  });

  it("deleteSession issues a DELETE and resolves null on 204", async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, { status: 204 }));

    const result = await deleteSession("sess_abc123");

    expect(result).toBeNull();
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/chat/sessions/sess_abc123");
    expect(options.method).toBe("DELETE");
  });
});

describe("admin endpoints", () => {
  it("listUsers requests /api/admin/users with the stored token", async () => {
    setApiToken("admin-token");
    const body = [{ id: "U20260000001", roles: ["viewer"], daily_quota: null, enabled: true, created_at: "2026-08-21" }];
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await listUsers();

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/admin/users");
    expect(options.headers.Authorization).toBe("Bearer admin-token");
  });

  it("createUsers posts ids and omits unset optional fields", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ created: 1, skipped: 0 }));

    await createUsers(["U20260000001", "U20260000002"]);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/admin/users");
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ ids: ["U20260000001", "U20260000002"] });
  });

  it("createUsers includes roles and daily_quota when given", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ created: 1, skipped: 0 }));

    await createUsers(["U20260000001"], ["viewer"], 100);

    expect(JSON.parse(fetchMock.mock.calls[0][1].body))
      .toEqual({ ids: ["U20260000001"], roles: ["viewer"], daily_quota: 100 });
  });

  it("resetUserPassword posts to the per-user URL", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await resetUserPassword("U20260000001");

    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/admin/users/U20260000001/reset-password");
    expect(fetchMock.mock.calls[0][1].method).toBe("POST");
  });

  it("setUserStatus posts the enabled flag", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await setUserStatus("U20260000001", false);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/admin/users/U20260000001/status");
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ enabled: false });
  });

  it("updateUser sends a PATCH with the given patch body", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await updateUser("U20260000001", { roles: ["operator"], daily_quota: 0 });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/admin/users/U20260000001");
    expect(options.method).toBe("PATCH");
    expect(JSON.parse(options.body)).toEqual({ roles: ["operator"], daily_quota: 0 });
  });

  it("deleteUsers posts ids with the operator password", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ deleted: ["U20260000002"], skipped: [] }));

    await deleteUsers(["U20260000002"], "secret");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/admin/users/delete");
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ ids: ["U20260000002"], password: "secret" });
  });

  it("assignStudents posts ids with the target teacher", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ assigned: ["U20260000003"], skipped: [] }));

    await assignStudents(["U20260000003"], "U20260000002");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/admin/users/assign");
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ ids: ["U20260000003"], created_by: "U20260000002" });
  });

  it("listAuditLog requests the audit log endpoint", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ entries: [{ id: "aud_1", actor: "U20260000001", action: "delete_users", targets: ["U20260000002"], created_at: "2026-08-27" }] }));

    const result = await listAuditLog();

    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/admin/audit-log");
    expect(result.entries[0].targets).toEqual(["U20260000002"]);
  });

  it("a 401 on an admin endpoint also clears the stored identity", async () => {
    setApiToken("expired");
    setUserIdentity("U20260000001", ["admin"]);
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 401, text: "unauthorized" }));

    await expect(listUsers()).rejects.toThrow("unauthorized");

    expect(storage.removeItem).toHaveBeenCalledWith("assistant_api_token");
    expect(storage.removeItem).toHaveBeenCalledWith("assistant_user_subject");
    expect(storage.removeItem).toHaveBeenCalledWith("assistant_user_roles");
  });
});

describe("grading endpoints", () => {
  it("getGradeSummary requests /api/grading/summary with the stored token", async () => {
    setApiToken("operator-token");
    const body = { by_user: [], by_protocol: [] };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await getGradeSummary();

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/grading/summary");
    expect(options.headers.Authorization).toBe("Bearer operator-token");
  });

  it("getGradeSubmissions without params omits the query string", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ submissions: [], total: 0 }));

    await getGradeSubmissions();

    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/grading/submissions");
  });

  it("getGradeSubmissions passes user_id/protocol/limit/offset as query params", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ submissions: [], total: 0 }));

    await getGradeSubmissions({ user_id: "U20260000001", protocol: "dns", limit: 50, offset: 10 });

    const url = fetchMock.mock.calls[0][0];
    expect(url).toContain("/api/grading/submissions?");
    expect(url).toContain("user_id=U20260000001");
    expect(url).toContain("protocol=dns");
    expect(url).toContain("limit=50");
    expect(url).toContain("offset=10");
  });

  it("getGradeSubmissions skips unset params", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ submissions: [], total: 0 }));

    await getGradeSubmissions({ limit: 200 });

    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/grading/submissions?limit=200");
  });

  it("a 401 on a grading endpoint also clears the stored identity", async () => {
    setApiToken("expired");
    setUserIdentity("U20260000001", ["operator"]);
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 401, text: "unauthorized" }));

    await expect(getGradeSummary()).rejects.toThrow("unauthorized");

    expect(storage.removeItem).toHaveBeenCalledWith("assistant_api_token");
    expect(storage.removeItem).toHaveBeenCalledWith("assistant_user_subject");
    expect(storage.removeItem).toHaveBeenCalledWith("assistant_user_roles");
  });
});


describe("assignment endpoints", () => {
  const pluginFile = () => new File(["int main(void){return 0;}"], "DNS.c", { type: "text/x-c" });

  it("listAssignmentProtocols requests /api/assignments/protocols with the stored token", async () => {
    setApiToken("admin-token");
    const body = { protocols: [{ name: "dns", cases: 8 }] };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await listAssignmentProtocols();

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/assignments/protocols");
    expect(options.headers.Authorization).toBe("Bearer admin-token");
  });

  it("listAssignments requests /api/assignments and parses the body", async () => {
    const body = { assignments: [{ id: "asg_1", title: "第一次作业" }] };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await listAssignments();

    expect(result).toEqual(body);
    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/assignments");
  });

  it("createAssignment posts title/description/deadline/items as JSON", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: "asg_1" }));
    const payload = {
      title: "第一次作业",
      description: "完成 DNS 插件",
      deadline: "2026-09-01T00:00:00.000Z",
      items: [{ protocol: "dns", points: 20 }]
    };

    await createAssignment(payload);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/assignments");
    expect(options.method).toBe("POST");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(JSON.parse(options.body)).toEqual(payload);
  });

  it("getAssignment URL-encodes the assignment id", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: "asg 1" }));

    await getAssignment("asg 1");

    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/assignments/asg%201");
  });

  it("updateAssignment sends a PATCH with the given patch body", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: "asg_1" }));

    await updateAssignment("asg_1", { title: "改名", items: [{ protocol: "http", points: 30 }] });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/assignments/asg_1");
    expect(options.method).toBe("PATCH");
    expect(JSON.parse(options.body)).toEqual({ title: "改名", items: [{ protocol: "http", points: 30 }] });
  });

  it("setAssignmentStatus posts to the withdraw/reactivate action path", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await setAssignmentStatus("asg_1", "withdraw");
    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/assignments/asg_1/withdraw");
    expect(fetchMock.mock.calls[0][1].method).toBe("POST");

    await setAssignmentStatus("asg_1", "reactivate");
    expect(fetchMock.mock.calls[1][0]).toBe("http://127.0.0.1:8090/api/assignments/asg_1/reactivate");
    expect(fetchMock.mock.calls[1][1].method).toBe("POST");
  });

  it("submitAssignment without a file posts JSON {protocol, code}", async () => {
    setApiToken("student-token");
    const body = { score: 80, passed: 4, total: 5, results: [], earned: 16, best_earned: 16 };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await submitAssignment("asg_1", { protocol: "dns", code: "int main(void){return 0;}" });

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/assignments/asg_1/submit");
    expect(options.method).toBe("POST");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.headers.Authorization).toBe("Bearer student-token");
    expect(JSON.parse(options.body)).toEqual({ protocol: "dns", code: "int main(void){return 0;}" });
  });

  it("submitAssignment with a file posts FormData without a manual Content-Type", async () => {
    setApiToken("student-token");
    fetchMock.mockResolvedValue(jsonResponse({ score: 100, passed: 5, total: 5 }));

    const result = await submitAssignment("asg_1", { protocol: "dns", code: "" }, pluginFile());

    expect(result).toEqual({ score: 100, passed: 5, total: 5 });
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/assignments/asg_1/submit");
    expect(options.method).toBe("POST");
    expect(options.body).toBeInstanceOf(FormData);
    expect(options.body.get("protocol")).toBe("dns");
    expect(options.body.get("file")).toBeInstanceOf(File);
    expect(options.body.get("file").name).toBe("DNS.c");
    // boundary 由运行时生成，不能手动设置 Content-Type。
    expect(options.headers).not.toHaveProperty("Content-Type");
    expect(options.headers.Authorization).toBe("Bearer student-token");
  });

  it("submitAssignment propagates backend errors (e.g. past deadline 400)", async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 400, text: "作业已截止" }));

    await expect(submitAssignment("asg_1", { protocol: "dns", code: "x" })).rejects.toThrow("作业已截止");
  });

  it("getAssignmentGrades requests the per-assignment grades endpoint", async () => {
    setApiToken("operator-token");
    const body = { items: [{ protocol: "dns", points: 20 }], rows: [] };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await getAssignmentGrades("asg_1");

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/assignments/asg_1/grades");
    expect(options.headers.Authorization).toBe("Bearer operator-token");
  });

  it("explainSubmission posts to the explain endpoint and returns {session_id, task_id, status}", async () => {
    setApiToken("student-token");
    const body = { session_id: "sess_x", task_id: "task_1", status: "started" };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await explainSubmission("asg_1", "sub_9");

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/assignments/asg_1/submissions/sub_9/explain");
    expect(options.method).toBe("POST");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.headers.Authorization).toBe("Bearer student-token");
    expect(JSON.parse(options.body)).toEqual({});
  });

  it("explainSubmission URL-encodes both ids", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ session_id: "s", task_id: "t", status: "attached" }));

    await explainSubmission("asg 1", "sub/9");

    expect(fetchMock.mock.calls[0][0])
      .toBe("http://127.0.0.1:8090/api/assignments/asg%201/submissions/sub%2F9/explain");
  });

  it("explainSubmission surfaces a 404 with error.status for the UI 不可讲解 copy", async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 404, text: "not found" }));

    await expect(explainSubmission("asg_1", "sub_9")).rejects.toMatchObject({ status: 404 });
  });

  describe("exportAssignmentGrades", () => {
    let anchor;
    let createObjectURL;
    let revokeObjectURL;

    beforeEach(() => {
      anchor = { click: vi.fn(), remove: vi.fn() };
      createObjectURL = vi.fn(() => "blob:mock-url");
      revokeObjectURL = vi.fn();
      vi.stubGlobal("document", {
        createElement: vi.fn(() => anchor),
        body: { appendChild: vi.fn() }
      });
      vi.stubGlobal("URL", { createObjectURL, revokeObjectURL });
    });

    function csvResponse(disposition) {
      return {
        ok: true,
        status: 200,
        headers: { get: (name) => (name === "Content-Disposition" ? disposition : null) },
        blob: async () => new Blob(["csv-data"], { type: "text/csv" })
      };
    }

    it("downloads the blob with the filename from Content-Disposition", async () => {
      setApiToken("admin-token");
      fetchMock.mockResolvedValue(csvResponse('attachment; filename="grades_asg_1.csv"'));

      await exportAssignmentGrades("asg_1");

      const [url, options] = fetchMock.mock.calls[0];
      expect(url).toBe("http://127.0.0.1:8090/api/assignments/asg_1/export");
      expect(options.headers.Authorization).toBe("Bearer admin-token");
      expect(anchor.download).toBe("grades_asg_1.csv");
      expect(anchor.href).toBe("blob:mock-url");
      expect(anchor.click).toHaveBeenCalledTimes(1);
      expect(revokeObjectURL).toHaveBeenCalledWith("blob:mock-url");
    });

    it("decodes an RFC 5987 filename* and falls back to a default name when absent", async () => {
      fetchMock.mockResolvedValue(csvResponse("attachment; filename*=UTF-8''%E6%88%90%E7%BB%A9.csv"));
      await exportAssignmentGrades("asg_1");
      expect(anchor.download).toBe("成绩.csv");

      fetchMock.mockResolvedValue(csvResponse(null));
      await exportAssignmentGrades("asg_1");
      expect(anchor.download).toBe("assignment_asg_1_grades.csv");
    });

    it("clears the stored identity and throws on a 401", async () => {
      setApiToken("expired");
      fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 401, text: "unauthorized" }));

      await expect(exportAssignmentGrades("asg_1")).rejects.toThrow("unauthorized");

      expect(storage.removeItem).toHaveBeenCalledWith("assistant_api_token");
      expect(dispatchEvent).toHaveBeenCalledTimes(1);
    });
  });
});

describe("task endpoints (S3-c)", () => {
  it("getTask requests /api/tasks/{task_id} with the stored token and parses the body", async () => {
    setApiToken("student-token");
    const body = {
      task_id: "task_1", status: "running", reason: "",
      request_id: "req_1", session_id: "sess_1", message_id: null,
      created_at: "2026-09-22T00:00:00", elapsed_ms: 120
    };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await getTask("task_1");

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/tasks/task_1");
    expect(options.method).toBeUndefined();   // GET 走 requestJson 默认
    expect(options.headers.Authorization).toBe("Bearer student-token");
  });

  it("getTask URL-encodes the task id and propagates a 404 with status", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ task_id: "t" }));

    await getTask("task 1");
    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/tasks/task%201");

    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 404, text: "task not found" }));
    await expect(getTask("task_404")).rejects.toMatchObject({ status: 404 });
  });

  it("getActiveTask requests /api/tasks/active with an encoded session_id and unwraps data.task", async () => {
    setApiToken("student-token");
    const task = {
      task_id: "task_1", status: "running", reason: "",
      request_id: "req_1", session_id: "sess_1", message_id: null,
      created_at: "2026-09-22T00:00:00", elapsed_ms: 120
    };
    fetchMock.mockResolvedValue(jsonResponse({ task }));

    const result = await getActiveTask("sess_1");

    expect(result).toEqual(task);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/tasks/active?session_id=sess_1");
    expect(options.method).toBeUndefined();   // GET 走 requestJson 默认
    expect(options.headers.Authorization).toBe("Bearer student-token");
  });

  it("getActiveTask URL-encodes the session_id query value", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ task: null }));

    await getActiveTask("a b/c?d");

    expect(fetchMock.mock.calls[0][0])
      .toBe("http://127.0.0.1:8090/api/tasks/active?session_id=a%20b%2Fc%3Fd");
  });

  it("getActiveTask returns null when the session has no active task", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ task: null }));

    await expect(getActiveTask("sess_1")).resolves.toBeNull();
  });

  it("getActiveTask propagates a 404 with error.status (unknown/foreign session)", async () => {
    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 404, text: "session not found" }));

    await expect(getActiveTask("sess_404")).rejects.toMatchObject({ status: 404 });
  });

  it("cancelTask posts an empty JSON body to /api/tasks/{id}/cancel and returns the status", async () => {
    setApiToken("student-token");
    const body = {
      task_id: "task_1", status: "cancelled", reason: "user_cancelled",
      request_id: "req_1", session_id: "sess_1", message_id: "msg_1",
      created_at: "2026-09-22T00:00:00", elapsed_ms: 240
    };
    fetchMock.mockResolvedValue(jsonResponse(body));

    const result = await cancelTask("task_1");

    expect(result).toEqual(body);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/tasks/task_1/cancel");
    expect(options.method).toBe("POST");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.headers.Authorization).toBe("Bearer student-token");
    expect(JSON.parse(options.body)).toEqual({});
  });

  it("cancelTask URL-encodes the task id and propagates a 404 with status", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ task_id: "t", status: "cancelled" }));

    await cancelTask("task 1");
    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8090/api/tasks/task%201/cancel");

    fetchMock.mockResolvedValue(jsonResponse(null, { ok: false, status: 404, text: "task not found" }));
    await expect(cancelTask("task_404")).rejects.toMatchObject({ status: 404 });
  });
});

describe("streamTaskEvents SSE (S3-c)", () => {
  it("GETs /api/tasks/{id}/events and parses multiple frames in one chunk", async () => {
    setApiToken("student-token");
    const payload =
      "event: start\ndata: {\"session_id\":\"sess_1\",\"task_id\":\"task_1\"}\n\n" +
      "event: sources\ndata: {\"citations\":[{\"chunk_id\":\"c1\"}]}\n\n" +
      "event: delta\ndata: {\"delta\":\"讲解\"}\n\n" +
      "event: done\ndata: {\"status\":\"succeeded\",\"terminal\":true}\n\n";
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamBody([encode(payload)])
    });
    const onEvent = vi.fn();
    const controller = new AbortController();

    await streamTaskEvents("task_1", { signal: controller.signal, onEvent });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("http://127.0.0.1:8090/api/tasks/task_1/events");
    expect(options.method).toBeUndefined();   // GET
    expect(options.signal).toBe(controller.signal);
    expect(options.headers.Authorization).toBe("Bearer student-token");
    expect(onEvent.mock.calls).toEqual([
      ["start", { session_id: "sess_1", task_id: "task_1" }],
      ["sources", { citations: [{ chunk_id: "c1" }] }],
      ["delta", { delta: "讲解" }],
      ["done", { status: "succeeded", terminal: true }]
    ]);
  });

  it("routes tool_start/tool_end to the tool callbacks and reassembles split frames", async () => {
    const full =
      "event: tool_start\ndata: {\"tool\":\"kb_search\",\"label\":\"正在检索知识库…\"}\n\n" +
      "event: delta\ndata: {\"delta\":\"部分\"}\n\n" +
      "event: tool_end\ndata: {\"tool\":\"kb_search\",\"ok\":true,\"summary\":\"命中 3 条\"}\n\n";
    const cut = full.indexOf("\"部分");
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamBody([encode(full.slice(0, cut)), encode(full.slice(cut))])
    });
    const onEvent = vi.fn();
    const onToolStart = vi.fn();
    const onToolEnd = vi.fn();

    await streamTaskEvents("task_1", { onEvent, onToolStart, onToolEnd });

    expect(onToolStart).toHaveBeenCalledWith({ tool: "kb_search", label: "正在检索知识库…" });
    expect(onToolEnd).toHaveBeenCalledWith({ tool: "kb_search", ok: true, summary: "命中 3 条" });
    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledWith("delta", { delta: "部分" });
  });

  it("falls back to onEvent for tool events when no tool callbacks are given", async () => {
    const payload =
      "event: tool_start\ndata: {\"tool\":\"kb_search\"}\n\n" +
      "event: tool_end\ndata: {\"tool\":\"kb_search\",\"ok\":true}\n\n";
    fetchMock.mockResolvedValue({ ok: true, status: 200, body: streamBody([encode(payload)]) });
    const onEvent = vi.fn();

    await streamTaskEvents("task_1", { onEvent });

    expect(onEvent).toHaveBeenCalledTimes(2);
    expect(onEvent).toHaveBeenNthCalledWith(1, "tool_start", { tool: "kb_search" });
    expect(onEvent).toHaveBeenNthCalledWith(2, "tool_end", { tool: "kb_search", ok: true });
  });

  it("throws with error.status on a 404 (unknown task) before reading the body", async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 404,
      body: null,
      text: async () => "task not found"
    });

    await expect(streamTaskEvents("task_404", { onEvent: vi.fn() }))
      .rejects.toMatchObject({ status: 404 });
  });

  it("throws on a 401 and clears the stored identity", async () => {
    setApiToken("expired");
    fetchMock.mockResolvedValue({
      ok: false,
      status: 401,
      body: null,
      text: async () => "unauthorized"
    });

    await expect(streamTaskEvents("task_1", { onEvent: vi.fn() })).rejects.toThrow("unauthorized");

    expect(storage.removeItem).toHaveBeenCalledWith("assistant_api_token");
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
  });
});
