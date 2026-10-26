import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { data, root } from './runtime.mjs';

const file = path.join(data, 'dev-processes.json');
if (!existsSync(file)) {
  console.log('没有本项目记录的开发服务需要停止。');
  process.exit(0);
}
const record = JSON.parse(readFileSync(file, 'utf8'));
// Validate process identity rather than killing whichever process later reuses a PID.
function belongs(pid, expected) {
  try {
    const command = execFileSync('/bin/ps', ['-p', String(pid), '-o', 'command='], { encoding: 'utf8' });
    return command.includes(expected);
  } catch { return false; }
}
if (belongs(record.supervisor, 'scripts/dev.mjs')) {
  process.kill(record.supervisor, 'SIGTERM');
  console.log('已通知开发进程停止本项目服务。');
} else {
  for (const child of [...record.processes].reverse()) {
    const expected = child.identity || (child.name === 'ollama' ? path.join(root, '.data/tools/ollama/ollama')
      : child.name === 'frontend' ? 'node_modules/vite/bin/vite.js'
      : child.name === 'backend' ? path.join(root, 'backend/target/travelbao-backend-0.1.0-SNAPSHOT.jar')
      : path.join(root, 'mcp-server/target/travelbao-mcp-server-0.1.0-SNAPSHOT.jar'));
    if (belongs(child.pid, expected)) {
      try { process.kill(-child.pid, 'SIGTERM'); } catch { /* Already stopped. */ }
    }
  }
  unlinkSync(file);
  console.log('已清理本项目记录的残留服务。');
}
