import { spawn } from 'node:child_process';
import { existsSync, openSync, closeSync, mkdirSync, writeFileSync, unlinkSync, copyFileSync } from 'node:fs';
import path from 'node:path';
import { root, data, configuration, ollamaBinary, ollamaEnvironment, waitFor } from './runtime.mjs';

const env = configuration();
const processes = [];
const pidFile = path.join(data, 'dev-processes.json');
const logDir = path.join(data, 'logs');
mkdirSync(logDir, { recursive: true });
let stopping = false;

function save() { writeFileSync(pidFile, JSON.stringify({ supervisor: process.pid, processes }, null, 2), { mode: 0o600 }); }
function start(name, command, args, cwd = root, childEnv = env, identity = name === 'ollama' ? command : args[0]) {
  const logFile = path.join(logDir, `${name}.log`);
  const log = openSync(logFile, 'a', 0o600);
  const child = spawn(command, args, { cwd, env: childEnv, stdio: ['ignore', log, log], detached: true });
  closeSync(log);
  child.on('error', () => { console.error(`${name} 无法启动，请检查 ${path.relative(root, logFile)}`); void stop(1); });
  child.on('exit', code => { if (!stopping) { console.error(`${name} 已退出（${code}），请查看对应日志。`); void stop(1); } });
  if (child.pid) processes.push({ name, pid: child.pid, command, identity, cwd, logFile });
  save();
}

async function stop(code = 0) {
  if (stopping) return;
  stopping = true;
  for (const child of [...processes].reverse()) {
    try { process.kill(-child.pid, 'SIGTERM'); } catch { /* Already stopped. */ }
  }
  if (existsSync(pidFile)) unlinkSync(pidFile);
  setTimeout(() => process.exit(code), 500);
}

for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => void stop());

async function main() {
  if (existsSync(pidFile)) throw new Error('已存在开发进程记录，请先运行 node scripts/stop.mjs。');
  const backendJar = path.join(root, 'backend/target/travelbao-backend-0.1.0-SNAPSHOT.jar');
  const mcpJar = path.join(root, 'mcp-server/target/travelbao-mcp-server-0.1.0-SNAPSHOT.jar');
  if (!existsSync(backendJar) || !existsSync(mcpJar)) throw new Error('请先运行 ./mvnw package。');
  if (!existsSync(path.join(root, 'frontend/node_modules/vite'))) throw new Error('请先在 frontend/ 运行 npm ci。');
  const runDir = path.join(data, 'run', String(process.pid));
  mkdirSync(runDir, { recursive: true });
  const runningBackend = path.join(runDir, 'backend.jar');
  const runningMcp = path.join(runDir, 'mcp.jar');
  // Boot loads nested JARs lazily: rebuilding target/*.jar must not alter a running archive.
  copyFileSync(backendJar, runningBackend);
  copyFileSync(mcpJar, runningMcp);
  const ollamaUrl = env.OLLAMA_BASE_URL || 'http://127.0.0.1:11434';
  if (!(await waitFor(`${ollamaUrl}/api/version`, 1000))) {
    start('ollama', ollamaBinary(), ['serve'], root, ollamaEnvironment(env));
    if (!(await waitFor(`${ollamaUrl}/api/version`, 20_000))) throw new Error('Ollama 未就绪，请查看 .data/logs/ollama.log。');
  } else console.log('复用已经运行的 Ollama 服务（停止脚本不会关闭它）。');
  start('mcp', 'java', ['-jar', runningMcp], root, env, runningMcp);
  if (!(await waitFor(`http://127.0.0.1:${env.MCP_PORT || 8081}/health`))) throw new Error('MCP 服务未就绪。');
  start('backend', 'java', ['-jar', runningBackend], root, env, runningBackend);
  if (!(await waitFor(`http://127.0.0.1:${env.BACKEND_PORT || 8080}/api/health`))) throw new Error('后端未就绪。');
  // Browser build tools receive ports only; they never receive server API keys.
  const frontendEnv = { ...env };
  delete frontendEnv.DEEPSEEK_API_KEY;
  delete frontendEnv.AMAP_API_KEY;
  start('frontend', 'node', ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1'], path.join(root, 'frontend'), frontendEnv);
  const url = `http://127.0.0.1:${env.FRONTEND_PORT || 5173}`;
  if (!(await waitFor(url))) throw new Error('前端未就绪。');
  console.log(`旅游宝已启动：${url}`);
  console.log('日志保存在 .data/logs/；按 Ctrl+C 或运行 node scripts/stop.mjs 停止本次启动的服务。');
  setInterval(() => {}, 60_000);
}

main().catch(error => { console.error(error.message); void stop(1); });
