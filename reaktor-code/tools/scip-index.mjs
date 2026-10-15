#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const ts = require('typescript');
const { scip } = require('@sourcegraph/scip-typescript/dist/src/scip.js');
const [workspace, target, output] = process.argv.slice(2);
if (!workspace || !target || !output || process.argv.length !== 5)
  throw new Error('Usage: scip-index.mjs WORKSPACE RELATIVE_TSCONFIG OUTPUT_ARTIFACT');
const root = realpathSync(workspace);
const configuration = resolve(root, target);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const local = path => {
  const name = relative(root, resolve(path)).split(sep).join('/');
  if (isAbsolute(name) || name.split('/').some(part => ['..', '.git', '.gradle', 'node_modules', 'build', '.env'].includes(part))) return null;
  if (resolve(path) !== realpathSync(path)) throw new Error(`Symbolic source path: ${name}`);
  return name;
};
if (local(configuration) !== target) throw new Error('Target must be an existing canonical relative tsconfig path');
const files = new Map();
const capture = path => {
  const name = local(path);
  if (name === null) return;
  if ((!files.has(name) && files.size >= 4096) || statSync(path).size > 524288) throw new Error(`Input exceeds Code scope: ${name}`);
  const bytes = readFileSync(path);
  if (bytes.includes(0)) throw new Error(`Binary input: ${name}`);
  files.set(name, hash(bytes));
};
const host = { ...ts.sys, onUnRecoverableConfigFileDiagnostic: diagnostic => { throw new Error(ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')); },
  readFile: path => { const text = ts.sys.readFile(path); if (text !== undefined) capture(path); return text; } };
const parsed = ts.getParsedCommandLineOfConfigFile(configuration, {}, host);
if (!parsed || parsed.errors.length) throw new Error('TypeScript target configuration is invalid');
if (parsed.projectReferences?.length) throw new Error('Index one target without project references; multi-project qualification is pending');
const compilerHost = ts.createCompilerHost(parsed.options);
const read = compilerHost.readFile.bind(compilerHost);
compilerHost.readFile = path => { const text = read(path); if (text !== undefined) capture(path); return text; };
const program = ts.createProgram(parsed.fileNames, parsed.options, compilerHost);
program.getSourceFiles().forEach(file => capture(file.fileName));
for (const name of ['package.json', 'package-lock.json', 'pnpm-lock.yaml', 'yarn.lock']) {
  const file = resolve(root, name);
  if (existsSync(file)) capture(file);
}
const diagnostics = ts.getPreEmitDiagnostics(program);
if (diagnostics.some(item => item.category === ts.DiagnosticCategory.Error))
  throw new Error(ts.formatDiagnosticsWithColorAndContext(diagnostics.slice(0, 20), { getCanonicalFileName: x => x, getCurrentDirectory: () => root, getNewLine: () => '\n' }));
const destination = resolve(output);
if (files.has(relative(root, destination).split(sep).join('/'))) throw new Error('Artifact would replace a source input');
mkdirSync(dirname(destination), { recursive: true });
const binary = `${destination}.${process.pid}.scip`;
const temporary = `${destination}.${process.pid}.tmp`;
try {
  const result = spawnSync(process.execPath, [require.resolve('@sourcegraph/scip-typescript'), 'index', '--cwd', root, '--output', binary, target], { cwd: root, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`SCIP indexer exited ${result.status}`);
  if (statSync(binary).size > 16777216) throw new Error('SCIP binary exceeds the bounded artifact size');
  const index = scip.Index.deserializeBinary(readFileSync(binary)).toObject();
  if (index.metadata.project_root !== pathToFileURL(root).href) throw new Error('Indexer workspace differs from requested workspace');
  const version = require('@sourcegraph/scip-typescript/package.json').version;
  if (version !== '0.4.0' || index.metadata.tool_info.name !== 'scip-typescript' || index.metadata.tool_info.version !== version) throw new Error('Unqualified SCIP indexer');
  for (const document of index.documents) {
    if (!files.has(document.relative_path)) throw new Error(`Indexer returned an unbound source: ${document.relative_path}`);
    document.position_encoding = 2;
  }
  for (const [name, digest] of files) if (hash(readFileSync(resolve(root, name))) !== digest) throw new Error(`Source changed during indexing: ${name}`);
  const artifact = JSON.stringify({ format: 'reaktor-scip-v1', target, compiler: `typescript/${ts.version}`, inputs: Object.fromEntries([...files].sort()), index });
  if (Buffer.byteLength(artifact) > 16777216) throw new Error('SCIP JSON exceeds the bounded artifact size');
  writeFileSync(temporary, artifact);
  renameSync(temporary, destination);
  process.stdout.write(JSON.stringify({ artifact: destination, provider: `scip-typescript/${version}`, compiler: ts.version,
    target, inputs: files.size, documents: index.documents.length, digest: hash(artifact) }) + '\n');
} finally {
  rmSync(binary, { force: true });
  rmSync(temporary, { force: true });
}
