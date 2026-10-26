import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const projectRoot = fileURLToPath(new URL('../', import.meta.url));
let missingRequired = 0;

function checkCommand(name, args, minimum, extractVersion, required = true) {
  const result = spawnSync(name, args, {
    encoding: 'utf8',
    timeout: 10_000,
    shell: false,
  });
  const output = `${result.stdout ?? ''}\n${result.stderr ?? ''}`.trim();
  const version = extractVersion(output);
  const supported = version && minimum(version);

  if (result.error || result.status !== 0 || !supported) {
    console.log(`${required ? '[缺项]' : '[待准备]'} ${name}：${version ?? '未检测到可用命令'}`);
    if (required) missingRequired += 1;
    return;
  }

  console.log(`[可用] ${name} ${version}`);
}

console.log('旅游宝本机环境检查（不读取密钥、不联网、不安装依赖）');
checkCommand('java', ['-version'], v => Number(v.split('.')[0]) >= 17,
  text => text.match(/version\s+"([^"\s]+)"/)?.[1]);
checkCommand('node', ['--version'], v => {
  const [major, minor] = v.split('.').map(Number);
  return major > 22 || (major === 22 && minor >= 12);
}, text => text.match(/v(\d+\.\d+\.\d+)/)?.[1]);
checkCommand('npm', ['--version'], () => true,
  text => text.match(/\b(\d+\.\d+\.\d+)\b/)?.[1]);
if (existsSync(path.join(projectRoot, 'mvnw'))) {
  console.log('[可用] 项目 Maven Wrapper：无需全局安装 Maven');
} else {
  checkCommand('mvn', ['--version'], () => true,
    text => text.match(/Apache Maven\s+(\S+)/)?.[1], false);
}

const ollamaLookup = spawnSync('/usr/bin/which', ['ollama'], {
  encoding: 'utf8', timeout: 5_000, shell: false,
});
console.log(ollamaLookup.status === 0
  ? '[已安装] ollama：服务连通性与向量模型在第 1 天另行验证'
  : existsSync(path.join(projectRoot, '.data/tools/ollama/ollama'))
    ? '[已安装] 项目本地 Ollama：使用 scripts/dev.mjs 启动'
    : '[待准备] ollama：安装/配置后验证 Embedding');

for (const [relativePath, label] of [
  ['mvnw', 'Maven Wrapper'],
  ['.env', '本机配置文件（不读取内容）'],
  ['frontend/package-lock.json', '前端依赖锁文件'],
]) {
  console.log(`${existsSync(path.join(projectRoot, relativePath)) ? '[存在]' : '[待准备]'} ${label}`);
}

console.log(missingRequired === 0
  ? 'Java / Node.js / npm 满足运行要求；真实连接状态请通过服务连接页面或 verify-day1.mjs 验证。'
  : `存在 ${missingRequired} 项必需环境缺项。`);
process.exitCode = missingRequired === 0 ? 0 : 1;
