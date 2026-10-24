import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  ArrowLeft, ArrowUp, BarChart3, BookOpen, Bot, Check, ChevronDown, ChevronRight, CircleDot, ClipboardList, Code2,
  Copy, Download, Eye, EyeOff, FileCode2, FolderSearch, History, KeyRound, Loader2, LogOut, Menu, MessageSquarePlus,
  PanelRightClose, PanelRightOpen, Paperclip, Send, Sparkles, Square, TerminalSquare, Trash2, Users, Wrench, X
} from "lucide-react";
import { marked } from "marked";
import DOMPurify from "dompurify";
import {
  approvePasswordResetRequest, assignStudents, authProbeReady, cancelTask, changePassword, confirmPasswordReset,
  createAssignment,
  createUsers, deleteSession, deleteUsers, explainSubmission, exportAssignmentGrades, getActiveTask,
  generateTemplate, getApiToken, getAssignment, getAssignmentGrades, getGradeSummary, getLastSessionId,
  getMyUsage, getSource,
  getSubjectUsage, getTokenExpiresAt,
  getUserRoles, getUserSubject, listAssignmentProtocols, listAssignments, listAuditLog,
  listPasswordResetRequests, listSessions, listUsers,
  loadChatSession, login, logout, rejectPasswordResetRequest, requestPasswordReset,
  resetUserPassword, sendChatStream, setApiToken, setAssignmentStatus, setLastSessionId, setTokenExpiresAt,
  setUserIdentity,
  setUserPassword, setUserStatus, streamTaskEvents, submitAssignment, updateAssignment
} from "./api/client.js";
import {
  classOf, formatScore, gradeItemsOf, itemBestLabel, itemEarnedLabel, protocolScoreOf,
  studentItemsOf, studentTotalEarned, studentTotalPoints, totalPointsOf
} from "./grades";
import "./styles/app.css";
import RagWorkspace, { AnswerFeedback, KnowledgeHealth } from "./RagWorkspace.jsx";
import { retryPendingTask } from "./taskRecovery.js";

const QUICK_QUESTIONS = [
  { label: "项目运行链路", detail: "从抓包到分析结果", question: "这个项目的整体运行链路是什么？", icon: CircleDot },
  { label: "插件接口", detail: "can_handle 与 process", question: "can_handle 和 process 有什么区别？", icon: Code2 },
  { label: "DNS 插件学习", detail: "从哪些文件开始", question: "如果我要学习 DNS 插件，应该先看哪些文件？", icon: FolderSearch }
];

// 任务事件流收到 done{status:"pending",terminal:false}（pending 行暂不可重驱动：
// 太新/占槽满）时的有界重试参数——覆盖孤儿恢复 grace 窗口后如实停手。
const PENDING_RESUME_RETRY_MS = 3000;
const PENDING_RESUME_MAX_ATTEMPTS = 10;

const MARKDOWN_SANITIZE_OPTIONS = {
  ALLOWED_TAGS: [
    "a", "b", "blockquote", "br", "code", "del", "em", "h1", "h2", "h3", "h4",
    "h5", "h6", "hr", "i", "li", "ol", "p", "pre", "strong", "table", "tbody",
    "td", "th", "thead", "tr", "ul"
  ],
  ALLOWED_ATTR: ["class", "href", "title"],
  ALLOW_DATA_ATTR: false
};

// 聊天附件（M1）：仅接受 .c / .so，且不超过 256KB，超限前端直接提示不上传。
const ATTACHMENT_MAX_BYTES = 256 * 1024;
const ATTACHMENT_EXTENSIONS = [".c", ".so"];

function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

// 返回错误文案；合法则返回空串。
function validateAttachment(file) {
  const dot = file.name.lastIndexOf(".");
  const ext = dot >= 0 ? file.name.slice(dot).toLowerCase() : "";
  if (!ATTACHMENT_EXTENSIONS.includes(ext)) return "仅支持上传 .c 或 .so 文件。";
  if (file.size > ATTACHMENT_MAX_BYTES) return `附件超过 256KB（当前 ${formatFileSize(file.size)}），请精简后再试。`;
  return "";
}

