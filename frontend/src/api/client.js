// VITE_API_BASE="" (production build) means same-origin: requests go to
// "/api/..." on the serving host, which nginx reverse-proxies to the backend.
// Unset (local dev) falls back to the dev backend on 127.0.0.1:8090.
const API_BASE = import.meta.env.VITE_API_BASE ?? "http://127.0.0.1:8090";
const TOKEN_KEY = "assistant_api_token";
const SUBJECT_KEY = "assistant_user_subject";
const ROLES_KEY = "assistant_user_roles";
const EXPIRES_KEY = "assistant_token_expires_at";
const SESSION_KEY = "assistant_last_session";

export function getApiToken() {
  return localStorage.getItem(TOKEN_KEY) || "";
}

// 401 后置位、拿到新令牌时复位：探测类请求据此在「无有效凭据」期间短路，
// 不再周期性打无令牌请求（登录页遮罩下侧栏组件仍挂载时的 auth_failed 噪音）。
// local 模式无令牌概念、请求恒成功，永不进入此态。
let authBlocked = false;

export function authProbeReady() {
  return !authBlocked || !!getApiToken();
}

export function setApiToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
    authBlocked = false;
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

export function getUserSubject() {
  return localStorage.getItem(SUBJECT_KEY) || "";
}

export function getUserRoles() {
  try {
    const roles = JSON.parse(localStorage.getItem(ROLES_KEY) || "[]");
    return Array.isArray(roles) ? roles : [];
  } catch {
    return [];
  }
}

export function setUserIdentity(subject, roles) {
  if (subject) localStorage.setItem(SUBJECT_KEY, subject);
  else localStorage.removeItem(SUBJECT_KEY);
  if (subject && roles && roles.length) localStorage.setItem(ROLES_KEY, JSON.stringify(roles));
  else localStorage.removeItem(ROLES_KEY);
}

export function getTokenExpiresAt() {
  return localStorage.getItem(EXPIRES_KEY) || "";
}

export function setTokenExpiresAt(value) {
  if (value) localStorage.setItem(EXPIRES_KEY, value);
  else localStorage.removeItem(EXPIRES_KEY);
}

// 最近会话 id 持久化：刷新页面后据此自动找回会话与仍在执行的活动任务。
// 服务端按归属校验——拿到他人/已删会话 id 只会 404，不泄露内容。
export function getLastSessionId() {
  return localStorage.getItem(SESSION_KEY) || "";
}

export function setLastSessionId(value) {
  if (value) localStorage.setItem(SESSION_KEY, value);
  else localStorage.removeItem(SESSION_KEY);
}

function authHeaders() {
  const token = getApiToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function handleUnauthorized(response) {
  if (response.status === 401) {
    // The stored token is missing or rejected: drop it (and the cached
    // identity) and ask the UI to collect a new one before retrying.
    authBlocked = true;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(SUBJECT_KEY);
    localStorage.removeItem(ROLES_KEY);
    localStorage.removeItem(EXPIRES_KEY);
    window.dispatchEvent(new Event("assistant-auth-required"));
  }
}

export async function requestJson(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
      ...(options.headers || {})
    }
  });
  if (!response.ok) {
    handleUnauthorized(response);
    const text = await response.text();
    // 把 status 和 Retry-After 挂到 Error 上，供调用方识别限流（429）。
    const error = new Error(text || `Request failed: ${response.status}`);
    error.status = response.status;
    const retryAfter = Number(response.headers.get("Retry-After"));
    if (retryAfter > 0) error.retryAfter = retryAfter;
    throw error;
  }
  // 204 No Content（logout/change-password/reset-password/status）没有响应体；
  // 202（password-reset-requests）同样可能没有响应体。
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

// 有附件时聊天接口改走 multipart/form-data：message/session_id 为纯文本字段，
// context 沿用 JSON 语义（序列化为字符串），file 为 .c/.so 附件。
// Content-Type 不手动设置，由浏览器/运行时带 boundary 生成。
export function buildChatFormData(payload, file) {
  const form = new FormData();
  form.append("message", payload.message ?? "");
  if (payload.request_id) form.append("request_id", payload.request_id);
  if (payload.session_id) form.append("session_id", payload.session_id);
  if (payload.context) form.append("context", JSON.stringify(payload.context));
  form.append("file", file, file.name || "attachment");
  return form;
}

