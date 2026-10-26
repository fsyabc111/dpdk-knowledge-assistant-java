// 固定合成上游：绝不编译/执行代码，无外部请求、文件写入或原始请求日志。
// MCP账本只保留job_id与固定结果；进程重启即丢失，不覆盖真实harness恢复验收。
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const PROTOCOL_VERSION = '2025-06-18';
const PROTOCOLS = ['dns', 'http', 'ssh'];
const CASES = [{name:'synthetic_fixed_pass',category:'synthetic'}];
const ID_PATTERN = /^[A-Za-z0-9_.:-]{1,160}$/;
const TOOLS = [
  ['list_protocols', {}], ['list_cases', {protocol:{type:'string'}}],
  ['grade_plugin', {protocol:{type:'string'},plugin_source:{type:'string'},job_id:{type:'string'}}],
  ['get_task_status', {job_id:{type:'string'}}], ['get_reference_answer', {protocol:{type:'string'}}],
].map(([name, properties]) => ({name,description:'仅隔离演练固定合成结果；不执行代码',inputSchema:{type:'object',properties,required:name==='list_protocols'?[]:name==='get_task_status'?['job_id']:name==='grade_plugin'?['protocol','plugin_source']:['protocol']}}));
const fixedGrade = protocol => ({ok:true,synthetic:true,protocol,score:100,passed:1,cases_total:1,failed:0,crashed:0,timedout:0,cheat:0,
  results:[{name:'synthetic_fixed_pass',category:'synthetic',result:'PASS',failures:[]}]});
const rpcError = (res, id, code, message, status=200) => {
  res.writeHead(status, {'Content-Type':'application/json'});
  res.end(JSON.stringify({jsonrpc:'2.0',id:id ?? null,error:{code,message}}));
};

