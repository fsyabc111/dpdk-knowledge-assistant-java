// 合成固定上游，无外部网络与输入持久化；不代表真实模型/评测结果。
import http from 'node:http';
http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') { res.end('{"synthetic":true}'); return; }
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > 1048576) { res.writeHead(413).end(); return; } chunks.push(chunk); }
  let body; try { body = JSON.parse(Buffer.concat(chunks).toString() || '{}'); } catch { res.writeHead(400).end(); return; }
  res.setHeader('Content-Type', 'application/json');
  if (req.url === '/v1/embeddings') {
    const inputs = Array.isArray(body.input) ? body.input : [body.input];
    res.end(JSON.stringify({object:'list', model:'synthetic-1024', data:inputs.map((_, index) => ({object:'embedding', index, embedding:Array.from({length:1024}, (_,i) => i === 0 ? 1 : 0)})), usage:{prompt_tokens:1,total_tokens:1}}));
  } else if (req.url === '/v1/chat/completions') {
    res.end(JSON.stringify({id:'synthetic',object:'chat.completion',model:'synthetic-chat',choices:[{index:0,message:{role:'assistant',content:'合成隔离演练回答'},finish_reason:'stop'}],usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}}));
  } else { res.writeHead(404).end(JSON.stringify({error:'synthetic route unavailable'})); }
}).listen(18091, '0.0.0.0');