async function requestFormData(path, form, signal) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST", signal,
    headers: { ...authHeaders() },
    body: form
  });
  if (!response.ok) {
    handleUnauthorized(response);
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  return response;
}

export function sendChat(payload, file) {
  if (file) {
    return requestFormData("/api/chat", buildChatFormData(payload, file))
      .then(async (response) => {
        const text = await response.text();
        return text ? JSON.parse(text) : null;
      });
  }
  return requestJson("/api/chat", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

// SSE 帧解析（/api/chat/stream 与 /api/tasks/{id}/events 共用同一帧格式：
// "event:"/"data:" 对、\n\n 分隔）。tool_start/tool_end 优先走可选专用回调，
// 未提供时回落到 onEvent；对其余未知事件类型保持宽松透传。
async function readSseStream(response, { onEvent, onToolStart, onToolEnd }) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split("\n\n");
    buffer = frames.pop() || "";
    for (const frame of frames) {
      const event = frame.match(/^event:\s*(.+)$/m)?.[1];
      const raw = frame.match(/^data:\s*(.+)$/m)?.[1];
      if (!event || !raw) continue;
      const data = JSON.parse(raw);
      if (event === "tool_start" && onToolStart) { onToolStart(data); continue; }
      if (event === "tool_end" && onToolEnd) { onToolEnd(data); continue; }
      onEvent(event, data);
    }
  }
}

// 读取 SSE 响应前的统一失败处理：401 清身份，错误带上 status 供调用方区分 404/429。
async function ensureStreamResponse(response) {
  if (!response.ok || !response.body) {
    handleUnauthorized(response);
    const text = await response.text();
    const error = new Error(text || `Request failed: ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return response;
}

// onToolStart/onToolEnd 为可选回调：M1 起后端会推送 tool_start/tool_end 事件
// （kb_search、grade_plugin 等）。未提供这两个回调时事件仍回落到 onEvent，
// 保持旧调用方行为不变；解析对其余未知事件依旧宽松。
export async function sendChatStream(payload, { signal, onEvent, onToolStart, onToolEnd }, file) {
  const response = file
    ? await requestFormData("/api/chat/stream", buildChatFormData(payload, file), signal)
    : await fetch(`${API_BASE}/api/chat/stream`, {
        method: "POST", signal,
        headers: { "Content-Type": "application/json", ...authHeaders() },
        body: JSON.stringify(payload)
      });
  await ensureStreamResponse(response);
  await readSseStream(response, { onEvent, onToolStart, onToolEnd });
}

export function getSource(chunkId) {
  return requestJson(`/api/sources/${chunkId}`);
}

export function getMyUsage() {
  return requestJson("/api/usage/me");
}

export function getSubjectUsage() {
  return requestJson("/api/usage/subjects");
}

// 成绩视图（M3）：operator/admin 可访问，其余角色后端返回 404。
// params 支持 user_id / protocol / limit / offset，未给的参数不出现在 query 里。
export function getGradeSubmissions(params = {}) {
  const search = new URLSearchParams();
  if (params.user_id) search.set("user_id", params.user_id);
  if (params.protocol) search.set("protocol", params.protocol);
  if (params.limit !== undefined && params.limit !== null) search.set("limit", params.limit);
  if (params.offset !== undefined && params.offset !== null) search.set("offset", params.offset);
  const query = search.toString();
  return requestJson(`/api/grading/submissions${query ? `?${query}` : ""}`);
}

export function getGradeSummary() {
  return requestJson("/api/grading/summary");
}

export function loadChatSession(sessionId, limit = 100) {
  return requestJson(`/api/chat/sessions/${encodeURIComponent(sessionId)}?limit=${limit}`);
}

export function generateTemplate(payload) {
  return requestJson("/api/plugin-template", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

// 只负责请求并返回登录响应；token/身份信息由调用方（UI）负责落库。
export function login(username, password) {
  return requestJson("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
}

// 后端成功返回 204（无响应体），且失败不阻塞本地退出，因此不走 requestJson。
export async function logout() {
  try {
    const response = await fetch(`${API_BASE}/api/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...authHeaders() }
    });
    if (!response.ok) handleUnauthorized(response);
  } catch {
    // 网络错误同样忽略：退出登录是尽力而为的操作。
  }
}