export function createFakeUpstream() {
  const sessions = new Map(), jobs = new Map();
  let createdJobs = 0;
  const tool = (name, args) => {
    if (name === 'list_protocols') return {ok:true,synthetic:true,protocols:PROTOCOLS.map(name => ({name,cases:1,categories:{synthetic:1}}))};
    if (name === 'get_task_status') {
      if (typeof args.job_id !== 'string' || !ID_PATTERN.test(args.job_id)) return {ok:false,error:'invalid_job_id',synthetic:true};
      return jobs.has(args.job_id) ? {ok:true,synthetic:true,job_id:args.job_id,status:'finished',result:jobs.get(args.job_id)}
        : {ok:true,synthetic:true,job_id:args.job_id,status:'unknown'};
    }
    if (!['list_cases','grade_plugin','get_reference_answer'].includes(name)) return {ok:false,error:'unknown_tool',synthetic:true};
    if (!PROTOCOLS.includes(args.protocol)) return {ok:false,error:'unknown_protocol',synthetic:true};
    if (name === 'list_cases') return {ok:true,synthetic:true,protocol:args.protocol,cases:CASES};
    if (name === 'get_reference_answer') return {ok:true,synthetic:true,protocol:args.protocol,source:'/* synthetic fixed reference: never compiled */\nint synthetic_reference(void) { return 0; }'};
    // plugin_source只存在于本次HTTP解析的局部变量，不散列、不存储、不执行、不回显。
    if (typeof args.plugin_source !== 'string') return {ok:false,error:'invalid_source_type',synthetic:true};
    const jobId = args.job_id ?? `synthetic-${createdJobs + 1}`;
    if (typeof jobId !== 'string' || !ID_PATTERN.test(jobId)) return {ok:false,error:'invalid_job_id',synthetic:true};
    if (jobs.has(jobId)) return jobs.get(jobId);
    if (jobs.size >= 32768) return {ok:false,error:'synthetic_capacity',synthetic:true};
    const result = fixedGrade(args.protocol);
    jobs.set(jobId, result); createdJobs++;
    return result;
  };
  const server = http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/health') { res.setHeader('Content-Type','application/json'); res.end('{"synthetic":true,"executes_source":false,"live_quality":false}'); return; }
    if (req.method !== 'POST') { res.writeHead(405, {Allow:'POST'}).end(); return; }
    const chunks = []; let size = 0;
    try {
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 1048576) { res.writeHead(413).end(); return; }
        chunks.push(chunk);
      }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString() || '{}'); }
      catch { rpcError(res, null, -32700, 'Parse error', 400); return; }
      res.setHeader('Content-Type','application/json');
      if (req.url === '/mcp') {
        if (!body || Array.isArray(body) || body.jsonrpc !== '2.0' || typeof body.method !== 'string') {
          rpcError(res, null, -32600, 'Invalid Request', 400); return;
        }
        if (body.method === 'initialize') {
          if (sessions.size >= 65536) { rpcError(res, body.id, -32000, 'Synthetic session capacity', 503); return; }
          const sessionId = randomUUID(); sessions.set(sessionId, false); res.setHeader('Mcp-Session-Id',sessionId);
          res.end(JSON.stringify({jsonrpc:'2.0',id:body.id,result:{protocolVersion:PROTOCOL_VERSION,capabilities:{tools:{}},serverInfo:{name:'synthetic-no-execution-harness',version:'1.0.0'}}})); return;
        }
        const sessionId = req.headers['mcp-session-id'];
        if (!sessions.has(sessionId)) { rpcError(res, body.id, -32001, 'Unknown MCP session', 404); return; }
        res.setHeader('Mcp-Session-Id',sessionId);
        if (body.method === 'notifications/initialized') { sessions.set(sessionId,true); res.writeHead(202).end(); return; }
        if (!sessions.get(sessionId)) { rpcError(res,body.id,-32002,'Session not initialized'); return; }
        if (body.method === 'tools/list') { res.end(JSON.stringify({jsonrpc:'2.0',id:body.id,result:{tools:TOOLS}})); return; }
        if (body.method !== 'tools/call') { rpcError(res,body.id,-32601,'Method not found'); return; }
        const args = body.params?.arguments;
        if (!args || typeof args !== 'object' || Array.isArray(args)) { rpcError(res,body.id,-32602,'Invalid params'); return; }
        const payload = tool(body.params?.name,args);
        res.end(JSON.stringify({jsonrpc:'2.0',id:body.id,result:{isError:false,structuredContent:payload,content:[{type:'text',text:JSON.stringify(payload)}]}})); return;
      }
      if (req.url === '/v1/embeddings') {
        const inputs = Array.isArray(body.input) ? body.input : [body.input];
        res.end(JSON.stringify({object:'list',model:'synthetic-1024',data:inputs.map((_,index)=>({object:'embedding',index,embedding:Array.from({length:1024},(_,i)=>i===0?1:0)})),usage:{prompt_tokens:1,total_tokens:1}}));
      } else if (req.url === '/v1/chat/completions') {
        const messages = Array.isArray(body.messages) ? body.messages : [];
        const lastUser = messages.findLastIndex(message => message.role === 'user');
        const afterUser = messages.slice(lastUser + 1);
        const system = String(messages.find(message => message.role === 'system')?.content ?? '');
        let message = {role:'assistant',content:'合成隔离演练回答'};
        if (Array.isArray(body.tools) && body.tools.length && !afterUser.some(item => item.role === 'tool')) {
          // 只编排知识检索；真正检索、引用、验证和持久化仍由Java产品链完成。
          message = {role:'assistant',content:'',tool_calls:[{id:'synthetic-kb-1',type:'function',
            function:{name:'kb_search',arguments:JSON.stringify({query:'DNS'})}}]};
        } else if (system.includes('supported') && system.includes('不得重写答案')) {
          message.content = '{"status":"answered","reason":"supported","supported":true}';
        } else if (afterUser.some(item => item.role === 'tool') || system.includes('事实依据')) {
          message.content = '合成知识说明：DNS 报文解析需要核对字段边界 [1]。';
        }
        res.end(JSON.stringify({id:'synthetic',object:'chat.completion',model:'synthetic-chat',
          choices:[{index:0,message,finish_reason:message.tool_calls?'tool_calls':'stop'}],
          usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}}));
      } else { res.writeHead(404).end('{"error":"synthetic route unavailable"}'); }
    } catch { if (!res.headersSent) res.writeHead(500); res.end(); }
  });
  // 仅测试观察固定账本；不提供HTTP调试接口，快照不会暴露原始请求/源码。
  return {server, snapshot:()=>({createdJobs,jobs:[...jobs].map(([jobId,result])=>({jobId,result:structuredClone(result)}))})};
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const fake = createFakeUpstream();
  fake.server.listen(Number(process.env.FAKE_UPSTREAM_PORT ?? 18091), process.env.FAKE_UPSTREAM_HOST ?? '0.0.0.0', () => {
    // 测试IPC只发送端口，不写stdout/请求日志。普通容器进程没有IPC通道。
    if (typeof process.send === 'function') process.send({port:fake.server.address().port});
  });
}
