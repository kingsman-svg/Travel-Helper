import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import path from 'node:path';
import { data, configuration, waitFor } from './runtime.mjs';
const env = configuration(), base = `http://127.0.0.1:${env.BACKEND_PORT || 8080}/api`;
const directory = path.join(data, 'verification'); mkdirSync(directory, { recursive: true });
const reportPath = path.join(directory, 'day2.json');
const checks = [];
if (!process.argv.includes('--after-restart')) writeFileSync(reportPath,JSON.stringify({checkedAt:new Date().toISOString(),allPassed:false,phase:'running',checks},null,2));
async function api(endpoint, method = 'GET', body, status = 200) {
  const response = await fetch(base + endpoint, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(100_000) });
  const value = await response.json();
  assert.equal(response.status, status, value.message || endpoint); return value;
}
async function check(name, work) { const start = Date.now(); const detail = await work(); checks.push({ name, passed: true, durationMs: Date.now()-start, detail }); console.log(`通过：${name}`); }
function sum(draft) {
  const costs = [...draft.itinerary.extraCosts, ...draft.itinerary.days.flatMap(d => d.activities.flatMap(a => a.costs))];
  const total = costs.filter(c => draft.request.budgetIncludes.includes(c.category)).reduce((n,c) => n + Math.round(c.amount * 100),0);
  assert.equal(Math.round(draft.budget.total * 100),total);
  assert.equal(Math.round(draft.budget.remaining * 100),Math.round(draft.request.totalBudget*100)-total);
  return total;
}
const digest = value => createHash('sha256').update(JSON.stringify({request:value.request,itinerary:value.itinerary,budget:value.budget,schedule:value.schedule})).digest('hex');
async function chat(id, message) {
  const response = await fetch(base + '/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ conversationId:id, message }), signal: AbortSignal.timeout(100_000) });
  assert.equal(response.status,200);
  const events = (await response.text()).split('\n').filter(s => s.startsWith('data:')).map(s => JSON.parse(s.slice(5)));
  assert.ok(events.some(e => e.type === 'done'), '流式回复必须正常结束');
  assert.ok(!events.some(e => e.type === 'error'), '流式回复不能包含错误');
  return events.filter(e => e.type === 'delta').map(e => e.content).join('');
}
assert.ok(await waitFor(base + '/health'), '后端未就绪');
if (process.argv.includes('--after-restart')) {
  const report = JSON.parse(readFileSync(reportPath,'utf8'));
  report.allPassed = false; report.phase = 'restart-running'; writeFileSync(reportPath,JSON.stringify(report,null,2));
  const saved = await api('/trips/' + report.tripId);
  await check('重启后已保存行程保持一致',async () => {
    const actual = report.digestStrategy === 'content-v1' ? digest(saved.draft) : createHash('sha256').update(JSON.stringify(saved.draft)).digest('hex');
    assert.equal(actual,report.savedDigest); return {version:saved.version};
  });
  await check('重启后Advisor记忆可恢复',async () => { const reply = await chat(report.memoryId,'我之前告诉你的旅行偏好代码是什么？请只回复代码。'); assert.ok(reply.includes(report.nonce)); return '从数据库恢复之前的偏好代码'; });
  report.restartChecks = checks; report.restartCheckedAt = new Date().toISOString(); report.allPassed = true; report.phase = 'complete';
  writeFileSync(reportPath,JSON.stringify(report,null,2));
} else {
  const current = await api('/conversations','POST');
  const request = {origin:'上海',destination:'杭州',startDate:'2026-11-01',endDate:'2026-11-03',people:2,totalBudget:3000,budgetIncludes:['ACCOMMODATION','FOOD','TRANSPORT','TICKETS','OTHER'],preferences:'美食和湖景，第一天和第三天正常游玩，第二天先安排约60分钟步行，后续可能调整。'};
  let generated, adjusted, saved;
  await check('真实生成连续三日行程',async () => { generated = await api(`/conversations/${current.id}/plan`,'POST',{expectedVersion:0,request}); assert.equal(generated.draft.itinerary.days.length,3); assert.equal(generated.draft.request.people,2); sum(generated.draft); return {version:generated.version,total:generated.draft.budget.total}; });
  await check('第二天少走路并保留原需求',async () => { adjusted = await api(`/conversations/${current.id}/adjust`,'POST',{expectedVersion:1,message:'第二天少走路，请把第二天估计步行总量降低到30分钟以内，多休息；第一、三天不变。'}); assert.deepEqual(adjusted.draft.request,generated.draft.request); assert.ok(adjusted.draft.schedule.walkingMinutesByDate['2026-11-02'] <= 30); assert.deepEqual(adjusted.draft.itinerary.days[0],generated.draft.itinerary.days[0]); assert.deepEqual(adjusted.draft.itinerary.days[2],generated.draft.itinerary.days[2]); return adjusted.draft.schedule.walkingMinutesByDate; });
  await check('保存列表查询与Markdown导出',async () => { saved = await api(`/conversations/${current.id}/save`,'POST',{expectedVersion:2}); const list = await api('/trips'); assert.ok(list.some(t => t.id === saved.id)); assert.deepEqual((await api('/trips/'+saved.id)).draft.itinerary,adjusted.draft.itinerary); const response = await fetch(`${base}/trips/${saved.id}/export`); assert.equal(response.status,200); const md = await response.text(); assert.ok(md.includes('2026-11-02') && md.includes(String(saved.draft.budget.total))); return {tripId:saved.id}; });
  await check('编辑后精确计算预算并保存新版本',async () => { const itinerary = structuredClone(adjusted.draft.itinerary); itinerary.title += ' · 验收'; itinerary.extraCosts.push({category:'OTHER',amount:0.30,note:'验收小数费用'}); const edited = await api(`/conversations/${current.id}/draft`,'PUT',{expectedVersion:2,itinerary}); assert.equal(sum(edited.draft),sum(adjusted.draft)+30); saved = await api(`/conversations/${current.id}/save`,'POST',{expectedVersion:3}); return {version:saved.version,total:saved.draft.budget.total}; });
  await check('无效日期和过期版本被拦截',async () => { const itinerary = structuredClone(saved.draft.itinerary); itinerary.days[1].date = '2026-12-01'; await api(`/conversations/${current.id}/draft`,'PUT',{expectedVersion:3,itinerary},400); await api(`/conversations/${current.id}/save`,'POST',{expectedVersion:0},409); assert.equal((await api(`/conversations/${current.id}`)).version,3); return '原行程与保存版本未被覆盖'; });
  const memory = await api('/conversations','POST'), isolated = await api('/conversations','POST');
  const nonce = '绿茶' + randomUUID().slice(0,8);
  await check('Advisor同会话记忆与新会话隔离',async () => { await chat(memory.id,`请记住，我的旅行偏好代码是${nonce}。只回复收到。`); const reply = await chat(memory.id,'我刚告诉你的旅行偏好代码是什么？请只回复代码。'); assert.ok(reply.includes(nonce)); const other = await chat(isolated.id,'我之前告诉你的旅行偏好代码是什么？如果没有告诉你，只回复UNKNOWN。'); assert.ok(!other.includes(nonce)); return '同会话可召回；新会话未出现旧偏好'; });
  writeFileSync(reportPath,JSON.stringify({checkedAt:new Date().toISOString(),checks,tripId:saved.id,conversationId:current.id,savedDigest:digest(saved.draft),digestStrategy:'content-v1',memoryId:memory.id,nonce,allPassed:false,note:'重启验收前，allPassed 保持 false。'},null,2));
  console.log('第一阶段通过，请重启服务后运行 node scripts/verify-day2.mjs --after-restart。');
}