export function changePassword(oldPassword, newPassword) {
  return requestJson("/api/auth/change-password", {
    method: "POST",
    body: JSON.stringify({ old_password: oldPassword, new_password: newPassword })
  });
}

// 后端可能返回 {sessions: [...]} 或裸数组，统一规整为数组。
export async function listSessions(limit = 50, offset = 0) {
  const data = await requestJson(`/api/chat/sessions?limit=${limit}&offset=${offset}`);
  return Array.isArray(data) ? data : (data.sessions || []);
}

export function listUsers() {
  return requestJson("/api/admin/users");
}

export function createUsers(ids, roles, dailyQuota) {
  const body = { ids };
  if (roles) body.roles = roles;
  if (dailyQuota !== undefined && dailyQuota !== null) body.daily_quota = dailyQuota;
  return requestJson("/api/admin/users", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function resetUserPassword(userId) {
  return requestJson(`/api/admin/users/${encodeURIComponent(userId)}/reset-password`, {
    method: "POST"
  });
}

export function setUserStatus(userId, enabled) {
  return requestJson(`/api/admin/users/${encodeURIComponent(userId)}/status`, {
    method: "POST",
    body: JSON.stringify({ enabled })
  });
}

export function updateUser(userId, patch) {
  return requestJson(`/api/admin/users/${encodeURIComponent(userId)}`, {
    method: "PATCH",
    body: JSON.stringify(patch)
  });
}

// 忘记密码：匿名提交申请，无论学号是否存在后端都统一返回 202。
export function requestPasswordReset(username) {
  return requestJson("/api/auth/password-reset-requests", {
    method: "POST",
    body: JSON.stringify({ username })
  });
}

// 凭管理员签发的一次性令牌自助改密，成功 204，失败统一 400。
export function confirmPasswordReset(token, newPassword) {
  return requestJson("/api/auth/password-reset", {
    method: "POST",
    body: JSON.stringify({ token, new_password: newPassword })
  });
}

export function setUserPassword(userId, newPassword) {
  return requestJson(`/api/admin/users/${encodeURIComponent(userId)}/set-password`, {
    method: "POST",
    body: JSON.stringify({ new_password: newPassword })
  });
}

export function listPasswordResetRequests(status = "pending") {
  return requestJson(`/api/admin/password-reset-requests?status=${encodeURIComponent(status)}`);
}

export function approvePasswordResetRequest(requestId) {
  return requestJson(`/api/admin/password-reset-requests/${encodeURIComponent(requestId)}/approve`, {
    method: "POST"
  });
}

export function rejectPasswordResetRequest(requestId) {
  return requestJson(`/api/admin/password-reset-requests/${encodeURIComponent(requestId)}/reject`, {
    method: "POST"
  });
}

export function listAuditLog() {
  return requestJson("/api/admin/audit-log");
}

export function deleteUsers(ids, password) {
  return requestJson("/api/admin/users/delete", {
    method: "POST",
    body: JSON.stringify({ ids, password })
  });
}

export function assignStudents(ids, createdBy) {
  return requestJson("/api/admin/users/assign", {
    method: "POST",
    body: JSON.stringify({ ids, created_by: createdBy })
  });
}

// 删除自己的会话：成功 204，会话不存在/非本人统一 404。
export function deleteSession(sessionId) {
  return requestJson(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: "DELETE"
  });
}

// ---------- 作业管理（assignments） ----------
// 仅 account 模式可用；非目标角色后端统一 404，调用方按惯例静默降级。

// 可评测协议清单（发布表单下拉用）：harness 不可达时返回 {protocols: [], error}。
export function listAssignmentProtocols() {
  return requestJson("/api/assignments/protocols");
}

// 学生视角返回可见作业+我的成绩摘要；管理视角返回我创建的（leader 全部）。
export function listAssignments() {
  return requestJson("/api/assignments");
}

export function createAssignment(payload) {
  return requestJson("/api/assignments", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function getAssignment(assignmentId) {
  return requestJson(`/api/assignments/${encodeURIComponent(assignmentId)}`);
}

export function updateAssignment(assignmentId, patch) {
  return requestJson(`/api/assignments/${encodeURIComponent(assignmentId)}`, {
    method: "PATCH",
    body: JSON.stringify(patch)
  });
}

// action 为 "withdraw"（撤回）或 "reactivate"（恢复）。
export function setAssignmentStatus(assignmentId, action) {
  return requestJson(`/api/assignments/${encodeURIComponent(assignmentId)}/${action}`, {
    method: "POST"
  });
}

// 作业提交：有文件走 multipart（protocol 为文本字段，file 为 .c/.so 附件，
// 仿 buildChatFormData 不手动设置 Content-Type）；否则 JSON {protocol, code}。
export function submitAssignment(assignmentId, payload, file) {
  const path = `/api/assignments/${encodeURIComponent(assignmentId)}/submit`;
  if (file) {
    const form = new FormData();
    form.append("protocol", payload.protocol ?? "");
    form.append("file", file, file.name || "submission");
    return requestFormData(path, form).then(async (response) => {
      const text = await response.text();
      return text ? JSON.parse(text) : null;
    });
  }
  return requestJson(path, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

// 单份作业的成绩表（operator/admin/leader）：{assignment, rows}，字段缺失由 UI 宽松处理。
export function getAssignmentGrades(assignmentId) {
  return requestJson(`/api/assignments/${encodeURIComponent(assignmentId)}/grades`);
}

// 导出成绩 CSV：后端返回 text/csv，前端转 Blob 触发浏览器下载。
// 文件名取 Content-Disposition 的 filename*/filename，缺失时回退默认名。
export async function exportAssignmentGrades(assignmentId) {
  const response = await fetch(`${API_BASE}/api/assignments/${encodeURIComponent(assignmentId)}/export`, {
    headers: { ...authHeaders() }
  });
  if (!response.ok) {
    handleUnauthorized(response);
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  const disposition = response.headers.get("Content-Disposition") || "";
  const starMatch = disposition.match(/filename\*=(?:UTF-8'')?([^;]+)/i);
  const plainMatch = disposition.match(/filename="?([^";]+)"?/i);
  const filename = starMatch
    ? decodeURIComponent(starMatch[1].trim())
    : plainMatch
      ? plainMatch[1].trim()
      : `assignment_${assignmentId}_grades.csv`;
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

// S3-d 结果讲解入口：把一次作业提交交给助教讲解（后端不重新评测、不改成绩）。
// 返回 {session_id, task_id, status: "started"|"attached"|"replayed"}；
// 作业不可见/非本人提交统一 404（error.status 由 requestJson 附带）。
export function explainSubmission(assignmentId, submissionId) {
  return requestJson(
    `/api/assignments/${encodeURIComponent(assignmentId)}/submissions/${encodeURIComponent(submissionId)}/explain`,
    { method: "POST", body: JSON.stringify({}) }
  );
}

// ---------- 任务事件流（S3-c 展示与重连） ----------
// 帧格式与 /api/chat/stream 完全一致，复用同一 SSE 解析与回调约定。

// 任务只读状态：{task_id, status, reason, request_id, session_id, message_id, ...}；
// 他人/不存在统一 404。
export function getTask(taskId) {
  return requestJson(`/api/tasks/${encodeURIComponent(taskId)}`);
}

// 会话内最近一条活动任务（pending/running）：页面刷新/会话切换后的找回入口。
// 后端返回 {"task": TaskStatusResponse|null}，这里规整为任务对象或 null。
export async function getActiveTask(sessionId) {
  const data = await requestJson(`/api/tasks/active?session_id=${encodeURIComponent(sessionId)}`);
  return data?.task ?? null;
}

// 显式取消（S3-c：断线≠取消，停止按钮必须走这里）；幂等——重复调用与
// 已终态任务都返回当前状态。空 JSON 体与 explainSubmission 的 POST 习惯一致。
export function cancelTask(taskId) {
  return requestJson(`/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
    method: "POST",
    body: JSON.stringify({})
  });
}

// 订阅任务事件流：进行中接续实时帧（含已发帧回放），终态重建回放帧。
// handlers 与 sendChatStream 同签名风格：{signal, onEvent, onToolStart, onToolEnd}。
export async function streamTaskEvents(taskId, { signal, onEvent, onToolStart, onToolEnd }) {
  const response = await fetch(`${API_BASE}/api/tasks/${encodeURIComponent(taskId)}/events`, {
    signal,
    headers: { Accept: "text/event-stream", ...authHeaders() }
  });
  await ensureStreamResponse(response);
  await readSseStream(response, { onEvent, onToolStart, onToolEnd });
}
