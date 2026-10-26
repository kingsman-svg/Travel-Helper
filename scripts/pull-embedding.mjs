import { spawnSync } from 'node:child_process';
import { configuration, ollamaBinary, ollamaEnvironment } from './runtime.mjs';

const env = configuration();
const model = env.EMBEDDING_MODEL || 'bge-m3';
console.log(`下载本地 Embedding 模型 ${model}，模型缓存在项目 .data/ollama/models/。`);
const result = spawnSync(ollamaBinary(), ['pull', model], { env: ollamaEnvironment(env), stdio: 'inherit' });
process.exitCode = result.status ?? 1;
