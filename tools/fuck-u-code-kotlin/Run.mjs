import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const tool = path.join(root, '.dev/tooling/fuck-u-code-kotlin-src');
const output = path.join(root, '.dev/slim/eff-u-code-kotlin');
const scope = JSON.parse(await fs.readFile(path.join(root, 'config/slim-scope.json'), 'utf8'));
await fs.mkdir(output, { recursive: true });
const snapshot = path.join(output, `input-${randomUUID()}`);
await fs.mkdir(snapshot);
const manifest = [];
try {
  const require = createRequire(path.join(tool, 'package.json'));
  const { glob } = await import(pathToFileURL(require.resolve('glob')));
  for (const directory of scope.production) {
    const relative = path.relative(root, path.resolve(root, directory));
    if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('Invalid source scope');
  }
  const files = await glob(scope.production.map(directory => directory + '/**/*'), {
    cwd: root, nodir: true, dot: true, follow: false, ignore: scope.exclude,
  });
  for (const relative of [...new Set(files)].sort()) {
    const source = path.join(root, relative);
    if ((await fs.lstat(source)).isSymbolicLink()) continue;
    const bytes = await fs.readFile(source);
    const target = path.join(snapshot, relative);
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, bytes);
    manifest.push({ path: relative.replaceAll('\\', '/'), sha256: createHash('sha256').update(bytes).digest('hex') });
  }
  const { configSchema, createRuntimeConfig } = await import(pathToFileURL(path.join(tool, 'dist/config/index.js')));
  const { createAnalyzer } = await import(pathToFileURL(path.join(tool, 'dist/analyzer/index.js')));
  const { VERSION } = await import(pathToFileURL(path.join(tool, 'dist/version.js')));
  const config = createRuntimeConfig(snapshot, configSchema.parse({
    include: scope.production.map(directory => directory + '/**/*'), exclude: scope.exclude,
    concurrency: 2, ai: { enabled: false }, output: { format: 'json', top: 20 },
  }));
  const result = await createAnalyzer(config).analyze();
  // Source hashes identify the frozen input. Reports point back to the corresponding workspace files.
  for (const file of result.fileResults) {
    file.parseResult.filePath = path.join(root, file.filePath);
    for (const metric of file.metrics) {
      for (const location of metric.locations ?? []) location.filePath = path.join(root, file.filePath);
    }
  }
  result.projectPath = root;
  const coverage = {};
  for (const file of result.fileResults) {
    const key = file.parseResult.language + '/' + file.parseResult.parserKind;
    coverage[key] = (coverage[key] ?? 0) + 1;
  }
  const metadata = {
    toolVersion: VERSION, upstream: '71e147315ad5c99ca8142ad7a036e2262f5c6475',
    patchSha256: createHash('sha256').update(await fs.readFile(new URL('./kotlin.patch', import.meta.url))).digest('hex'),
    aiEnabled: false, productionScope: scope, sourceManifest: manifest, parserCoverage: coverage,
  };
  await fs.writeFile(path.join(output, 'scope.json'), JSON.stringify(metadata, null, 2));
  await fs.writeFile(path.join(output, 'report.json'), JSON.stringify(result, (key, value) => key === 'content' ? undefined : value, 2));
  const worst = [...result.fileResults].sort((a, b) => a.score - b.score).slice(0, 10)
    .map(file => ({ file: file.filePath, score: file.score, functions: file.parseResult.functions.length }));
  console.log(JSON.stringify({ toolVersion: VERSION, totalFiles: result.totalFiles, analyzedFiles: result.analyzedFiles,
    skippedFiles: result.skippedFiles, parserCoverage: coverage, scoreScope: result.scoreScope,
    overallScore: result.overallScore, failedFiles: result.failedFiles, worst }, null, 2));
} finally {
  const resolved = path.resolve(snapshot);
  if (path.dirname(resolved) !== output || !path.basename(resolved).startsWith('input-')) {
    throw new Error('Refusing cleanup outside the owned snapshot directory');
  }
  await fs.rm(resolved, { recursive: true, force: true });
}
