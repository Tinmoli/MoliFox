import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = path.join(root, '.dev/tooling/fuck-u-code-kotlin-src');
const patch = fileURLToPath(new URL('./kotlin.patch', import.meta.url));
const upstream = '71e147315ad5c99ca8142ad7a036e2262f5c6475';
const buildTemp = path.join(root, '.dev/work/eff-u-code-tmp');
await fs.mkdir(buildTemp, { recursive: true });
const env = { ...process.env, npm_config_cache: path.join(root, '.dev/npm-cache'),
  TEMP: buildTemp, TMP: buildTemp, TMPDIR: buildTemp };
function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, env, stdio: 'inherit', windowsHide: true, ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} failed (${result.status})`);
  return result;
}
try { await fs.access(path.join(source, '.git')); } catch {
  run('git', ['clone', '--no-checkout', 'https://github.com/Done-0/fuck-u-code.git', source]);
  run('git', ['-C', source, 'checkout', '-b', 'codex/kotlin-support', upstream]);
}
const reverse = spawnSync('git', ['-C', source, 'apply', '--unidiff-zero', '--reverse', '--check', patch],
  { cwd: root, env, stdio: 'pipe', windowsHide: true });
if (reverse.status !== 0) {
  const head = run('git', ['-C', source, 'rev-parse', 'HEAD'], { stdio: 'pipe', encoding: 'utf8' }).stdout.trim();
  if (head !== upstream) throw new Error('Tool checkout differs from pinned upstream; refusing to reset it');
  run('git', ['-C', source, 'diff', '--quiet']);
  run('git', ['-C', source, 'diff', '--cached', '--quiet']);
  run('git', ['-C', source, 'apply', '--unidiff-zero', '--check', patch]);
  run('git', ['-C', source, 'apply', '--unidiff-zero', patch]);
}
const npm = process.env.npm_execpath ?? path.join(path.dirname(process.execPath), 'node_modules/npm/bin/npm-cli.js');
await fs.access(npm);
run(process.execPath, [npm, 'ci', '--prefix', source, '--ignore-scripts', '--no-audit', '--no-fund']);
run(process.execPath, [npm, 'run', 'build', '--prefix', source]);
if (process.argv.includes('--test')) {
  const testBase = path.join(root, 'tests/.tmp');
  await fs.mkdir(testBase, { recursive: true });
  const temporary = await fs.mkdtemp(path.join(testBase, 'eff-u-code-'));
  try {
    run(process.execPath, [path.join(source, 'node_modules/vitest/vitest.mjs'), 'run', '--root', source], {
      env: { ...env, TEMP: temporary, TMP: temporary, TMPDIR: temporary },
    });
  } finally {
    if (path.dirname(path.resolve(temporary)) !== testBase || !path.basename(temporary).startsWith('eff-u-code-')) {
      throw new Error('Refusing cleanup outside the owned test directory');
    }
    await fs.rm(temporary, { recursive: true, force: true });
  }
}
console.log('Ready: node tools/fuck-u-code-kotlin/Run.mjs');
