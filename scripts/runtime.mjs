import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';

export const root = fileURLToPath(new URL('../', import.meta.url));
export const data = path.join(root, '.data');
mkdirSync(data, { recursive: true });

export function configuration() {
  const envFile = path.join(root, '.env');
  const local = existsSync(envFile) ? parseEnv(readFileSync(envFile, 'utf8')) : {};
  const env = { ...process.env, ...local };
  if (env.MCP_SERVER_URL && !env.MCP_BASE_URL) {
    const endpoint = new URL(env.MCP_SERVER_URL);
    env.MCP_BASE_URL = endpoint.origin;
    env.MCP_ENDPOINT ||= endpoint.pathname;
  }
  env.MCP_BASE_URL ||= `http://127.0.0.1:${env.MCP_PORT || 8081}`;
  return env;
}

export function ollamaEnvironment(env) {
  // The local inference service does not need the chat / maps credentials.
  const result = { ...env };
  delete result.DEEPSEEK_API_KEY;
  delete result.AMAP_API_KEY;
  return { ...result, OLLAMA_HOST: new URL(env.OLLAMA_BASE_URL || 'http://127.0.0.1:11434').host,
    OLLAMA_MODELS: path.join(data, 'ollama', 'models'), OLLAMA_NO_CLOUD: '1' };
}

export function ollamaBinary() {
  const local = path.join(data, 'tools', 'ollama', 'ollama');
  return existsSync(local) ? local : 'ollama';
}

export async function waitFor(url, deadlineMs = 30_000) {
  const until = Date.now() + deadlineMs;
  while (Date.now() < until) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1500) });
      if (response.ok) return true;
    } catch { /* Startup readiness is retried until the deadline. */ }
    await new Promise(resolve => setTimeout(resolve, 350));
  }
  return false;
}
