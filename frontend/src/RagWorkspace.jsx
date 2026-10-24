import React, { useEffect, useState } from "react";
import { ArrowLeft, Check, ClipboardCheck, RefreshCw, Save, Send, X } from "lucide-react";
import { requestJson } from "./api/client.js";

const STATES = { open: "待处理", in_progress: "处理中", awaiting_verification: "待验证", resolved: "已解决", ignored: "已忽略" };
const REASONS = { no_evidence: "没有证据", insufficient_evidence: "证据不足", conflicting_evidence: "资料冲突",
  validation_failed: "校验服务异常", generation_failed: "生成异常", interrupted: "请求中断", clarification: "需要澄清",
  invalid_citation: "引用无效", missing_citation: "缺少引用", evidence_budget: "证据超出预算" };

export function KnowledgeHealth() {
  const [ready, setReady] = useState(null);
  useEffect(() => {
    let active = true;
    async function check() {
      try { const value = await requestJson("/api/ready"); if (active) setReady(value.ok === true); }
      catch { if (active) setReady(false); }
    }
    check();
    const timer = setInterval(check, 30000);
    return () => { active = false; clearInterval(timer); };
  }, []);
  return <div className="index-card"><div>{ready === true && <span className="online-dot" />}
    {ready === null ? "知识库状态检查中" : ready ? "知识库可用" : "知识库暂不可用"}</div><p>PostgreSQL + pgvector</p></div>;
}

export function AnswerFeedback({ message }) {
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  if (!message.request_id || message.streaming) return null;
  async function send(reason) {
    setBusy(true);
    try {
      await requestJson("/api/rag/feedback", { method: "POST", body: JSON.stringify({
        message_id: message.message_id || message.id, reason
      }) });
      setNotice("反馈已记录");
    } catch (err) { setNotice(err.message); }
    finally { setBusy(false); }
  }
  return <div className="rag-feedback">
    {message.issue_id && <span>问题已记录</span>}
    <button type="button" disabled={busy || notice === "反馈已记录"} onClick={() => send("unresolved")}><ClipboardCheck size={14} />未解决</button>
    <button type="button" disabled={busy || notice === "反馈已记录"} onClick={() => send("incorrect")}><X size={14} />回答有误</button>
    <span role="status">{notice}</span>
  </div>;
}

