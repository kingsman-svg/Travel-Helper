import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { root, data } from './runtime.mjs';

for (const [command, args, cwd] of [
  [path.join(root, 'mvnw'), ['-B', '-ntp', 'package'], root],
  ['npm', ['run', 'build'], path.join(root, 'frontend')],
]) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit',
    env: { ...process.env, MAVEN_USER_HOME: path.join(data, 'm2') } });
  if (result.status !== 0) process.exit(result.status ?? 1);
}
