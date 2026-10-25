// Node标准库本地HTTP测试，不使用PG、Docker、模型或真实harness。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { fork } from 'node:child_process';
import { createFakeUpstream } from './server.mjs';

async function fixture(t) {
  const fake = createFakeUpstream(); fake.server.listen(0,'127.0.0.1'); await once(fake.server,'listening');
  t.after(()=>new Promise(resolve=>{fake.server.close(resolve);fake.server.closeAllConnections();}));
  const url=`http://127.0.0.1:${fake.server.address().port}/mcp`;
  const post=async(body, session)=>fetch(url,{method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json, text/event-stream',...(session?{'Mcp-Session-Id':session}:{})},body:JSON.stringify(body)});
  const initialized=await post({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-06-18',capabilities:{},clientInfo:{name:'test',version:'1'}}});
  const session=initialized.headers.get('Mcp-Session-Id');
  assert.equal((await initialized.json()).result.protocolVersion,'2025-06-18'); assert.ok(session);
  assert.equal((await post({jsonrpc:'2.0',method:'notifications/initialized'},session)).status,202);
  return {...fake,url,post,session,call:async(name,args={})=>(await (await post({jsonrpc:'2.0',id:2,method:'tools/call',params:{name,arguments:args}},session)).json()).result.structuredContent};
}
test('JSONRPC initialize/session/notification/tools list matches Java transport',async t=>{
  const f=await fixture(t);
  const listed=await f.post({jsonrpc:'2.0',id:3,method:'tools/list'},f.session);
  assert.equal(listed.headers.get('Mcp-Session-Id'),f.session);
  assert.deepEqual((await listed.json()).result.tools.map(t=>t.name),['list_protocols','list_cases','grade_plugin','get_task_status','get_reference_answer']);
  assert.equal((await f.post({jsonrpc:'2.0',id:3,method:'tools/list'},'invalid')).status,404);
  const invalid=await f.post({jsonrpc:'2.0',id:4,method:'unknown'},f.session); assert.equal((await invalid.json()).error.code,-32601);
});
test('all tools return fixed synthetic results and never echo submitted source',async t=>{
  const f=await fixture(t),sentinel='SOURCE_SENTINEL_DO_NOT_PERSIST_819ac';
  assert.deepEqual((await f.call('list_protocols')).protocols.map(x=>x.name),['dns','http','ssh']);
  assert.equal((await f.call('list_cases',{protocol:'dns'})).cases[0].category,'synthetic');
  const grade=await f.call('grade_plugin',{protocol:'dns',plugin_source:sentinel,job_id:'job-one'});
  assert.equal(grade.score,100); assert.equal(grade.results[0].result,'PASS'); assert.equal(grade.synthetic,true);
  assert.ok(!(await f.call('get_reference_answer',{protocol:'dns'})).source.includes(sentinel));
  assert.ok(!JSON.stringify(f.snapshot()).includes(sentinel)); assert.ok(!JSON.stringify(grade).includes(sentinel));
  assert.equal((await f.call('grade_plugin',{protocol:sentinel,plugin_source:sentinel})).error,'unknown_protocol');
});
test('same job replays once, finished status is exact, unknown stays explicit',async t=>{
  const f=await fixture(t);
  const first=await f.call('grade_plugin',{protocol:'dns',plugin_source:'first arbitrary code',job_id:'stable-job'});
  const repeated=await Promise.all(Array.from({length:8},()=>f.call('grade_plugin',{protocol:'dns',plugin_source:'different ignored code',job_id:'stable-job'})));
  repeated.forEach(result=>assert.deepEqual(result,first)); assert.equal(f.snapshot().createdJobs,1);
  const status=await f.call('get_task_status',{job_id:'stable-job'}); assert.equal(status.status,'finished'); assert.deepEqual(status.result,first);
  assert.deepEqual(await f.call('get_task_status',{job_id:'missing'}),{ok:true,synthetic:true,job_id:'missing',status:'unknown'});
});
test('new process-local ledger starts empty; fixed model/vector routes remain local',async t=>{
  const f=await fixture(t), origin=f.url.slice(0,-4);
  assert.equal((await f.call('get_task_status',{job_id:'stable-job'})).status,'unknown');
  const embedding=await (await fetch(origin+'/v1/embeddings',{method:'POST',body:JSON.stringify({input:['x','y']})})).json();
  assert.equal(embedding.data.length,2); assert.equal(embedding.data[0].embedding.length,1024);
  const chat=await (await fetch(origin+'/v1/chat/completions',{method:'POST',body:'{}'})).json();
  assert.equal(chat.choices[0].message.content,'合成隔离演练回答');
});
test('standalone server emits no request/source logs to stdout or stderr', {timeout:10000}, async t=>{
  const child=fork(new URL('./server.mjs',import.meta.url),[],{env:{...process.env,FAKE_UPSTREAM_HOST:'127.0.0.1',FAKE_UPSTREAM_PORT:'0'},silent:true});
  t.after(()=>{if(child.exitCode===null)child.kill();});
  let stdout='',stderr=''; child.stdout.on('data',x=>stdout+=x);child.stderr.on('data',x=>stderr+=x);
  const [{port}]=await once(child,'message'),url=`http://127.0.0.1:${port}/mcp`;
  const post=async(body,session)=>fetch(url,{method:'POST',headers:{'Content-Type':'application/json',...(session?{'Mcp-Session-Id':session}:{})},body:JSON.stringify(body)});
  const init=await post({jsonrpc:'2.0',id:1,method:'initialize'}),session=init.headers.get('Mcp-Session-Id'); await init.json();
  await post({jsonrpc:'2.0',method:'notifications/initialized'},session);
  const response=await post({jsonrpc:'2.0',id:2,method:'tools/call',params:{name:'grade_plugin',arguments:{protocol:'dns',plugin_source:'DO_NOT_LOG_SOURCE_SENTINEL',job_id:'log-test'}}},session);
  assert.ok(!(await response.text()).includes('DO_NOT_LOG_SOURCE_SENTINEL'));
  child.kill(); await once(child,'exit'); assert.equal(stdout,'');assert.equal(stderr,'');
});