export default function RagWorkspace({ onBack }) {
  const [tab, setTab] = useState("issues");
  const [status, setStatus] = useState("");
  const [offset, setOffset] = useState(0);
  const [items, setItems] = useState([]);
  const [selected, setSelected] = useState(null);
  const [note, setNote] = useState("");
  const [nextState, setNextState] = useState("in_progress");
  const [knowledge, setKnowledge] = useState([]);
  const [knowledgeId, setKnowledgeId] = useState("");
  const [mergedInto, setMergedInto] = useState("");
  const [editing, setEditing] = useState(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [notice, setNotice] = useState("");
  const [verification, setVerification] = useState(null);
  const [revisions, setRevisions] = useState([]);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let active = true;
    setSelected(null);
    setItems([]);
    Promise.all([
      requestJson(`/api/rag/issues?status=${encodeURIComponent(status)}&limit=50&offset=${offset}`),
      requestJson("/api/rag/knowledge")
    ]).then(([list, entries]) => { if (active) { setItems(list.items); setKnowledge(entries.items); } })
      .catch(err => { if (active) setNotice(err.message); });
    return () => { active = false; };
  }, [status, offset, refresh]);
  async function action(fn) {
    setBusy(true); setNotice("");
    try { await fn(); } catch (err) { setNotice(err.message); }
    finally { setBusy(false); }
  }
  async function open(id) {
    const row = await requestJson(`/api/rag/issues/${id}`);
    setSelected(row); setNote(row.note); setNextState(row.status); setVerification(null);
    setKnowledgeId(row.knowledge_id || ""); setMergedInto(row.merged_into || "");
  }
  function edit(item) {
    setEditing(item); setTitle(item?.title || ""); setBody(item?.body || ""); setConfirmed(false); setRevisions([]);
  }
  return <main className="rag-workspace">
    <header className="rag-heading"><button type="button" className="icon-button" title="返回聊天" onClick={onBack}><ArrowLeft size={18} /></button>
      <h1>知识与问题</h1><button className="icon-button" title="刷新" disabled={busy} onClick={() => setRefresh(x => x + 1)}><RefreshCw size={17} /></button></header>
    <nav className="rag-tabs" aria-label="工作台视图">{[["issues", "未解答问题"], ["knowledge", "共享知识"]].map(([id, label]) =>
      <button key={id} aria-pressed={tab === id} onClick={() => setTab(id)}>{label}</button>)}</nav>
    {notice && <p role="status" className="rag-notice">{notice}</p>}
    {tab === "issues" ? <>
      <div className="rag-toolbar"><select aria-label="问题状态" value={status} onChange={e => { setStatus(e.target.value); setOffset(0); }}>
        <option value="">全部状态</option>{Object.entries(STATES).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
        <button disabled={!offset || busy} onClick={() => setOffset(Math.max(0, offset - 50))}>上一页</button>
        <button disabled={items.length < 50 || busy} onClick={() => setOffset(offset + 50)}>下一页</button></div>
      <div className="rag-columns"><section aria-label="问题列表">
        {!items.length && <p>暂无记录</p>}
        {items.map(item => <button className={`rag-row ${selected?.id === item.id ? "selected" : ""}`} key={item.id}
          disabled={busy} onClick={() => action(() => open(item.id))}>
          <strong>{item.question}</strong><span>{STATES[item.status]} · {REASONS[item.reason] || item.reason}</span>
          <small>{new Date(item.created_at).toLocaleString()}</small></button>)}
      </section><section aria-label="问题详情">
        {!selected ? <p>请选择问题</p> : <>
          <h2>{selected.question}</h2><p>原因：{REASONS[selected.reason] || selected.reason}</p>
          {selected.context_text && <blockquote>{selected.context_text}</blockquote>}
          <p>模型：{selected.model_version} · 耗时：{(selected.elapsed_ms / 1000).toFixed(1)} 秒</p>
          {selected.feedback && <p>用户反馈：{selected.feedback}</p>}
          <ul>{selected.evidence.map((c, i) => <li key={i}>{c.source_path}：{c.section}</li>)}</ul>
          <label>处理状态<select value={nextState} onChange={e => setNextState(e.target.value)}>{Object.entries(STATES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
          <label>处理备注<textarea value={note} maxLength={4000} onChange={e => setNote(e.target.value)} /></label>
          <label>关联知识<select value={knowledgeId} onChange={e => setKnowledgeId(e.target.value)}><option value="">未关联</option>
            {knowledge.map(k => <option value={k.id} key={k.id}>{k.title}</option>)}</select></label>
          <label>合并到问题<select value={mergedInto} onChange={e => setMergedInto(e.target.value)}><option value="">不合并</option>
            {items.filter(i => i.id !== selected.id).map(i => <option value={i.id} key={i.id}>{i.question.slice(0, 60)}</option>)}</select></label>
          <div className="rag-toolbar"><button disabled={busy} onClick={() => action(async () => {
            await requestJson(`/api/rag/issues/${selected.id}`, { method: "PATCH", body: JSON.stringify({status: nextState, note,
              claim: true, knowledge_id: knowledgeId || null, merged_into: mergedInto || null}) });
            setRefresh(x => x + 1); setNotice("处理记录已保存");
          })}><Save size={15} />认领并保存</button>
          <button disabled={busy} onClick={() => action(async () => {
            setVerification(await requestJson(`/api/rag/issues/${selected.id}/verify`, {method: "POST"}));
          })}><Check size={15} />原题验证</button></div>
          {verification && <div className="rag-verification"><strong>{verification.status === "answered" ? "验证通过" : "未通过验证"}</strong><p>{verification.answer}</p></div>}
          <h3>处理记录</h3>{selected.events.map((e, i) => <p key={i}>{e.created_at.slice(0, 19)} · {STATES[e.action] || e.action} · {e.detail}</p>)}
        </>}
      </section></div>
    </> : <div className="rag-columns"><section aria-label="共享知识列表">
      <button disabled={busy} onClick={() => edit(null)}><Send size={15} />新建知识</button>
      {!knowledge.length && <p>暂无共享知识</p>}
      {knowledge.map(k => <button className="rag-row" key={k.id} onClick={() => edit(k)}><strong>{k.title}</strong>
        <span>修订 {k.revision} · {k.status === "published" ? "已发布" : "已撤回"}</span></button>)}
    </section><section aria-label="知识编辑"><h2>{editing ? "修订知识" : "新建知识"}</h2>
      <label>标题<input value={title} maxLength={200} onChange={e => setTitle(e.target.value)} /></label>
      <label>正文<textarea className="rag-editor" value={body} maxLength={20000} onChange={e => setBody(e.target.value)} /></label>
      <label className="rag-confirm"><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />内容已人工检查，不含学生隐私或参考答案，发布后全体可见</label>
      <div className="rag-toolbar"><button disabled={busy || !title.trim() || !body.trim() || !confirmed} onClick={() => action(async () => {
        await requestJson(editing ? `/api/rag/knowledge/${editing.id}` : "/api/rag/knowledge", {method: editing ? "PATCH" : "POST",
          body: JSON.stringify({title, body, revision: editing?.revision || 0, status: "published", shared_confirmed: confirmed})});
        edit(null); setRefresh(x => x + 1); setNotice("已提交发布，索引更新完成后生效");
      })}><Send size={15} />发布</button>
      {editing && <button disabled={busy} onClick={() => action(async () => {
        await requestJson(`/api/rag/knowledge/${editing.id}`, {method: "PATCH", body: JSON.stringify({...editing, status: "withdrawn"})});
        edit(null); setRefresh(x => x + 1); setNotice("已提交撤回，索引更新完成后生效");
      })}><X size={15} />撤回</button>}
      {editing && <button disabled={busy} onClick={() => action(async () => {
        setRevisions((await requestJson(`/api/rag/knowledge/${editing.id}/revisions`)).items);
      })}>修订历史</button>}</div>
      {revisions.map(r => <details key={r.revision}><summary>修订 {r.revision} · {r.created_at}</summary><pre>{r.body}</pre></details>)}
    </section></div>}
  </main>;
}
