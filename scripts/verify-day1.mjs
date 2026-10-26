import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { data, configuration, waitFor } from './runtime.mjs';

const env = configuration();
const backend = `http://127.0.0.1:${env.BACKEND_PORT || 8080}`;
const results = [];
await waitFor(`${backend}/api/health`, 30_000);
await waitFor(`http://127.0.0.1:${env.FRONTEND_PORT || 5173}`, 30_000);

async function check(name, run) {
  const start = Date.now();
  try {
    const detail = await run();
    results.push({ name, status: 'passed', durationMs: Date.now() - start, detail });
    console.log(`[通过] ${name}`);
  } catch (error) {
    // Only safe messages produced by these checks are printed; never serialize environment variables.
    results.push({ name, status: 'failed', durationMs: Date.now() - start,
      message: error.safeMessage || '验证请求未完成，请检查对应服务。' });
    console.log(`[未通过] ${name}`);
  }
}

function fail(message) { const error = new Error(message); error.safeMessage = message; throw error; }

await check('frontend', async () => {
  const response = await fetch(`http://127.0.0.1:${env.FRONTEND_PORT || 5173}`, { signal: AbortSignal.timeout(5000) });
  if (!response.ok || !(await response.text()).includes('旅游宝')) fail('前端未返回预期页面。');
  return { httpStatus: response.status };
});
await check('backend', async () => {
  const response = await fetch(`${backend}/api/health`, { signal: AbortSignal.timeout(5000) });
  const body = await response.json();
  if (!response.ok || body.application !== 'travelbao-backend') fail('主后端健康检查失败。');
  return body;
});
for (const type of ['mcp', 'embedding', 'chat', 'tools']) {
  await check(type, async () => {
    const response = await fetch(`${backend}/api/probes/${type}`, { method: 'POST', signal: AbortSignal.timeout(100_000) });
    if (!response.ok) fail('验证接口返回失败。');
    const result = await response.json();
    if (result.status !== 'passed') fail(result.message || '验证未通过。');
    return result;
  });
}
await check('stream', async () => {
  const response = await fetch(`${backend}/api/chat`, { method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message: '请只用一句话介绍你能怎样帮助我规划杭州旅行。' }),
    signal: AbortSignal.timeout(100_000) });
  if (!response.ok) fail('流式接口返回失败。');
  const raw = (await response.text()).replace(/\r\n/g, '\n');
  const events = raw.split('\n\n').flatMap(frame => {
    const text = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
    return text ? [JSON.parse(text)] : [];
  });
  if (events.some(event => event.type === 'error')) fail('模型流返回明确错误。');
  const reply = events.filter(event => event.type === 'delta').map(event => event.content).join('');
  if (!reply.trim() || events.at(-1)?.type !== 'done') fail('流式回复为空或缺少结束事件。');
  return { deltaEvents: events.filter(event => event.type === 'delta').length, reply, terminalEvent: 'done' };
});
await check('validation', async () => {
  const response = await fetch(`${backend}/api/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: '' }), signal: AbortSignal.timeout(5000) });
  if (response.status !== 400) fail('空请求未被拒绝。');
  return { httpStatus: response.status };
});

const folder = path.join(data, 'verification');
mkdirSync(folder, { recursive: true });
const report = { timestamp: new Date().toISOString(), mode: 'live', results,
  allPassed: results.every(result => result.status === 'passed') };
writeFileSync(path.join(folder, 'day1.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
console.log(`验收记录已保存至 .data/verification/day1.json；通过 ${results.filter(r => r.status === 'passed').length}/${results.length} 项。`);
process.exitCode = report.allPassed ? 0 : 1;
