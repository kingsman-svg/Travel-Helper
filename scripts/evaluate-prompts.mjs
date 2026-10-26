import { spawn } from 'node:child_process';
import { configuration, root } from './runtime.mjs';
const env = configuration();
if (!env.DEEPSEEK_API_KEY) throw new Error('请先在本机 .env 配置 DeepSeek 密钥。');
console.log('开始五组 v1/v2 Prompt 对照，共 10 次真实 DeepSeek 请求；报告不记录密钥。');
const child = spawn('./mvnw', ['-B', '-ntp', '-pl', 'backend', '-Dtest=PromptEvaluation', 'test'], { cwd: root, env, stdio: 'inherit' });
child.on('exit', code => process.exit(code || 0));
