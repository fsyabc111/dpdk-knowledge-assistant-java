// 使用原始前端API客户端，经与Vite相同的构建期env替换后请求真实Java服务。
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';

const [base, root] = process.argv.slice(2);
assert.match(base, /^http:\/\/127\.0\.0\.1:\d+$/);
const require = createRequire(path.join(root, 'frontend/package.json'));
const { transform } = require('esbuild');
const source = await readFile(path.join(root, 'frontend/src/api/client.js'), 'utf8');
const compiled = await transform(source, {
  format: 'esm', define: { 'import.meta.env.VITE_API_BASE': JSON.stringify(base) }
});
const storage = new Map();
globalThis.localStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: key => storage.delete(key)
};
globalThis.window = new EventTarget();
const client = await import('data:text/javascript;base64,' + Buffer.from(compiled.code).toString('base64'));
const payload = { message: 'client contract', request_id: crypto.randomUUID() };
const answer = await client.sendChat(payload);
assert.equal(answer.answer_status, 'unanswered');
assert.equal(answer.answer_reason, 'no_evidence');
assert.deepEqual(answer.next_steps, [{ label: '继续提问', source_path: '' }]);
const replay = await client.sendChat(payload);
assert.deepEqual(replay, { ...answer, next_steps: [] });
assert.equal((await client.getTask(answer.task_id)).task_id, answer.task_id);
assert.equal(await client.getActiveTask(answer.session_id), null);
const events = [];
await client.streamTaskEvents(answer.task_id, { onEvent: (event, data) => events.push([event, data]) });
assert.equal(events[0][0], 'start');
assert.equal(events.at(-1)[0], 'done');
// 冻结契约：内存正常done可省略terminal，DB重建终态附true；仅pending必须false。
assert.notEqual(events.at(-1)[1].terminal, false);
assert.equal(events.at(-1)[1].task_id, answer.task_id);
assert.equal(events.find(([name]) => name === 'delta')[1].delta, answer.answer);
const streamed = [];
await client.sendChatStream({ message: 'client stream contract', request_id: crypto.randomUUID() }, {
  onEvent: (event, data) => streamed.push([event, data])
});
assert.equal(streamed[0][0], 'start');
assert.equal(streamed.at(-1)[0], 'done');
assert.ok(streamed.some(([name]) => name === 'sources'));
assert.ok(streamed.some(([name]) => name === 'delta'));
const file = new File(['/* synthetic source */\nint main(void){return 0;}\n'], 'sample.c');
const uploaded = await client.sendChat({ message: 'client upload contract', request_id: crypto.randomUUID() }, file);
assert.equal(uploaded.answer_reason, 'no_evidence');
assert.ok(uploaded.task_id);
console.log('FRONTEND_CLIENT_CONTRACT=PASS json/replay/status/active/task-sse/chat-sse/multipart');