// 工具调用卡片：tool_start 进入运行态（label + 加载动画），tool_end 后显示
// summary 并按 ok 着色；默认折叠，展开可见工具名与原始事件数据。
function ToolCallCard({ entry }) {
  const [open, setOpen] = useState(false);
  const running = entry.status === "running";
  return (
    <div className={`tool-card ${running ? "running" : entry.ok ? "ok" : "failed"}`}>
      <button type="button" className="tool-card-toggle" onClick={() => setOpen((prev) => !prev)} aria-expanded={open}>
        {running ? <Loader2 size={13} className="tool-spin" /> : entry.ok ? <Check size={13} /> : <X size={13} />}
        <span>{running ? (entry.label || entry.tool) : (entry.summary || entry.label || entry.tool)}</span>
        {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
      </button>
      {open && (
        <div className="tool-card-body">
          <div className="tool-card-meta"><Wrench size={12} />{entry.tool}</div>
          <pre>{JSON.stringify(entry.raw || { tool: entry.tool, label: entry.label }, null, 2)}</pre>
        </div>
      )}
    </div>
  );
}

function Markdown({ text }) {
  // Answers are model output and therefore untrusted HTML input. Sanitizing
  // after Markdown rendering also blocks raw HTML from indexed documents.
  const html = useMemo(
    () => DOMPurify.sanitize(marked.parse(text || ""), MARKDOWN_SANITIZE_OPTIONS),
    [text]
  );
  return <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />;
}

function UsagePanel({ open, onToggle }) {
  const [usage, setUsage] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setError("");
    getMyUsage()
      .then((data) => { if (!cancelled) setUsage(data); })
      .catch(() => { if (!cancelled) setError("用量数据加载失败，请稍后重试。"); });
    return () => { cancelled = true; };
  }, [open]);

  const quotaLabel = (quota) => (quota === 0 ? "不限" : String(quota));
  const maxCount = usage ? Math.max(1, ...usage.recent_days.map((item) => item.chat_count)) : 1;

  return (
    <div className="usage-panel">
      <button type="button" className="usage-toggle" onClick={onToggle} aria-expanded={open}>
        <BarChart3 size={15} /><span>用量</span>
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
      </button>
      {open && (
        <div className="usage-body">
          {error && <small className="usage-error">{error}</small>}
          {usage && (
            <>
              <div className="usage-summary">
                今日 <b>{usage.today.chat_count}</b> / {quotaLabel(usage.daily_quota)}
              </div>
              <div className="usage-days">
                {usage.recent_days.map((item) => (
                  <div className="usage-day" key={item.day}>
                    <span className="usage-day-label">{item.day.slice(5)}</span>
                    <span className="usage-bar-track">
                      <span className="usage-bar" style={{ width: `${(item.chat_count / maxCount) * 100}%` }} />
                    </span>
                    <span className="usage-day-count">{item.chat_count}</span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function formatSessionTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// 429 限流提示：优先使用响应头 Retry-After 的秒数。
function rateLimitMessage(err) {
  const seconds = Math.ceil(Number(err?.retryAfter) || 0);
  return seconds > 0 ? `尝试次数过多，请 ${seconds} 秒后再试。` : "尝试次数过多，请稍后再试。";
}

// 会话历史消息 → 气泡模型：user 原样，assistant 规整为与流式渲染同构的对象。
// reloadSession 与讲解会话预载（S3-d）共用，保证两处渲染口径一致。
function mapHistoryMessage(message) {
  return message.role === "user"
    ? { role: "user", content: message.content }
    : {
        ...message, role: "assistant", answer: message.content,
        citations: message.citations || [], confidence: message.confidence || "medium",
        streaming: false
      };
}

// 流式事件 → assistant 气泡的统一应用逻辑：聊天流（/api/chat/stream）与任务事件流
// （/api/tasks/{id}/events，S3-c/S3-d 讲解入口）共用同一份，保证渲染零分叉。
// patchAssistant 由调用方给出（定位当前流式气泡）；onStart/onDone 为可选钩子。
// error 事件抛错交给调用方的 catch 统一收尾，与既有聊天路径同语义。
function makeStreamHandlers(patchAssistant, { onStart, onDone } = {}) {
  return {
    onEvent: (event, data) => {
      if (event === "start") onStart?.(data);
      if (event === "sources") patchAssistant((item) => ({ ...item, citations: data.citations }));
      if (event === "delta") patchAssistant((item) => ({ ...item, answer: item.answer + data.delta }));
      if (event === "done") {
        patchAssistant((item) => ({ ...item, ...data, streaming: false }));
        onDone?.(data);
      }
      if (event === "error") throw new Error(data.message);
    },
    onToolStart: (data) => patchAssistant((item) => ({
      ...item,
      tools: [...(item.tools || []), { tool: data.tool, label: data.label || data.tool, status: "running" }]
    })),
    // 配对最近一个同名且仍在运行中的工具调用；对不上则补一条已完成记录。
    onToolEnd: (data) => patchAssistant((item) => {
      const tools = [...(item.tools || [])];
      const at = tools.findLastIndex((entry) => entry.tool === data.tool && entry.status === "running");
      const done = { tool: data.tool, status: "done", ok: data.ok !== false, summary: data.summary || "", raw: data };
      if (at < 0) tools.push(done);
      else tools[at] = { ...tools[at], ...done };
      return { ...item, tools };
    })
  };
}

// 流结束（含中断/报错）时仍停在运行态的工具一律收尾为失败，避免加载动画空转。
function finalizeStreamingBubble(patchAssistant) {
  patchAssistant((item) => ({
    ...item, streaming: false,
    tools: item.tools?.map((entry) => entry.status === "running" ? { ...entry, status: "done", ok: false, summary: "已中断" } : entry)
  }));
}

// 气泡的「已取消」终态判定（S3-e）：本地显式取消落的 cancelled 标记与服务端
// done 帧携带的 status/answer_status="cancelled"（终态回放/迟到终帧）同口径展示。
function isCancelledMessage(message) {
  return message?.cancelled === true || message?.status === "cancelled" || message?.answer_status === "cancelled";
}

// 取消来源的中文文案：reason 由取消接口/终态帧带回（user_cancelled/account_disabled 等）。
function cancelledLabel(message) {
  const reason = message?.reason || message?.answer_reason;
  if (reason === "account_disabled") return "已终止（账号停用）";
  return "已取消";   // user_cancelled 及未知来源统一按用户取消展示
}

// 所有密码输入框统一带可见性切换（eye 图标切换 password/text）。
function PasswordInput({ value, onChange, placeholder, autoComplete, id }) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="password-field">
      <input id={id} type={visible ? "text" : "password"} value={value} onChange={onChange}
        placeholder={placeholder} autoComplete={autoComplete} aria-label={placeholder} />
      <button type="button" className="password-toggle" tabIndex={-1}
        onClick={() => setVisible((prev) => !prev)} aria-label={visible ? "隐藏密码" : "显示密码"}>
        {visible ? <EyeOff size={14} /> : <Eye size={14} />}
      </button>
    </div>
  );
}

function SessionList({ currentId, refreshKey, error, onSelect }) {
  const [sessions, setSessions] = useState(null);

  useEffect(() => {
    let cancelled = false;
    // local 模式（无需令牌）与 account 模式都能调通；接口不可用时静默隐藏。
    // 401 后且尚无新令牌时探测短路（authProbeReady），不打必败的无令牌请求。
    if (!authProbeReady()) return undefined;
    listSessions()
      .then((items) => { if (!cancelled) setSessions(items); })
      .catch(() => { if (!cancelled) setSessions(null); });
    return () => { cancelled = true; };
  }, [refreshKey]);

  if (!sessions) return null;
  return (
    <>
      <div className="sidebar-label"><History size={11} /> 历史会话</div>
      <nav className="session-list">
        {sessions.length === 0 && <small className="session-empty">暂无历史会话</small>}
        {sessions.map((session) => (
          <button key={session.id} type="button"
            className={session.id === currentId ? "active" : ""}
            onClick={() => onSelect(session.id)}>
            <span><b>{session.title || "未命名会话"}</b><small>{formatSessionTime(session.updated_at)}</small></span>
            <ChevronRight size={15} />
          </button>
        ))}
      </nav>
      {error && <small className="session-error">{error}</small>}
    </>
  );
}

// 侧边栏管理员入口：先探测接口是否可用（非 admin/leader/operator 或 404 时不显示），
// 点击后由 App 切换到全宽管理页。operator 只有成绩查看权限，入口直接打开作业管理页
// （成绩区已随 GradesCard 迁入作业维度），用成绩汇总接口探测。
function AdminNavEntry({ roles, onOpen }) {
  const [supported, setSupported] = useState(false);
  const canManage = roles.includes("admin") || roles.includes("leader");

  useEffect(() => {
    let cancelled = false;
    // 非目标角色或接口不存在返回 404：入口不显示（与用量面板降级惯例一致）。
    const probe = canManage ? listUsers() : getGradeSummary();
    probe
      .then(() => { if (!cancelled) setSupported(true); })
      .catch(() => { if (!cancelled) setSupported(false); });
    return () => { cancelled = true; };
  }, [canManage]);

  if (!supported) return null;
  return (
    <div className="usage-panel">
      <button type="button" className="usage-toggle" onClick={() => onOpen(canManage ? "admin" : "assignments")}>
        <Users size={15} /><span>{canManage ? "用户管理" : "作业管理"}</span>
        <ChevronRight size={14} />
      </button>
    </div>
  );
}

// 侧边栏「作业管理」入口（admin/leader）：operator 经由 AdminNavEntry 进入同一页面，
// 这里不重复渲染。探测失败（非 account 模式等）时静默隐藏。
function AssignmentsNavEntry({ roles, onOpen }) {
  const [supported, setSupported] = useState(false);
  const canManage = roles.includes("admin") || roles.includes("leader");

  useEffect(() => {
    if (!canManage || !authProbeReady()) return undefined;
    let cancelled = false;
    listAssignments()
      .then(() => { if (!cancelled) setSupported(true); })
      .catch(() => { if (!cancelled) setSupported(false); });
    return () => { cancelled = true; };
  }, [canManage]);

  if (!canManage || !supported) return null;
  return (
    <div className="usage-panel">
      <button type="button" className="usage-toggle" onClick={onOpen}>
        <ClipboardList size={15} /><span>作业管理</span>
        <ChevronRight size={14} />
      </button>
    </div>
  );
}

// 侧边栏「作业提交」入口：account 模式已登录用户可见；作业端点不可用时静默隐藏。
function HomeworkNavEntry({ onOpen }) {
  const [supported, setSupported] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listAssignments()
      .then(() => { if (!cancelled) setSupported(true); })
      .catch(() => { if (!cancelled) setSupported(false); });
    return () => { cancelled = true; };
  }, []);

  if (!supported) return null;
  return (
    <div className="usage-panel">
      <button type="button" className="usage-toggle" onClick={onOpen}>
        <BookOpen size={15} /><span>作业提交</span>
        <ChevronRight size={14} />
      </button>
    </div>
  );
}

function AdminPage({ onBack, roles }) {
  const isLeader = roles.includes("leader");
  const canManage = isLeader || roles.includes("admin");
  const [users, setUsers] = useState([]);
  const [idsInput, setIdsInput] = useState("");
  const [createRole, setCreateRole] = useState("viewer");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [setPasswordUser, setSetPasswordUser] = useState("");
  const [setPasswordValue, setSetPasswordValue] = useState("");
  const [setPasswordConfirm, setSetPasswordConfirm] = useState("");
  const [resetRequests, setResetRequests] = useState([]);
  const [resetTokens, setResetTokens] = useState({});
  const [tokenCopied, setTokenCopied] = useState("");
  const [subjectUsage, setSubjectUsage] = useState([]);
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deletePassword, setDeletePassword] = useState("");
  const [auditEntries, setAuditEntries] = useState([]);
  const [assignTarget, setAssignTarget] = useState("");
  const selfId = getUserSubject();

  const countOf = (value) => (Array.isArray(value) ? value.length : (value ?? 0));
  const quotaLabel = (quota) => (quota === null || quota === undefined ? "默认" : quota === 0 ? "不限" : String(quota));
  const roleLabel = (user) => {
    const userRoles = user.roles || [];
    if (userRoles.includes("leader")) return "组长";
    if (userRoles.includes("admin")) return "老师";
    if (userRoles.includes("operator")) return "助教";
    return "学生";
  };

  function reloadUsers() {
    // 接口失败（如令牌过期触发 401）时保留现有列表，由登录遮罩接管。
    return listUsers()
      .then((data) => { setUsers(Array.isArray(data) ? data : (data.users || [])); })
      .catch(() => {});
  }

  function reloadResetRequests() {
    // 接口不可用时静默降级为空列表。
    return listPasswordResetRequests()
      .then((data) => setResetRequests(Array.isArray(data) ? data : (data.requests || [])))
      .catch(() => setResetRequests([]));
  }

  function reloadAudit() {
    // 删除记录（审计表）：leader 看全部，老师只看自己的操作；失败静默为空。
    return listAuditLog()
      .then((data) => setAuditEntries(Array.isArray(data) ? data : (data.entries || [])))
      .catch(() => setAuditEntries([]));
  }

  useEffect(() => {
    // 用户管理接口仅 admin/leader 可用：operator 进入时只渲染成绩卡片，不打这些接口。
    if (!canManage) return;
    reloadUsers();
    reloadResetRequests();
    reloadAudit();
    // 全部 subject 的配额与累计用量（管理角色专属接口；失败静默为空表）。
    getSubjectUsage()
      .then((data) => setSubjectUsage(data.subjects || []))
      .catch(() => setSubjectUsage([]));
  }, [canManage]);

  async function submitCreate(event) {
    event.preventDefault();
    const ids = idsInput.split(/\s+/).map((item) => item.trim()).filter(Boolean);
    if (!ids.length || busy) return;
    setBusy(true);
    setNotice("");
    try {
      const result = await createUsers(ids, [createRole]);
      setNotice(`已创建 ${countOf(result.created)} 个${createRole === "admin" ? "老师" : "学生"}账号，跳过 ${countOf(result.skipped)} 个（初始密码为学号）。`);
      setIdsInput("");
      await reloadUsers();
    } catch {
      setNotice("创建失败，请检查学号格式（U + 11 位数字）。");
    } finally {
      setBusy(false);
    }
  }

  async function handleResetPassword(userId) {
    if (busy) return;
    setBusy(true);
    setNotice("");
    try {
      await resetUserPassword(userId);
      setNotice(`已重置 ${userId} 的密码（初始密码为学号）。`);
    } catch {
      setNotice("重置密码失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  }

  async function handleToggleStatus(user) {
    if (busy) return;
    setBusy(true);
    setNotice("");
    try {
      await setUserStatus(user.id, !user.enabled);
      await reloadUsers();
    } catch {
      setNotice("状态更新失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  }

  function toggleSetPassword(userId) {
    setSetPasswordUser((prev) => (prev === userId ? "" : userId));
    setSetPasswordValue("");
    setSetPasswordConfirm("");
  }

  function toggleSelect(userId) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(userId)) next.delete(userId);
      else next.add(userId);
      return next;
    });
  }

  // 批量删除：每次操作都要输入自己的密码，后端校验失败返回 403。
  async function submitDelete(event) {
    event.preventDefault();
    if (busy || !selectedIds.size || !deletePassword) return;
    setBusy(true);
    setNotice("");
    try {
      const result = await deleteUsers([...selectedIds], deletePassword);
      setNotice(`已删除 ${countOf(result.deleted)} 个账号，跳过 ${countOf(result.skipped)} 个（不存在、不可管理或为自己）。`);
      setSelectedIds(new Set());
      setDeleteOpen(false);
      setDeletePassword("");
      await reloadUsers();
      await reloadAudit();
    } catch (err) {
      setNotice(err && err.status === 403 ? "密码不正确，删除未执行。" : "删除失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  }

  // 组长把勾选的未分配学生批量指派给目标老师。
  async function submitAssign() {
    const targets = orphanStudents.filter((user) => selectedIds.has(user.id)).map((user) => user.id);
    if (busy || !assignTarget || !targets.length) return;
    setBusy(true);
    setNotice("");
    try {
      const result = await assignStudents(targets, assignTarget);
      setNotice(`已把 ${countOf(result.assigned)} 个学生分配给 ${assignTarget}，跳过 ${countOf(result.skipped)} 个。`);
      setSelectedIds(new Set());
      await reloadUsers();
    } catch {
      setNotice("分配失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  }

  async function submitSetPassword(event, userId) {
    event.preventDefault();
    if (busy) return;
    if (setPasswordValue !== setPasswordConfirm) {
      setNotice("两次输入的新密码不一致。");
      return;
    }
    setBusy(true);
    setNotice("");
    try {
      await setUserPassword(userId, setPasswordValue);
      setNotice(`已设置 ${userId} 的密码，用户下次登录需修改密码。`);
      setSetPasswordUser("");
      setSetPasswordValue("");
      setSetPasswordConfirm("");
      await reloadUsers();
    } catch {
      setNotice("设置失败：密码不符合要求（长度过短，或与学号、旧密码相同）。");
    } finally {
      setBusy(false);
    }
  }

  async function handleApproveReset(requestId) {
    if (busy) return;
    setBusy(true);
    setNotice("");
    try {
      // 明文令牌仅这一次返回，就地展示给管理员线下转交。
      const data = await approvePasswordResetRequest(requestId);
      setResetTokens((prev) => ({ ...prev, [requestId]: { token: data.reset_token, expires_at: data.expires_at } }));
    } catch {
      setNotice("审批失败：申请不存在或已被处理。");
    } finally {
      setBusy(false);
    }
  }

  async function handleRejectReset(requestId) {
    if (busy) return;
    setBusy(true);
    setNotice("");
    try {
      await rejectPasswordResetRequest(requestId);
      setResetRequests((prev) => prev.filter((item) => item.id !== requestId));
    } catch {
      setNotice("驳回失败：申请不存在或已被处理。");
    } finally {
      setBusy(false);
    }
  }

  async function copyResetToken(requestId, token) {
    try {
      await navigator.clipboard.writeText(token);
      setTokenCopied(requestId);
      window.setTimeout(() => setTokenCopied(""), 1600);
    } catch {
      // Clipboard can be disabled by browser policy; the token remains selectable.
    }
  }

  // 树形分组：组长 → 老师 → 学生（按 created_by 归属）。老师视角后端已按树隔离
  // 返回（自己 + 自己的学生），直接平铺即可。
  const isLeaderUser = (user) => (user.roles || []).includes("leader");
  const isTeacherUser = (user) => (user.roles || []).includes("admin") && !isLeaderUser(user);
  const leaders = users.filter(isLeaderUser);
  const teachers = users.filter(isTeacherUser);
  const students = users.filter((user) => !isLeaderUser(user) && !isTeacherUser(user));
  const ownerIds = new Set([...leaders, ...teachers].map((user) => user.id));
  const orphanStudents = students.filter((user) => !user.created_by || !ownerIds.has(user.created_by));
  const studentsOf = (ownerId) => students.filter((user) => user.created_by === ownerId);

  function renderUserRow(user, { child = false } = {}) {
    return (
      <React.Fragment key={user.id}>
        <tr className={child ? "admin-tree-child" : ""}>
          <td className="admin-select">
            {user.id !== selfId && (
              <input type="checkbox" checked={selectedIds.has(user.id)}
                onChange={() => toggleSelect(user.id)} aria-label={`选择 ${user.id}`} />
            )}
          </td>
          <td>{user.id}{user.must_change_password && <span className="admin-flag">待改密</span>}</td>
          <td>{roleLabel(user)}</td>
          <td>{user.created_by || "—"}</td>
          <td>{quotaLabel(user.daily_quota)}</td>
          <td>{user.enabled ? "启用" : "停用"}</td>
          <td>
            <button type="button" onClick={() => toggleSetPassword(user.id)} disabled={busy}>设置密码</button>
            <button type="button" onClick={() => handleResetPassword(user.id)} disabled={busy}>重置密码</button>
            <button type="button" onClick={() => handleToggleStatus(user)} disabled={busy}>{user.enabled ? "停用" : "启用"}</button>
          </td>
        </tr>
        {setPasswordUser === user.id && (
          <tr>
            <td colSpan={7}>
              <form className="admin-inline" onSubmit={(event) => submitSetPassword(event, user.id)}>
                <PasswordInput value={setPasswordValue}
                  onChange={(event) => setSetPasswordValue(event.target.value)}
                  placeholder="新密码" autoComplete="new-password" />
                <PasswordInput value={setPasswordConfirm}
                  onChange={(event) => setSetPasswordConfirm(event.target.value)}
                  placeholder="确认新密码" autoComplete="new-password" />
                <div className="admin-inline-actions">
                  <button type="submit" disabled={!setPasswordValue || !setPasswordConfirm || busy}>确认</button>
                  <button type="button" className="ghost" onClick={() => setSetPasswordUser("")} disabled={busy}>取消</button>
                </div>
              </form>
            </td>
          </tr>
        )}
      </React.Fragment>
    );
  }

  return (
    <main className="admin-page">
      <header className="admin-page-header">
        <div><div className="eyebrow">管理后台</div><h1>用户管理</h1></div>
        <button type="button" className="admin-back" onClick={onBack}>
          <ArrowLeft size={15} /> 返回对话
        </button>
      </header>

      {notice && <p className="admin-page-notice">{notice}</p>}

      {canManage && (
      <div className="admin-page-grid">
        <section className="admin-card">
          <h2>批量创建用户</h2>
          <form className="admin-create" onSubmit={submitCreate}>
            {isLeader && (
              <select className="admin-role-select" value={createRole}
                onChange={(event) => setCreateRole(event.target.value)}>
                <option value="viewer">学生（普通用户）</option>
                <option value="admin">老师（管理员）</option>
              </select>
            )}
            <textarea rows={5} value={idsInput} onChange={(event) => setIdsInput(event.target.value)}
              placeholder={"每行一个学号\n如 U20260000001"} />
            <button type="submit" disabled={!idsInput.trim() || busy}>
              {busy ? "处理中" : (isLeader ? "创建用户" : "创建学生")}
            </button>
          </form>
        </section>

        <section className="admin-card">
          <h2>改密申请{resetRequests.length ? `（${resetRequests.length}）` : ""}</h2>
          <div className="reset-request-list">
            {resetRequests.length === 0 && <small className="admin-notice">暂无待审批的改密申请</small>}
            {resetRequests.map((item) => (
              <div className="reset-request-row" key={item.id}>
                <div className="reset-request-meta">
                  <b>{item.user_id}</b>
                  <small>{formatSessionTime(item.created_at)}</small>
                </div>
                {resetTokens[item.id] ? (
                  <div className="reset-token">
                    <code>{resetTokens[item.id].token}</code>
                    <small>30 分钟内有效，仅显示这一次，请线下转交学生。</small>
                    <button type="button" onClick={() => copyResetToken(item.id, resetTokens[item.id].token)}>
                      {tokenCopied === item.id ? <Check size={12} /> : <Copy size={12} />}
                      {tokenCopied === item.id ? "已复制" : "复制令牌"}
                    </button>
                  </div>
                ) : (
                  <div className="reset-request-actions">
                    <button type="button" onClick={() => handleApproveReset(item.id)} disabled={busy}>通过</button>
                    <button type="button" className="reject" onClick={() => handleRejectReset(item.id)} disabled={busy}>驳回</button>
                  </div>
                )}
              </div>
            ))}
          </div>
        </section>
      </div>
      )}

      {canManage && (
      <section className="admin-card">
        <h2>用户列表{users.length ? `（${users.length}）` : ""}</h2>
        <div className="admin-batch-bar">
          <button type="button" className="danger" disabled={!selectedIds.size || busy}
            onClick={() => { setDeleteOpen((prev) => !prev); setDeletePassword(""); }}>
            <Trash2 size={13} /> 删除选中{selectedIds.size ? `（${selectedIds.size}）` : ""}
          </button>
        </div>
        {deleteOpen && (
          <form className="admin-inline admin-delete-confirm" onSubmit={submitDelete}>
            <PasswordInput value={deletePassword}
              onChange={(event) => setDeletePassword(event.target.value)}
              placeholder="输入你的密码确认删除" autoComplete="current-password" />
            <div className="admin-inline-actions">
              <button type="submit" className="danger" disabled={!deletePassword || busy}>确认删除（不可恢复）</button>
              <button type="button" className="ghost" onClick={() => setDeleteOpen(false)} disabled={busy}>取消</button>
            </div>
          </form>
        )}
        <table className="admin-page-table">
          <thead>
            <tr><th></th><th>学号</th><th>角色</th><th>归属</th><th>配额</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            {isLeader ? (
              <>
                {leaders.map((user) => renderUserRow(user))}
                {teachers.map((teacher) => (
                  <React.Fragment key={teacher.id}>
                    {renderUserRow(teacher)}
                    {studentsOf(teacher.id).map((student) => renderUserRow(student, { child: true }))}
                  </React.Fragment>
                ))}
                {orphanStudents.length > 0 && (
                  <>
                    <tr className="admin-tree-group">
                      <td colSpan={7}>
                        未分配学生（{orphanStudents.length}）
                        {isLeader && teachers.length > 0 && (
                          <span className="admin-assign">
                            <select value={assignTarget} onChange={(event) => setAssignTarget(event.target.value)}>
                              <option value="">选择老师…</option>
                              {teachers.map((teacher) => (
                                <option key={teacher.id} value={teacher.id}>{teacher.id}</option>
                              ))}
                            </select>
                            <button type="button"
                              disabled={!assignTarget || !orphanStudents.some((user) => selectedIds.has(user.id)) || busy}
                              onClick={submitAssign}>分配选中</button>
                          </span>
                        )}
                      </td>
                    </tr>
                    {orphanStudents.map((user) => renderUserRow(user, { child: true }))}
                  </>
                )}
              </>
            ) : (
              users.map((user) => renderUserRow(user))
            )}
          </tbody>
        </table>
      </section>
      )}

      {canManage && (
      <section className="admin-card">
        <h2>删除记录{auditEntries.length ? `（${auditEntries.length}）` : ""}</h2>
        {auditEntries.length === 0 ? (
          <small className="admin-notice">暂无删除记录</small>
        ) : (
          <table className="admin-page-table">
            <thead>
              <tr><th>时间</th><th>操作者</th><th>删除的账号</th></tr>
            </thead>
            <tbody>
              {auditEntries.map((entry) => (
                <tr key={entry.id}>
                  <td>{formatSessionTime(entry.created_at)}</td>
                  <td>{entry.actor}</td>
                  <td>{(entry.targets || []).join("、")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      )}

      {canManage && (
      <section className="admin-card">
        <h2>用量统计{subjectUsage.length ? `（${subjectUsage.length}）` : ""}</h2>
        {subjectUsage.length === 0 ? (
          <small className="admin-notice">暂无用量数据</small>
        ) : (
          <table className="admin-page-table">
            <thead>
              <tr><th>主体</th><th>今日</th><th>总计</th><th>配额</th><th>最后活跃</th></tr>
            </thead>
            <tbody>
              {subjectUsage.map((item) => (
                <tr key={item.subject}>
                  <td>{item.subject}</td>
                  <td>{item.today_count}</td>
                  <td>{item.total_count}</td>
                  <td>{quotaLabel(item.daily_quota)}</td>
                  <td>{item.last_active_day || "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      )}

    </main>
  );
}

// datetime-local ↔ ISO 转换：表单控件需要本地时间的 "YYYY-MM-DDTHH:mm"，
// 提交给后端时转成带时区的 ISO 串；无效输入返回空串由必填校验拦截。
function deadlineToInput(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function deadlineToIso(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : date.toISOString();
}

const EMPTY_ASSIGNMENT_FORM = { title: "", description: "", deadline: "", items: [{ protocol: "", points: 20 }] };

// 作业管理页（admin/leader 可发布/编辑/撤回；operator 只见成绩区+导出）。
// 所有接口失败按惯例静默降级为空态，不弹错误。
function AssignmentsPage({ onBack, roles }) {
  const canManage = roles.includes("admin") || roles.includes("leader");
  const [assignments, setAssignments] = useState([]);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [editingId, setEditingId] = useState("");
  const [form, setForm] = useState(EMPTY_ASSIGNMENT_FORM);
  const [protocols, setProtocols] = useState([]);
  const [protocolsHint, setProtocolsHint] = useState("");
  const [gradesId, setGradesId] = useState("");
  const [grades, setGrades] = useState(null);
  const [filterUser, setFilterUser] = useState("");

  function reloadAssignments() {
    return listAssignments()
      .then((data) => setAssignments(Array.isArray(data) ? data : (data?.assignments || [])))
      .catch(() => setAssignments([]));
  }

  useEffect(() => {
    reloadAssignments();
    if (!canManage) return;
    // 协议下拉数据源；harness 不可达时提示并允许手动输入（datalist 天然支持手输）。
    listAssignmentProtocols()
      .then((data) => {
        setProtocols(Array.isArray(data?.protocols) ? data.protocols : []);
        setProtocolsHint(data?.error ? "协议列表暂不可用，可手动输入协议名。" : "");
      })
      .catch(() => {
        setProtocols([]);
        setProtocolsHint("协议列表暂不可用，可手动输入协议名。");
      });
  }, [canManage]);

  // 选中作业后拉取成绩表；切作业/失败时回到空态。
  useEffect(() => {
    if (!gradesId) { setGrades(null); return undefined; }
    let cancelled = false;
    getAssignmentGrades(gradesId)
      .then((data) => { if (!cancelled) setGrades(data || null); })
      .catch(() => { if (!cancelled) setGrades(null); });
    return () => { cancelled = true; };
  }, [gradesId]);

  function resetForm() {
    setEditingId("");
    setForm(EMPTY_ASSIGNMENT_FORM);
  }

  function startEdit(assignment) {
    setEditingId(assignment.id);
    setForm({
      title: assignment.title || "",
      description: assignment.description || "",
      deadline: deadlineToInput(assignment.deadline),
      items: (assignment.items || []).map((item) => ({ protocol: item.protocol || "", points: item.points ?? 20 }))
    });
    setNotice("");
  }

  function updateItem(index, patch) {
    setForm((prev) => ({
      ...prev,
      items: prev.items.map((item, at) => (at === index ? { ...item, ...patch } : item))
    }));
  }

  const formValid = form.title.trim() && form.deadline &&
    form.items.length > 0 && form.items.every((item) => item.protocol.trim() && Number(item.points) >= 1);

  async function submitForm(event) {
    event.preventDefault();
    if (!formValid || busy) return;
    setBusy(true);
    setNotice("");
    const payload = {
      title: form.title.trim(),
      description: form.description,
      deadline: deadlineToIso(form.deadline),
      items: form.items.map((item) => ({ protocol: item.protocol.trim(), points: Number(item.points) }))
    };
    try {
      if (editingId) {
        await updateAssignment(editingId, payload);
        setNotice("作业已更新。");
      } else {
        await createAssignment(payload);
        setNotice("作业已发布。");
      }
      resetForm();
      await reloadAssignments();
    } catch {
      setNotice(editingId ? "更新失败，请稍后重试。" : "发布失败，请检查协议项与截止时间。");
    } finally {
      setBusy(false);
    }
  }

  async function handleStatus(assignment) {
    if (busy) return;
    setBusy(true);
    setNotice("");
    const action = assignment.status === "withdrawn" ? "reactivate" : "withdraw";
    try {
      await setAssignmentStatus(assignment.id, action);
      await reloadAssignments();
    } catch {
      setNotice("操作失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  }

  async function handleExport(assignmentId) {
    try {
      await exportAssignmentGrades(assignmentId);
    } catch {
      setNotice("导出失败，请稍后重试。");
    }
  }

  // 成绩表宽松渲染：字段缺失/非数值统一 "—"（取值表达式集中在 ./grades，与后端契约同锁）。
  const gradeItems = gradeItemsOf(grades);
  const userNeedle = filterUser.trim().toLowerCase();
  const visibleRows = (grades?.rows || []).filter((row) =>
    !userNeedle || String(row.user_id ?? "").toLowerCase().includes(userNeedle));

  return (
    <main className="admin-page">
      <header className="admin-page-header">
        <div><div className="eyebrow">教学管理</div><h1>作业管理</h1></div>
        <button type="button" className="admin-back" onClick={onBack}>
          <ArrowLeft size={15} /> 返回对话
        </button>
      </header>

      {notice && <p className="admin-page-notice">{notice}</p>}

      {canManage && (
      <div className="admin-page-grid">
        <section className="admin-card">
          <h2>作业列表{assignments.length ? `（${assignments.length}）` : ""}</h2>
          {assignments.length === 0 ? (
            <small className="admin-notice">暂无作业</small>
          ) : (
            <table className="admin-page-table">
              <thead>
                <tr><th>标题</th><th>状态</th><th>截止时间</th><th>提交人数</th><th>操作</th></tr>
              </thead>
              <tbody>
                {assignments.map((assignment) => (
                  <tr key={assignment.id}>
                    <td>{assignment.title}</td>
                    <td>{assignment.status === "withdrawn" ? "已撤回" : "进行中"}</td>
                    <td>{assignment.deadline ? formatSessionTime(assignment.deadline) : "—"}</td>
                    <td>{assignment.submission_count ?? "—"}</td>
                    <td>
                      <button type="button" onClick={() => startEdit(assignment)} disabled={busy}>编辑</button>
                      <button type="button" onClick={() => handleStatus(assignment)} disabled={busy}>
                        {assignment.status === "withdrawn" ? "恢复" : "撤回"}
                      </button>
                      <button type="button" onClick={() => setGradesId(assignment.id)}>成绩</button>
                      <button type="button" onClick={() => handleExport(assignment.id)}>导出</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="admin-card">
          <h2>{editingId ? "编辑作业" : "发布作业"}</h2>
          <form className="admin-create" onSubmit={submitForm}>
            <input value={form.title} onChange={(event) => setForm((prev) => ({ ...prev, title: event.target.value }))}
              placeholder="作业标题" aria-label="作业标题" />
            <textarea rows={4} value={form.description}
              onChange={(event) => setForm((prev) => ({ ...prev, description: event.target.value }))}
              placeholder="作业描述（纯文本）" />
            <label className="assignment-field-label">截止时间
              <input type="datetime-local" value={form.deadline}
                onChange={(event) => setForm((prev) => ({ ...prev, deadline: event.target.value }))} />
            </label>
            <div className="assignment-items">
              {form.items.map((item, index) => (
                <div className="assignment-item-row" key={index}>
                  <input list="assignment-protocol-options" value={item.protocol}
                    onChange={(event) => updateItem(index, { protocol: event.target.value })}
                    placeholder="协议（如 dns）" aria-label={`协议 ${index + 1}`} />
                  <input type="number" min="1" max="1000" value={item.points}
                    onChange={(event) => updateItem(index, { points: event.target.value })}
                    aria-label={`分值 ${index + 1}`} title="分值" />
                  <button type="button" className="ghost" onClick={() =>
                    setForm((prev) => ({ ...prev, items: prev.items.filter((_, at) => at !== index) }))}
                    disabled={form.items.length <= 1} aria-label="删除该协议项"><X size={13} /></button>
                </div>
              ))}
              <datalist id="assignment-protocol-options">
                {protocols.map((item) => (
                  <option key={item.name} value={item.name}>
                    {item.cases !== undefined && item.cases !== null ? `${item.cases} 个用例` : ""}
                  </option>
                ))}
              </datalist>
              {protocolsHint && <small className="admin-notice">{protocolsHint}</small>}
              <button type="button" className="assignment-add-item" onClick={() =>
                setForm((prev) => ({ ...prev, items: [...prev.items, { protocol: "", points: 20 }] }))}>
                添加协议项
              </button>
            </div>
            <div className="admin-inline-actions">
              <button type="submit" disabled={!formValid || busy}>{busy ? "处理中" : editingId ? "保存修改" : "发布作业"}</button>
              {editingId && (
                <button type="button" className="ghost" onClick={resetForm} disabled={busy}>取消编辑</button>
              )}
            </div>
          </form>
        </section>
      </div>
      )}

      <section className="admin-card">
        <h2>成绩总览</h2>
        <div className="admin-inline">
          <select value={gradesId} onChange={(event) => setGradesId(event.target.value)} aria-label="选择作业">
            <option value="">选择作业…</option>
            {assignments.map((assignment) => (
              <option key={assignment.id} value={assignment.id}>{assignment.title}</option>
            ))}
          </select>
          <input value={filterUser} onChange={(event) => setFilterUser(event.target.value)}
            placeholder="按学号筛选" />
          {gradesId && (
            <button type="button" onClick={() => handleExport(gradesId)}>
              <Download size={13} /> 导出 CSV
            </button>
          )}
        </div>
        {!gradesId ? (
          <small className="admin-notice">选择一份作业查看成绩</small>
        ) : !grades ? (
          <small className="admin-notice">暂无成绩数据</small>
        ) : visibleRows.length === 0 ? (
          <small className="admin-notice">暂无提交记录</small>
        ) : (
          <table className="admin-page-table">
            <thead>
              <tr>
                <th>学号</th><th>班级</th>
                {gradeItems.map((item) => <th key={item.protocol}>{item.protocol}（{item.points ?? "—"}分）</th>)}
                <th>总分</th><th>提交次数</th><th>最近提交</th>
              </tr>
            </thead>
            <tbody>
              {visibleRows.map((row) => (
                <tr key={row.user_id}>
                  <td>{row.user_id}</td>
                  <td>{classOf(row)}</td>
                  {gradeItems.map((item) => (
                    <td key={item.protocol}>{formatScore(protocolScoreOf(row, item.protocol))}</td>
                  ))}
                  <td>{formatScore(row.total_earned)} / {formatScore(totalPointsOf(gradeItems))}</td>
                  <td>{row.submission_count ?? "—"}</td>
                  <td>{row.last_submitted_at ? formatSessionTime(row.last_submitted_at) : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}

// 学生作业提交页：作业卡片列表 → 点入详情 → 选协议 → 粘贴代码或上传 .c/.so → 提交看结果。
// onExplain(assignmentId, submissionId)：S3-d 讲解入口，由 App 接管视图切换与事件流。
function HomeworkPage({ onBack, onExplain }) {
  const [assignments, setAssignments] = useState([]);
  const [current, setCurrent] = useState(null);
  const [protocol, setProtocol] = useState("");
  const [code, setCode] = useState("");
  const [file, setFile] = useState(null);
  const [submitError, setSubmitError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState(null);
  const [explaining, setExplaining] = useState(false);
  const [explainError, setExplainError] = useState("");
  const fileInputRef = useRef(null);

  function reloadAssignments() {
    // 接口失败（含非 account 模式 404）时静默降级为空列表。
    return listAssignments()
      .then((data) => setAssignments(Array.isArray(data) ? data : (data?.assignments || [])))
      .catch(() => setAssignments([]));
  }

  useEffect(() => { reloadAssignments(); }, []);

  async function openAssignment(assignment) {
    setCurrent(assignment);
    setProtocol("");
    setCode("");
    setFile(null);
    setSubmitError("");
    setResult(null);
    setExplainError("");
    // 详情接口失败时退回列表里的概要数据，不阻塞提交入口。
    try {
      const detail = await getAssignment(assignment.id);
      if (detail) setCurrent(detail);
    } catch {}
  }

  function handleFilePick(event) {
    const picked = event.target.files?.[0];
    // 清空 input value，允许再次选择同一个文件时仍触发 change。
    event.target.value = "";
    if (!picked) return;
    const error = validateAttachment(picked);
    if (error) { setSubmitError(error); return; }
    setSubmitError("");
    setFile(picked);
  }

  const overdue = current?.deadline ? new Date(current.deadline).getTime() < Date.now() : false;

  async function submit(event) {
    event.preventDefault();
    if (!protocol || submitting || overdue) return;
    if (!file && !code.trim()) { setSubmitError("请粘贴代码或上传 .c / .so 文件。"); return; }
    setSubmitting(true);
    setSubmitError("");
    setExplainError("");
    setResult(null);
    try {
      const data = await submitAssignment(current.id, { protocol, code: file ? "" : code }, file);
      setResult(data);
      await reloadAssignments();
    } catch (err) {
      setSubmitError(err?.status === 429 ? rateLimitMessage(err)
        : err?.status === 413 ? "代码超过大小限制，请精简后再试。"
        : "提交失败：作业可能已截止或撤回，请稍后重试。");
    } finally {
      setSubmitting(false);
    }
  }

  // S3-d 讲解入口：把本次提交交给助教讲解。成功后 App 切到聊天视图接管，
  // 本组件随视图卸载；失败就地提示（404 = 作业不可见/非本人提交）。
  async function handleExplain() {
    if (!onExplain || !result?.submission_id || explaining) return;
    setExplaining(true);
    setExplainError("");
    try {
      await onExplain(current.id, result.submission_id);
    } catch (err) {
      setExplainError(err?.status === 404 ? "该提交不可讲解。"
        : err?.status === 429 ? rateLimitMessage(err)
        : "讲解暂时不可用，请稍后重试。");
    } finally {
      setExplaining(false);
    }
  }

  if (!current) {
    return (
      <main className="admin-page">
        <header className="admin-page-header">
          <div><div className="eyebrow">学习中心</div><h1>作业提交</h1></div>
          <button type="button" className="admin-back" onClick={onBack}>
            <ArrowLeft size={15} /> 返回对话
          </button>
        </header>
        {assignments.length === 0 ? (
          <small className="admin-notice">暂无进行中的作业</small>
        ) : (
          <div className="homework-grid">
            {assignments.map((assignment) => (
              <button type="button" className="homework-card" key={assignment.id}
                onClick={() => openAssignment(assignment)}>
                <div className="homework-card-head">
                  <b>{assignment.title}</b>
                  <small>截止 {assignment.deadline ? formatSessionTime(assignment.deadline) : "—"}</small>
                </div>
                <div className="homework-card-items">
                  {(assignment.items || []).map((item) => (
                    <span key={item.protocol}>{item.protocol} · {item.points ?? "—"}分</span>
                  ))}
                </div>
                <div className="homework-card-score">
                  我的得分 {studentTotalEarned(assignment)} / {studentTotalPoints(assignment) ?? "—"}
                </div>
              </button>
            ))}
          </div>
        )}
      </main>
    );
  }

  return (
    <main className="admin-page">
      <header className="admin-page-header">
        <div>
          <div className="eyebrow">作业提交</div>
          <h1>{current.title}</h1>
        </div>
        <button type="button" className="admin-back" onClick={() => { setCurrent(null); setResult(null); }}>
          <ArrowLeft size={15} /> 返回列表
        </button>
      </header>

      <section className="admin-card">
        <h2>作业详情</h2>
        <p className="homework-meta">截止 {current.deadline ? formatSessionTime(current.deadline) : "—"}{overdue && "（已截止）"}</p>
        {current.description && <p className="homework-desc">{current.description}</p>}
        <table className="admin-page-table">
          <thead>
            <tr><th>协议</th><th>分值</th><th>我的最高分</th><th>折算得分</th><th>提交次数</th></tr>
          </thead>
          <tbody>
            {studentItemsOf(current).map((item) => (
              <tr key={item.protocol}>
                <td>{item.protocol}</td>
                <td>{item.points ?? "—"}</td>
                {/* W4：未提交协议两列统一显示「未提交」（判定在 grades.js）。 */}
                <td>{itemBestLabel(item)}</td>
                <td>{itemEarnedLabel(item)}</td>
                <td>{item.submission_count ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="admin-card">
        <h2>提交代码</h2>
        <form className="admin-create" onSubmit={submit}>
          <select value={protocol} onChange={(event) => setProtocol(event.target.value)} aria-label="选择协议">
            <option value="">选择协议…</option>
            {studentItemsOf(current).map((item) => (
              <option key={item.protocol} value={item.protocol}>{item.protocol}（{item.points ?? "—"}分）</option>
            ))}
          </select>
          {!file && (
            <textarea rows={10} value={code} onChange={(event) => setCode(event.target.value)}
              placeholder="粘贴插件源码，或改为上传 .c / .so 文件" />
          )}
          {file && (
            <div className="attachment-chips">
              <span className="attachment-chip">
                <FileCode2 size={13} /><b>{file.name}</b><small>{formatFileSize(file.size)}</small>
                <button type="button" onClick={() => setFile(null)} aria-label="移除附件"><X size={12} /></button>
              </span>
            </div>
          )}
          <div className="admin-inline-actions">
            <input ref={fileInputRef} type="file" accept=".c,.so" hidden onChange={handleFilePick} aria-label="选择代码文件" />
            <button type="button" className="ghost" onClick={() => fileInputRef.current?.click()} disabled={submitting}>
              <Paperclip size={13} /> 上传 .c / .so
            </button>
            <button type="submit" disabled={!protocol || submitting || overdue}>
              {submitting ? "评测中" : overdue ? "已截止" : "提交评测"}
            </button>
          </div>
          {submitError && <small className="auth-error">{submitError}</small>}
        </form>
      </section>

      {result && (
        <section className="admin-card">
          <h2>评测结果</h2>
          <p className="homework-meta">
            得分 <b>{result.score ?? "—"}</b>（用例 {result.passed ?? "—"}/{result.total ?? "—"}）
            {result.earned !== undefined && result.earned !== null && <> · 折算 {result.earned} 分</>}
            {result.best_earned !== undefined && result.best_earned !== null && <> · 历史最高 {result.best_earned} 分</>}
          </p>
          {result.submission_id && onExplain && (
            <div className="homework-explain">
              <button type="button" onClick={handleExplain} disabled={explaining}>
                <Bot size={13} /> {explaining ? "正在打开讲解…" : "讲解本次结果"}
              </button>
              {explainError && <small className="auth-error">{explainError}</small>}
            </div>
          )}
          {(result.results || []).length > 0 && (
            <table className="admin-page-table">
              <thead>
                <tr><th>用例</th><th>结果</th></tr>
              </thead>
              <tbody>
                {result.results.map((item, index) => (
                  <tr key={item.name || item.case || index}>
                    <td>{item.name || item.case || `用例 ${index + 1}`}</td>
                    <td>{item.passed ? "通过" : `未通过${item.detail ? `：${item.detail}` : ""}`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </main>
  );
}

function SourcePanel({ source, onClose }) {
  if (!source) return null;
  return (
    <aside className="source-drawer">
      <div className="drawer-heading">
        <div><span>REFERENCE</span><h2>{source.title || "项目来源"}</h2></div>
        <button className="icon-button" onClick={onClose} aria-label="关闭引用"><X size={18} /></button>
      </div>
      <div className="source-path"><TerminalSquare size={14} />{source.source_path}</div>
      <pre>{source.content}</pre>
    </aside>
  );
}

function AuthBrand() {
  return (
    <div className="brand auth-brand">
      <div className="brand-mark"><Sparkles size={19} /></div>
      <div><strong>FlowSense</strong><span>项目知识工作台</span></div>
    </div>
  );
}

// 忘记密码页：①提交申请（统一 202 口径）②管理员已批准后凭令牌自助改密。
function ForgotPasswordScreen({ onBack }) {
  const [resetUsername, setResetUsername] = useState("");
  const [requestLoading, setRequestLoading] = useState(false);
  const [requestMessage, setRequestMessage] = useState("");
  const [requestError, setRequestError] = useState("");
  const [tokenInput, setTokenInput] = useState("");
  const [resetPassword, setResetPassword] = useState("");
  const [resetConfirm, setResetConfirm] = useState("");
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [confirmError, setConfirmError] = useState("");

  async function submitRequest(event) {
    event.preventDefault();
    const username = resetUsername.trim();
    if (!username || requestLoading) return;
    setRequestLoading(true);
    setRequestMessage("");
    setRequestError("");
    try {
      await requestPasswordReset(username);
      // 后端统一 202，不区分学号是否存在，提示口径保持一致。
      setRequestMessage("申请已提交，请等待管理员审批。");
      setResetUsername("");
    } catch (err) {
      setRequestError(err?.status === 429 ? rateLimitMessage(err)
        : err?.status === 400 ? "学号格式不正确。" : "提交失败，请稍后重试。");
    } finally {
      setRequestLoading(false);
    }
  }

  async function submitConfirm(event) {
    event.preventDefault();
    if (confirmLoading) return;
    if (resetPassword !== resetConfirm) {
      setConfirmError("两次输入的新密码不一致。");
      return;
    }
    setConfirmLoading(true);
    setConfirmError("");
    try {
      await confirmPasswordReset(tokenInput.trim(), resetPassword);
      onBack("密码已重置，请使用新密码登录。");
    } catch (err) {
      setConfirmError(err?.status === 429 ? rateLimitMessage(err) : "重置失败：令牌无效或已过期，请确认后重试。");
    } finally {
      setConfirmLoading(false);
    }
  }

  return (
    <div className="auth-form">
      <h2 className="auth-title">忘记密码</h2>
      <p className="auth-subtitle">提交申请后由管理员审批，拿到一次性重置令牌后即可自助设置新密码。</p>
      <div className="auth-section-label">提交申请</div>
      <form className="auth-form" onSubmit={submitRequest}>
        <input value={resetUsername} onChange={(event) => setResetUsername(event.target.value)}
          placeholder="学号（如 U20260000001）" autoComplete="username" />
        <button className="auth-submit" type="submit" disabled={!resetUsername.trim() || requestLoading}>
          {requestLoading ? "提交中" : "提交申请"}
        </button>
        {requestMessage && <p className="auth-ok">{requestMessage}</p>}
        {requestError && <p className="auth-error">{requestError}</p>}
      </form>
      <div className="auth-section-label">管理员已批准</div>
      <form className="auth-form" onSubmit={submitConfirm}>
        <input value={tokenInput} onChange={(event) => setTokenInput(event.target.value)}
          placeholder="重置令牌" autoComplete="off" />
        <PasswordInput value={resetPassword} onChange={(event) => setResetPassword(event.target.value)}
          placeholder="新密码" autoComplete="new-password" />
        <PasswordInput value={resetConfirm} onChange={(event) => setResetConfirm(event.target.value)}
          placeholder="确认新密码" autoComplete="new-password" />
        <button className="auth-submit" type="submit"
          disabled={!tokenInput.trim() || !resetPassword || !resetConfirm || confirmLoading}>
          {confirmLoading ? "提交中" : "重置密码"}
        </button>
        {confirmError && <p className="auth-error">{confirmError}</p>}
      </form>
      <div className="auth-actions">
        <button type="button" className="auth-link" onClick={() => onBack("")}>返回登录</button>
      </div>
    </div>
  );
}

// 全屏登录页：authRequired 时遮住主界面，页内切换到忘记密码。
function LoginScreen({ username, password, error, loading, onUsernameChange, onPasswordChange, onSubmit }) {
  const [view, setView] = useState("login");
  const [notice, setNotice] = useState("");

  function handleBack(message) {
    setView("login");
    setNotice(message || "");
  }

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <AuthBrand />
        {view === "login" ? (
          <form className="auth-form" onSubmit={onSubmit}>
            <label htmlFor="login-username"><KeyRound size={13} /> 学号登录</label>
            <input id="login-username" value={username}
              onChange={(event) => onUsernameChange(event.target.value)}
              placeholder="学号（如 U20260000001）" autoComplete="username" />
            <PasswordInput id="login-password" value={password}
              onChange={(event) => onPasswordChange(event.target.value)}
              placeholder="密码" autoComplete="current-password" />
            <button className="auth-submit" type="submit" disabled={!username.trim() || !password || loading}>
              {loading ? "登录中" : "登录"}
            </button>
            {notice && <p className="auth-ok">{notice}</p>}
            {error
              ? <p className="auth-error">{error}</p>
              : <p className="auth-hint">账号由管理员创建，初始密码为学号。</p>}
            <div className="auth-actions">
              <span />
              <button type="button" className="auth-link"
                onClick={() => { setNotice(""); setView("forgot"); }}>忘记密码？</button>
            </div>
          </form>
        ) : (
          <ForgotPasswordScreen onBack={handleBack} />
        )}
      </div>
    </div>
  );
}

function App() {
  const [sessionId, setSessionId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [openSources, setOpenSources] = useState([]);
  const [templateProtocol, setTemplateProtocol] = useState("FTP");
  const [templateResult, setTemplateResult] = useState(null);
  const [copied, setCopied] = useState(null);
  const [sessionLoadError, setSessionLoadError] = useState("");
  const [loadingSession, setLoadingSession] = useState(false);
  const [historyVersion, setHistoryVersion] = useState(0);
  const [authRequired, setAuthRequired] = useState(false);
  const [tokenNotice, setTokenNotice] = useState("");
  const [loginUsername, setLoginUsername] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [loginError, setLoginError] = useState("");
  const [loginLoading, setLoginLoading] = useState(false);
  const [userSubject, setUserSubject] = useState(getUserSubject());
  const [userRoles, setUserRoles] = useState(getUserRoles());
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordError, setPasswordError] = useState("");
  const [passwordMessage, setPasswordMessage] = useState("");
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [usageOpen, setUsageOpen] = useState(false);
  const [view, setView] = useState("chat");
  const [attachment, setAttachment] = useState(null);
  const [attachError, setAttachError] = useState("");
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [inspectorCollapsed, setInspectorCollapsed] = useState(false);
  const inputRef = useRef(null);
  const fileInputRef = useRef(null);
  const abortRef = useRef(null);
  // 当前流绑定的任务（start 帧携带 task_id 时登记）：停止按钮据此把本地断流
  // 升级为显式取消——S3-c 起断线≠取消，仅 abort 会让任务在后台继续执行。
  const activeTaskRef = useRef(null);
  const pendingRequestRef = useRef(null);
  const messagesRef = useRef(null);
  // 找回竞态防护用的当前值快照（异步 await 返回后闭包里的 state 已旧，必须读 ref）；
  // conversationSeq 是会话动作的世代号：新建/切换/讲解/新提问都会递增。
  const sessionIdRef = useRef(null);
  const viewRef = useRef("chat");
  const conversationSeqRef = useRef(0);
  // 用户未上翻时才跟随流式输出滚动到底，避免打断回看历史内容。
  const pinnedBottomRef = useRef(true);

  useEffect(() => {
    // client.js dispatches this when the backend rejects the stored token.
    const onAuthRequired = () => setAuthRequired(true);
    window.addEventListener("assistant-auth-required", onAuthRequired);
    return () => window.removeEventListener("assistant-auth-required", onAuthRequired);
  }, []);

  // 每次渲染后同步快照：resumeActiveTask 在 await 之后靠它们判断「会话已切换/
  // 已离开聊天页」，丢弃迟到的找回结果（不落新会话的气泡）。
  useEffect(() => {
    sessionIdRef.current = sessionId;
    viewRef.current = view;
  });

  // 会话 id 持久化（S4-d）：选中新会话时同步到 localStorage——刷新后据此
  // 自动找回会话与仍在执行的活动任务。清空不走这里：挂载首拍 sessionId 为 null，
  // 若写空串会抢在自动找回前抹掉存根；新建/删除/登出由调用点显式清。
  useEffect(() => {
    if (sessionId) setLastSessionId(sessionId);
  }, [sessionId]);

  // 首次挂载自动找回最近会话（含活动任务续传）：local 模式无令牌概念直接可恢复；
  // account 模式未登录时由 restoreConversation 的 401 分支保留存根，登录后再找。
  useEffect(() => {
    const stored = getLastSessionId();
    if (stored) restoreConversation(stored, { silent: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    function checkTokenExpiry() {
      const expiresAt = getTokenExpiresAt();
      if (!getApiToken() || !expiresAt) {
        setTokenNotice("");
        return;
      }
      const remaining = new Date(expiresAt).getTime() - Date.now();
      if (!Number.isFinite(remaining)) {
        setTokenNotice("");
        return;
      }
      if (remaining <= 0) {
        // 已过期：主动清本地状态并弹登录页，不等后端 401。
        setApiToken("");
        setUserIdentity(null);
        setTokenExpiresAt("");
        setUserSubject("");
        setUserRoles([]);
        setTokenNotice("");
        setAuthRequired(true);
        return;
      }
      // 24h 内将过期：顶部提示条。
      setTokenNotice(remaining <= 24 * 60 * 60 * 1000
        ? `登录将于 ${formatSessionTime(expiresAt)} 到期，届时需重新登录。`
        : "");
    }
    checkTokenExpiry();
    const timer = window.setInterval(checkTokenExpiry, 60000);
    return () => window.clearInterval(timer);
  }, []);

  // Ctrl/Cmd + K：新建对话并聚焦输入框（与侧栏按钮上的 kbd 提示一致）。
  useEffect(() => {
    function onKeyDown(event) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        startNewConversation();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  // 滚动条闲置隐藏：滚动时给容器加 is-scrolling，停止 1.2s 后移除（捕获阶段监听，
  // scroll 不冒泡）。悬停显示由 CSS :hover 负责。
  useEffect(() => {
    const timers = new WeakMap();
    function onScroll(event) {
      const el = event.target;
      if (!(el instanceof HTMLElement)) return;
      if (!el.matches(".messages, .sidebar, .inspector, .admin-page")) return;
      el.classList.add("is-scrolling");
      window.clearTimeout(timers.get(el));
      timers.set(el, window.setTimeout(() => el.classList.remove("is-scrolling"), 1200));
    }
    window.addEventListener("scroll", onScroll, true);
    return () => window.removeEventListener("scroll", onScroll, true);
  }, []);

  // 输入框随内容自动长高（上限由 CSS max-height 控制），清空后收回单行。
  useEffect(() => {
    const textarea = inputRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 140)}px`;
  }, [input]);

  // 消息变化（含流式 delta）时，若用户停留在底部附近则跟随滚动。
  useEffect(() => {
    const container = messagesRef.current;
    if (container && pinnedBottomRef.current) {
      container.scrollTop = container.scrollHeight;
    }
  }, [messages, loading]);

  function handleMessagesScroll() {
    const container = messagesRef.current;
    if (!container) return;
    pinnedBottomRef.current =
      container.scrollHeight - container.scrollTop - container.clientHeight < 120;
  }

  async function reloadSession(id) {
    const history = await loadChatSession(id);
    const mapped = history.messages.map(mapHistoryMessage);
    // active 查询可能在 React 提交下一拍前返回；同步 ref 才能通过同会话守卫。
    sessionIdRef.current = history.session_id;
    setSessionId(history.session_id);
    setMessages(mapped);
    setOpenSources([]);
    return mapped;   // 供找回逻辑复用同一份历史基座（resumeTaskStream 的 base）
  }

  async function handleLogin(event) {
    event.preventDefault();
    const username = loginUsername.trim();
    if (!username || !loginPassword || loginLoading) return;
    setLoginLoading(true);
    setLoginError("");
    try {
      const data = await login(username, loginPassword);
      setApiToken(data.token);
      setUserIdentity(data.subject, data.roles || []);
      setTokenExpiresAt(data.expires_at || "");
      setUserSubject(data.subject || "");
      setUserRoles(data.roles || []);
      setLoginUsername("");
      setLoginPassword("");
      setAuthRequired(false);
      setSessionLoadError("");
      setHistoryVersion((prev) => prev + 1);
      // Retry the current session under the new token so a 401 during restore
      // or chat becomes transparent after signing in. 顺带找回仍在跑的活动任务。
      if (sessionId) {
        conversationSeqRef.current += 1;
        const seq = conversationSeqRef.current;
        reloadSession(sessionId)
          .then((base) => resumeActiveTask(sessionId, base, seq))
          .catch(() => {});
      } else {
        // 刷新后重新登录：会话 state 已空，按持久化的最近会话找回（含活动任务）。
        const stored = getLastSessionId();
        if (stored) restoreConversation(stored, { silent: true });
      }
    } catch (err) {
      setLoginError(err?.status === 429 ? rateLimitMessage(err)
        : "登录失败：请检查学号和密码，或确认账号未被停用。");
    } finally {
      setLoginLoading(false);
    }
  }

  async function signOut() {
    // logout 内部已吞掉请求失败：本地状态无论如何都清理。
    await logout();
    setApiToken("");
    setUserIdentity(null);
    setLastSessionId("");
    setTokenExpiresAt("");
    setUserSubject("");
    setUserRoles([]);
    setPasswordOpen(false);
    setTokenNotice("");
    setView("chat");
    setAuthRequired(true);
    setHistoryVersion((prev) => prev + 1);
  }

  async function submitPasswordChange(event) {
    event.preventDefault();
    if (passwordSaving) return;
    setPasswordError("");
    setPasswordMessage("");
    if (newPassword !== confirmPassword) {
      setPasswordError("两次输入的新密码不一致。");
      return;
    }
    setPasswordSaving(true);
    try {
      await changePassword(oldPassword, newPassword);
      setPasswordMessage("修改成功，其他设备已退出登录。");
      setOldPassword("");
      setNewPassword("");
      setConfirmPassword("");
    } catch {
      setPasswordError("修改失败：请确认旧密码是否正确。");
    } finally {
      setPasswordSaving(false);
    }
  }

  async function ask(question) {
    const text = question.trim();
    const file = attachment;
    if (!pendingRequestRef.current || pendingRequestRef.current.text !== text || pendingRequestRef.current.file !== file) {
      pendingRequestRef.current = { id: crypto.randomUUID(), text, file };
    }
    // message 为空但有附件时允许发送：后端会补默认问句。
    if ((!text && !file) || loading) return;
    conversationSeqRef.current += 1;   // 真正发起的新提问使进行中的找回探测作废
    setInput("");
    setAttachment(null);
    setAttachError("");
    setLoading(true);
    // 工作区卡片改为累积式：提问不再清掉已打开的来源。
    setMessages((prev) => [...prev, {
      role: "user", content: text,
      attachment: file ? { name: file.name, size: file.size } : null
    }]);
    const assistantIndex = messages.length + 1;
    setMessages((prev) => [...prev, { role: "assistant", answer: "", citations: [], confidence: "medium", streaming: true, tools: [] }]);
    const controller = new AbortController();
    abortRef.current = controller;
    // 更新当前 assistant 气泡（assistantIndex 在流式期间固定不变）。
    const patchAssistant = (patch) => setMessages((prev) => prev.map((item, index) => index === assistantIndex ? patch(item) : item));
    try {
      await sendChatStream({ session_id: sessionId, message: text, request_id: pendingRequestRef.current.id, context: { page: "assistant" } }, {
        signal: controller.signal,
        ...makeStreamHandlers(patchAssistant, {
          // start 帧带 task_id（产品任务路径）时：给气泡打任务锚并登记——
          // 停止按钮据此升级为显式取消，取消补丁按 task_id 校验气泡身份。
          onStart: (data) => {
            setSessionId(data.session_id);
            if (data.task_id) patchAssistant((item) => ({ ...item, task_id: data.task_id }));
            trackActiveTask(data.task_id, assistantIndex);
          },
          onDone: () => { pendingRequestRef.current = null; }
        })
      }, file);
    } catch (err) {
      if (err.name !== "AbortError") {
        setInput(text);
        setAttachment(file);
        patchAssistant((item) => ({ ...item, answer: item.answer || `请求失败：${err.message}`, streaming: false, confidence: "low" }));
      }
    } finally {
      abortRef.current = null;
      activeTaskRef.current = null;
      finalizeStreamingBubble(patchAssistant);
      setLoading(false);
      setHistoryVersion((prev) => prev + 1);
    }
  }

  // start 帧携带 task_id（产品任务路径）时登记当前任务：停止按钮据此把
  // 「本地断流」升级为「显式取消」（断线≠取消，S3-c 契约）；旧路径无 task_id → null。
  // 只记 taskId 与气泡下标——取消收尾补丁按 task_id 校验气泡身份（见 patchBubbleCancelled）。
  function trackActiveTask(taskId, assistantIndex) {
    activeTaskRef.current = taskId ? { taskId, assistantIndex } : null;
  }

  // 取消成功后给任务气泡落「已取消」终态。只认带同一 task_id 且未持久化（无 id）
  // 的 assistant 气泡：本地 abort 后气泡已脱离 streaming 态、列表也可能已被
  // reloadSession/会话切换替换，按下标+身份双重校验，迟到的补丁不会误伤别的消息。
  function patchBubbleCancelled(assistantIndex, taskId, task) {
    setMessages((prev) => prev.map((item, index) => (
      index === assistantIndex && item.role === "assistant" && !item.id && item.task_id === taskId
        ? {
            ...item, streaming: false, cancelled: true,
            status: "cancelled", reason: task?.reason || "user_cancelled",
            answer: item.answer || "（已取消）"
          }
        : item
    )));
  }

  // 订阅任务事件流并把帧渲染到末位流式气泡：S3-d 讲解接续与 S3-e 会话找回共用。
  // - base：调用方已加载的历史气泡；其末位已是 assistant（终态回答已随历史落库，
  //   如 replayed 接续）时不追加流式气泡——回放帧自然落空，由收口后的 reloadSession 呈现。
  // - 流式气泡创建即带 task_id：它是取消补丁与 done 帧身份校验的锚点。
  // - done 后整条消息以库内为准刷新；异常时把兜底文案打进气泡（若有），中止保持局部内容。
  async function resumeTaskStream(taskId, base, { sessionId: targetSessionId, failureText = "加载失败" }) {
    const needsBubble = base.at(-1)?.role !== "assistant";
    const assistantIndex = base.length;
    setMessages(needsBubble
      ? [...base, { role: "assistant", answer: "", citations: [], confidence: "medium", streaming: true, tools: [], task_id: taskId }]
      : base);
    setLoading(true);
    const controller = new AbortController();
    abortRef.current = controller;
    // done 后整条消息列表会被库内数据替换：仅当目标槽位仍是流式气泡时才打补丁。
    const patchAssistant = (patch) => setMessages((prev) => prev.map(
      (item, index) => (index === assistantIndex && item.streaming ? patch(item) : item)
    ));
    let sawPendingDone = false;
    try {
      // pending 行暂不可重驱动（太新/占槽满/另一处恢复中）时服务端回
      // done{status:"pending",terminal:false}——有界重试接续，覆盖孤儿 grace
      // 窗口后仍不接续则如实停在已收内容并提示稍后找回。
      sawPendingDone = await retryPendingTask({
        signal: controller.signal,
        retryMs: PENDING_RESUME_RETRY_MS,
        maxRetries: PENDING_RESUME_MAX_ATTEMPTS,
        onRetry: () => {
          // 上一轮 pending done 已把气泡置为非流式：重订阅前标回，后续帧补丁才打得进。
          setMessages((prev) => prev.map((item, index) => (
            index === assistantIndex && item.role === "assistant" && item.task_id === taskId
              ? { ...item, streaming: true } : item
          )));
        },
        streamOnce: async () => {
          let pending = false;
          await streamTaskEvents(taskId, {
            signal: controller.signal,
            ...makeStreamHandlers(patchAssistant, {
              onStart: (d) => { setSessionId(d.session_id); trackActiveTask(d.task_id || taskId, assistantIndex); },
              onDone: (d) => { pending = d?.terminal === false && d?.status === "pending"; }
            })
          });
          return pending;
        }
      });
      if (sawPendingDone) {
        // 重试耗尽仍 pending：给任务气泡留可解释的落点（身份校验同 patchBubbleCancelled）。
        setMessages((prev) => prev.map((item, index) => (
          index === assistantIndex && item.role === "assistant" && !item.id && item.task_id === taskId
            ? { ...item, answer: item.answer || "任务仍在后台执行，稍后重新打开本会话可继续查看。", confidence: "low" }
            : item
        )));
      }
      // 此时首条用户消息与完整回答均已落库，以库内为准刷新消息列表。
      // （用户已取消/中止时不走到这里：本地 abort 从 catch 进 finally，气泡保留「已取消」收尾态。）
      if (targetSessionId) await reloadSession(targetSessionId).catch(() => {});
    } catch (err) {
      if (err.name !== "AbortError") {
        patchAssistant((item) => ({ ...item, answer: item.answer || `${failureText}：${err.message}`, streaming: false, confidence: "low" }));
      }
    } finally {
      abortRef.current = null;
      activeTaskRef.current = null;
      finalizeStreamingBubble(patchAssistant);
      setLoading(false);
      setHistoryVersion((prev) => prev + 1);
    }
  }

  // S3-e 会话刷新/切换后的活动任务找回：会话内最近一条 pending/running 任务
  // 若存在，则按已加载历史追加流式气泡并订阅事件流续跑展示（用户消息受理时已
  // 落库，历史里已有气泡，不重复渲染）。任何失败静默降级为普通会话视图。
  // seq 由调用方在触发前捕获：await 返回时世代已变（切了别的会话/新建/新提问）
  // 或当前视图已不在聊天页/会话已漂移 → 丢弃找回结果，不污染新视图。
  async function resumeActiveTask(targetSessionId, base, seq) {
    try {
      if (viewRef.current !== "chat") return;
      const task = await getActiveTask(targetSessionId);
      if (!task || task.session_id !== targetSessionId) return;
      if (conversationSeqRef.current !== seq) return;
      if (viewRef.current !== "chat" || sessionIdRef.current !== targetSessionId) return;
      await resumeTaskStream(task.task_id, base, { sessionId: targetSessionId, failureText: "找回的回答加载失败" });
    } catch {
      // 找回路径整体静默降级：404/网络/装配异常都不阻塞消息列表。
    }
  }

  // S3-d 讲解入口：把一次作业评测结果带进聊天视图，由助教讲解（不重评、不改成绩）。
  // 讲解会话的首条用户消息由后端合成——前端不伪造本地用户气泡，先拉取已落库消息
  // （续传时序上可能尚未落库，则以事件流为准），再订阅任务事件流，done 后刷新列表。
  async function openExplanation(assignmentId, submissionId) {
    // 与既有「单一 in-flight」惯例一致：聊天流式进行中不接管（同 restoreConversation），
    // 避免旧流的收尾补丁落到新消息列表上。
    if (loading) return;
    // 404（作业不可见/非本人提交）等错误抛回作业页就地展示。
    const data = await explainSubmission(assignmentId, submissionId);
    conversationSeqRef.current += 1;
    setSidebarOpen(false);
    setOpenSources([]);
    setView("chat");
    setSessionId(data.session_id);
    setSessionLoadError("");
    pendingRequestRef.current = null;
    let base = [];
    try {
      const history = await loadChatSession(data.session_id);
      base = (history?.messages || []).map(mapHistoryMessage);
    } catch {
      // 拉取失败或消息尚未落库：不阻塞，内容以事件流为准，done 后统一刷新。
    }
    await resumeTaskStream(data.task_id, base, { sessionId: data.session_id, failureText: "讲解加载失败" });
  }

  // 停止按钮（S3-e）：先本地断流复位 UI；当前流绑定了任务（start 帧带 task_id）
  // 时再补一次显式取消——仅 abort 只断开订阅，任务会在后台继续执行并落库。
  // 无 task_id（旧管线/字段缺失）保持原 abort-only 行为。
  function stopGeneration() {
    const active = activeTaskRef.current;
    activeTaskRef.current = null;
    abortRef.current?.abort();
    if (!active) return;
    cancelTask(active.taskId)
      .then((task) => {
        // 幂等语义：返回 cancelled 才给气泡落「已取消」终态；
        // 任务恰已自然收口（answered/…）时不动气泡内容。
        if (task && task.status !== "cancelled") return;
        patchBubbleCancelled(active.assistantIndex, active.taskId, task);
      })
      .catch(() => {
        // 取消请求失败（任务已消失/网络异常）：本地已断流，
        // 任务生命周期由后端兜底（稍后刷新仍可由 active 找回）。
      });
  }

  function handleFilePick(event) {
    const file = event.target.files?.[0];
    // 清空 input value，允许再次选择同一个文件时仍触发 change。
    event.target.value = "";
    if (!file) return;
    const error = validateAttachment(file);
    if (error) { setAttachError(error); return; }
    setAttachError("");
    setAttachment(file);
  }

  function startNewConversation() {
    if (loading) return;
    conversationSeqRef.current += 1;
    pendingRequestRef.current = null;
    setLastSessionId("");   // 显式离开旧会话：刷新不应找回
    setSessionId(null);
    setSessionLoadError("");
    setMessages([]);
    setOpenSources([]);
    requestAnimationFrame(() => inputRef.current?.focus());
  }

  async function restoreConversation(id, { silent = false } = {}) {
    if (!id || loading || loadingSession) return;
    conversationSeqRef.current += 1;
    const seq = conversationSeqRef.current;
    setLoadingSession(true);
    setSessionLoadError("");
    try {
      const base = await reloadSession(id);
      // 找回该会话仍在执行的活动任务（S3-e）：不 await——找回失败静默降级，
      // 续跑中的任务流自管 loading/收尾，不阻塞会话切换本身的完成。
      resumeActiveTask(id, base, seq);
    } catch (err) {
      if (silent) {
        // 自动找回路径：401（尚未登录）保留存根待登录后再找；404/其他说明
        // 会话已删或不属于当前账号——清掉存根避免每次刷新重试。
        if (err?.status !== 401) setLastSessionId("");
      } else {
        setSessionLoadError("未找到该会话，或会话已删除。");
      }
    } finally {
      setLoadingSession(false);
    }
  }

  async function openSource(chunkId) {
    // 收起状态下点引用时自动展开右栏，避免"点了没反应"。
    setInspectorCollapsed(false);
    // 同一来源已打开时不重复追加。
    if (openSources.some((item) => item.chunk_id === chunkId)) return;
    try {
      const source = await getSource(chunkId);
      setOpenSources((prev) => prev.some((item) => item.chunk_id === chunkId)
        ? prev
        : [...prev, { ...source, chunk_id: chunkId }]);
    } catch (err) {
      setOpenSources((prev) => [...prev, { chunk_id: chunkId, title: "来源读取失败", content: err.message, source_path: "" }]);
    }
  }

  async function copyAnswer(index, answer) {
    try {
      await navigator.clipboard.writeText(answer);
      setCopied(index);
      window.setTimeout(() => setCopied(null), 1600);
    } catch {
      // Clipboard can be disabled by browser policy; the answer remains selectable.
    }
  }

  // 删除当前会话（顶栏按钮）：确认后调后端，本地回到新会话并刷新历史列表。
  async function deleteCurrentSession() {
    if (!sessionId || loading) return;
    if (!window.confirm("删除当前会话？此操作不可恢复。")) return;
    try {
      await deleteSession(sessionId);
      startNewConversation();
      setHistoryVersion((prev) => prev + 1);
    } catch {
      setSessionLoadError("删除会话失败，请稍后重试。");
    }
  }

  async function buildTemplate() {
    setTemplateResult(null);
    setInspectorCollapsed(false);
    try {
      setTemplateResult(await generateTemplate({
        protocol: templateProtocol,
        template_type: "port_text_protocol",
        difficulty: "basic",
        requirements: ""
      }));
    } catch (err) {
      setTemplateResult({ explanation: `模板生成失败：${err.message}`, template: "", checklist: [] });
    }
  }

  return (
    <div className={`workspace${sidebarOpen ? " sidebar-open" : ""}${inspectorCollapsed ? " inspector-closed" : ""}`}>
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><Sparkles size={19} /></div>
          <div><strong>FlowSense</strong><span>项目知识工作台</span></div>
        </div>

        <button className="new-conversation" onClick={() => { setSidebarOpen(false); startNewConversation(); }} disabled={loading}>
          <MessageSquarePlus size={17} /> 新建对话 <kbd>⌘ K</kbd>
        </button>

        <SessionList currentId={sessionId} refreshKey={historyVersion}
          error={sessionLoadError} onSelect={(id) => { setSidebarOpen(false); restoreConversation(id); }} />

        <UsagePanel open={usageOpen} onToggle={() => setUsageOpen((prev) => !prev)} />

        {(userRoles.includes("admin") || userRoles.includes("leader") || userRoles.includes("operator")) && (
          <AdminNavEntry roles={userRoles} onOpen={(target) => { setSidebarOpen(false); setView(target); }} />
        )}

        <AssignmentsNavEntry roles={userRoles} onOpen={() => { setSidebarOpen(false); setView("assignments"); }} />
        {(userRoles.includes("admin") || userRoles.includes("leader")) && <button className="account-action" onClick={() => {
          setSidebarOpen(false); setView("rag");
        }}><BookOpen size={15} />知识与问题</button>}

        {userSubject && (
          <HomeworkNavEntry onOpen={() => { setSidebarOpen(false); setView("homework"); }} />
        )}

        {userSubject && (
          <div className="account-panel">
            <div className="sidebar-label">账号 · {userSubject}</div>
            <button type="button" className="account-action" onClick={() => setPasswordOpen((prev) => !prev)} aria-expanded={passwordOpen}>
              <KeyRound size={14} /> 修改密码
            </button>
            {passwordOpen && (
              <form className="session-loader password-form" onSubmit={submitPasswordChange}>
                <PasswordInput value={oldPassword}
                  onChange={(event) => setOldPassword(event.target.value)}
                  placeholder="旧密码" autoComplete="current-password" />
                <PasswordInput value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                  placeholder="新密码" autoComplete="new-password" />
                <div>
                  <PasswordInput value={confirmPassword}
                    onChange={(event) => setConfirmPassword(event.target.value)}
                    placeholder="确认新密码" autoComplete="new-password" />
                  <button type="submit" disabled={!oldPassword || !newPassword || !confirmPassword || passwordSaving}>
                    {passwordSaving ? "提交中" : "确认"}
                  </button>
                </div>
                {passwordError && <small>{passwordError}</small>}
                {passwordMessage && <small className="ok">{passwordMessage}</small>}
              </form>
            )}
            <button type="button" className="account-action" onClick={signOut}>
              <LogOut size={14} /> 退出登录
            </button>
          </div>
        )}

        <div className="sidebar-bottom">
          <KnowledgeHealth />
          <div className="template-compact">
            <div className="sidebar-label">插件骨架</div>
            <div><input aria-label="协议名" value={templateProtocol} onChange={(event) => setTemplateProtocol(event.target.value)} /><button onClick={buildTemplate} title="生成模板"><FileCode2 size={16} /></button></div>
          </div>
        </div>
      </aside>

      {view === "rag" ? <RagWorkspace onBack={() => setView("chat")} /> : view === "admin" ? (
        <AdminPage onBack={() => setView("chat")} roles={userRoles} />
      ) : view === "assignments" ? (
        <AssignmentsPage onBack={() => setView("chat")} roles={userRoles} />
      ) : view === "homework" ? (
        <HomeworkPage onBack={() => setView("chat")} onExplain={openExplanation} />
      ) : (
        <>
      <main className="conversation">
        <header className="topbar">
          <button type="button" className="sidebar-toggle" onClick={() => setSidebarOpen(true)} aria-label="打开侧边栏">
            <Menu size={17} />
          </button>
          <div><div className="eyebrow">流量检测教学项目</div><h1>智能问答助手</h1></div>
          <button className="session-status danger" type="button" onClick={deleteCurrentSession} disabled={!sessionId || loading}
            title={sessionId ? "删除当前会话（不可恢复）" : "发送第一条消息后可删除会话"}>
            <span className="online-dot" />{sessionId ? "删除会话" : "新会话"}
            {sessionId && <Trash2 size={13} />}
          </button>
        </header>

        <section className="messages" aria-live="polite" ref={messagesRef} onScroll={handleMessagesScroll}>
          {messages.length === 0 ? (
            <div className="welcome">
              <div className="welcome-icon"><Bot size={30} /></div>
              <p className="eyebrow">PROJECT COPILOT</p>
              <h2>从项目资料中找到可靠答案。</h2>
              <p>我会检索代码、设计文档和 FAQ，并将每个结论连接回原始来源。</p>
              <div className="suggestion-grid">
                {QUICK_QUESTIONS.map(({ label, question }) => <button key={label} onClick={() => ask(question)}>{label}<ArrowUp size={15} /></button>)}
              </div>
            </div>
          ) : messages.map((message, index) => (
            <article className={`message ${message.role}`} key={`${message.role}-${index}`}>
              <div className="avatar">{message.role === "user" ? "你" : <Bot size={17} />}</div>
              <div className="message-content">
                <div className="message-meta"><b>{message.role === "user" ? "你" : "FlowSense 助手"}</b>{message.role === "assistant" && <span className={isCancelledMessage(message) ? "meta-cancelled" : ""}>{isCancelledMessage(message) ? cancelledLabel(message) : message.confidence === "high" ? "已检索验证" : "项目资料回答"}</span>}</div>
                {message.role === "user" ? (
                  <>
                    {message.content && <p>{message.content}</p>}
                    {message.attachment && (
                      <span className="attachment-chip message-attachment">
                        <FileCode2 size={13} /><b>{message.attachment.name}</b><small>{formatFileSize(message.attachment.size)}</small>
                      </span>
                    )}
                  </>
                ) : (
                  <>
                    {message.tools?.length ? (
                      <div className="tool-list">{message.tools.map((entry, toolIndex) => <ToolCallCard key={`${entry.tool}-${toolIndex}`} entry={entry} />)}</div>
                    ) : null}
                    <Markdown text={message.answer || (isCancelledMessage(message) ? "（已取消）" : "")} />
                  </>
                )}
                {message.role === "assistant" && <div className="answer-actions">
                  <button onClick={() => copyAnswer(index, message.answer)}>{copied === index ? <Check size={14} /> : <Copy size={14} />}{copied === index ? "已复制" : "复制"}</button>
                  {message.citations?.length ? <span>{message.citations.length} 条引用</span> : null}
                </div>}
                {message.role === "assistant" && !isCancelledMessage(message) && <AnswerFeedback message={message} />}
                {message.citations?.length ? <div className="citations">
                  {message.citations.map((citation, citationIndex) => (
                    <button key={`${citation.chunk_id}-${citationIndex}`} onClick={() => openSource(citation.chunk_id)}>
                      <BookOpen size={14} /><span><b>[{citationIndex + 1}] {citation.source_path.split("/").pop()}</b><small>{citation.section || citation.source_path}</small></span><ChevronRight size={14} />
                    </button>
                  ))}
                </div> : null}
              </div>
            </article>
          ))}
          {loading && <div className="thinking"><span /><span /><span />正在检索项目资料并组织回答</div>}
        </section>

        <form className="composer" onSubmit={(event) => { event.preventDefault(); ask(input); }}>
          {attachment && (
            <div className="attachment-chips">
              <span className="attachment-chip">
                <FileCode2 size={13} /><b>{attachment.name}</b><small>{formatFileSize(attachment.size)}</small>
                <button type="button" onClick={() => setAttachment(null)} aria-label="移除附件"><X size={12} /></button>
              </span>
            </div>
          )}
          <textarea ref={inputRef} value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); ask(input); }
          }} placeholder="问一个关于代码、架构或协议的问题…" rows="1" />
          <input ref={fileInputRef} type="file" accept=".c,.so" hidden onChange={handleFilePick} aria-label="选择附件" />
          <button type="button" className="composer-attach" onClick={() => fileInputRef.current?.click()}
            disabled={loading} aria-label="上传附件" title="上传 .c / .so 附件（≤256KB）"><Paperclip size={15} /></button>
          {loading ? <button type="button" onClick={stopGeneration} aria-label="停止生成" title="停止生成"><Square size={15} /></button> : <button type="submit" disabled={!input.trim() && !attachment} aria-label="发送问题"><Send size={18} /></button>}
          <div className="composer-hint">
            {attachError
              ? <span className="attach-error">{attachError}</span>
              : <><span>Enter 发送</span><span>Shift + Enter 换行</span><span>回答仅基于项目知识库</span><span>可附加 .c / .so（≤256KB）</span></>}
          </div>
        </form>
      </main>

      <aside className={`inspector${inspectorCollapsed ? " collapsed" : ""}${openSources.length > 0 || templateResult ? " drawer-open" : ""}`}>
        <div className="inspector-title">
          <button type="button" className="panel-toggle" onClick={() => setInspectorCollapsed((prev) => !prev)}
            title={inspectorCollapsed ? "展开工作区" : "收起工作区"} aria-label={inspectorCollapsed ? "展开工作区" : "收起工作区"}>
            {inspectorCollapsed ? <PanelRightOpen size={17} /> : <PanelRightClose size={17} />}
          </button>
          <span>工作区</span>
        </div>
        {openSources.length === 0 && !templateResult && (
          <div className="inspector-empty"><BookOpen size={25} /><h3>引用来源</h3><p>回答中的资料卡片会显示在这里，方便阅读项目原文。</p></div>
        )}
        {openSources.map((source) => (
          <SourcePanel key={source.chunk_id} source={source}
            onClose={() => setOpenSources((prev) => prev.filter((item) => item.chunk_id !== source.chunk_id))} />
        ))}
        {templateResult && <section className="template-result"><div className="drawer-heading"><div><span>PLUGIN TEMPLATE</span><h2>{templateProtocol} 学习骨架</h2></div><button className="icon-button" onClick={() => setTemplateResult(null)}><X size={17} /></button></div><p>{templateResult.explanation}</p>{templateResult.template && <pre>{templateResult.template}</pre>}</section>}
      </aside>
        </>
      )}

      {(openSources.length > 0 || templateResult) && (
        <div className="inspector-backdrop" onClick={() => { setOpenSources([]); setTemplateResult(null); }} />
      )}
      {sidebarOpen && <div className="sidebar-backdrop" onClick={() => setSidebarOpen(false)} />}

      {tokenNotice && !authRequired && (
        <div className="token-expiry-banner">{tokenNotice}</div>
      )}

      {authRequired && (
        <LoginScreen username={loginUsername} password={loginPassword} error={loginError} loading={loginLoading}
          onUsernameChange={setLoginUsername} onPasswordChange={setLoginPassword} onSubmit={handleLogin} />
      )}
    </div>
  );
}

createRoot(document.getElementById("root")).render(<App />);
