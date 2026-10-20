#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const { scip } = require('@sourcegraph/scip-typescript/dist/src/scip.js');
const version = require('@sourcegraph/scip-typescript/package.json').version;
const provider = `scip-typescript/${version}`;
const qualified = ['5.9.3', '6.0.3'];
const maxSource = 524288;
const maxArtifact = 16777216;
const maxBinaryInputs = 256;
const constructorSuffix = '`<constructor>`().';

const options = process.argv.slice(2);
const facts = options[0] === '--facts';
const [workspace, ...rest] = facts ? options.slice(1) : options;
const output = rest.at(-1);
const targets = rest.slice(0, -1);
if (!workspace || !output || targets.length === 0)
  throw new Error('Usage: scip-index.mjs [--facts] WORKSPACE RELATIVE_TSCONFIG [RELATIVE_TSCONFIG...] OUTPUT');
const root = realpathSync(workspace);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const named = path => relative(root, resolve(path)).split(sep).join('/');
const local = path => {
  const name = named(path);
  if (isAbsolute(name) || name.split('/').some(part => ['..', '.git', '.gradle', 'node_modules', 'build', '.env'].includes(part))) return null;
  if (resolve(path) !== realpathSync(path)) throw new Error(`Symbolic source path: ${name}`);
  return name;
};
for (const target of targets)
  if (!existsSync(resolve(root, target)) || local(resolve(root, target)) !== target) throw new Error(`Target must be an existing canonical relative tsconfig path: ${target}`);

const groups = new Map();
for (const target of targets) {
  let compiler;
  try { compiler = createRequire(resolve(root, target)).resolve('typescript'); } catch { compiler = require.resolve('typescript'); }
  groups.set(compiler, [...(groups.get(compiler) ?? []), target]);
}
const versions = new Map([...groups.keys()].map(compiler => [compiler, require(compiler).version]));
const usable = [...groups].filter(([compiler]) => qualified.includes(versions.get(compiler)));
const refused = [...groups].filter(([compiler]) => !qualified.includes(versions.get(compiler)));
if (!facts && groups.size > 1) throw new Error(`The targets compile with different TypeScript installations: ${[...groups.keys()].join(', ')}`);
if (usable.length === 0 || (!facts && refused.length))
  throw new Error(`TypeScript ${[...new Set(versions.values())].join(', ')} is not qualified for ${provider}; qualified: ${qualified.join(', ')}`);
const ts = require(usable[0][0]);
const compilers = [...new Set(usable.map(([compiler]) => `typescript/${versions.get(compiler)}`))];

const destination = resolve(output);
mkdirSync(dirname(destination), { recursive: true });
const temporary = `${destination}.${process.pid}.tmp`;
const binaries = usable.map((_, at) => `${destination}.${process.pid}.${at}.scip`);
const started = Date.now();
try {
  const captured = facts ? null : capture();
  const documents = new Map();
  let index;
  usable.forEach(([compiler, group], at) => {
    const result = spawnSync(process.execPath, ['--max-old-space-size=8192', '--require', fileURLToPath(new URL('./typescript-from.cjs', import.meta.url)),
      require.resolve('@sourcegraph/scip-typescript'), 'index', '--cwd', root, '--output', binaries[at], '--no-progress-bar', '--max-file-byte-size', '512kb', ...group],
      { cwd: root, stdio: ['ignore', 2, 2], env: { ...process.env, REAKTOR_SCIP_TYPESCRIPT: compiler } });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`SCIP indexer exited ${result.status} for ${group.join(', ')}`);
    index = scip.Index.deserializeBinary(readFileSync(binaries[at]));
    if (index.metadata.project_root !== pathToFileURL(root).href) throw new Error('Indexer workspace differs from requested workspace');
    if (version !== '0.4.0' || index.metadata.tool_info.name !== 'scip-typescript' || index.metadata.tool_info.version !== version) throw new Error('Unqualified SCIP indexer');
    for (const document of index.documents) documents.set(document.relative_path, [...(documents.get(document.relative_path) ?? []), document]);
  });
  const left = refused.flatMap(([compiler, group]) => group.map(target => `${target}: TypeScript ${versions.get(compiler)} is not qualified for ${provider}`));
  const summary = facts ? writeFacts(documents, left) : writeArtifact(index, documents, captured);
  process.stdout.write(JSON.stringify({ ...summary, provider, compilers, targets }) + '\n');
} finally {
  binaries.forEach(binary => rmSync(binary, { force: true }));
  rmSync(temporary, { force: true });
}

function capture() {
  const inputs = new Map();
  const binaryInputs = new Map();
  const bind = path => {
    const absolute = realpathSync(path);
    if (binaryInputs.has(absolute)) return;
    if (binaryInputs.size >= maxBinaryInputs) throw new Error(`More than ${maxBinaryInputs} inputs lie outside the source scope`);
    binaryInputs.set(absolute, hash(readFileSync(absolute)));
  };
  const read = path => {
    const name = local(path);
    if (name === null) {
      if (path.endsWith('.json') && !realpathSync(path).split(sep).includes('node_modules')) bind(path);
      return;
    }
    if (statSync(path).size > maxSource) return bind(path);
    if (!inputs.has(name) && inputs.size >= 4096) throw new Error(`Input exceeds Code scope: ${name}`);
    const bytes = readFileSync(path);
    if (bytes.includes(0)) throw new Error(`Binary input: ${name}`);
    inputs.set(name, hash(bytes));
  };
  const host = { ...ts.sys, onUnRecoverableConfigFileDiagnostic: diagnostic => { throw new Error(ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')); },
    readFile: path => { const text = ts.sys.readFile(path); if (text !== undefined) read(path); return text; } };
  const projects = new Map();
  const parse = configuration => {
    if (projects.has(configuration)) return;
    const parsed = ts.getParsedCommandLineOfConfigFile(configuration, {}, host);
    if (!parsed || parsed.errors.length) throw new Error(`TypeScript configuration is invalid: ${named(configuration)}`);
    projects.set(configuration, parsed);
    for (const reference of parsed.projectReferences ?? []) parse(ts.resolveProjectReferencePath(reference));
  };
  targets.forEach(target => parse(resolve(root, target)));
  for (const parsed of projects.values()) {
    const compilerHost = ts.createCompilerHost(parsed.options);
    const readFile = compilerHost.readFile.bind(compilerHost);
    compilerHost.readFile = path => { const text = readFile(path); if (text !== undefined) read(path); return text; };
    const program = ts.createProgram({ rootNames: parsed.fileNames, options: parsed.options, host: compilerHost, projectReferences: parsed.projectReferences });
    program.getSourceFiles().forEach(file => read(file.fileName));
    const errors = ts.getPreEmitDiagnostics(program).filter(item => item.category === ts.DiagnosticCategory.Error);
    if (errors.length) throw new Error(ts.formatDiagnosticsWithColorAndContext(errors.slice(0, 20), { getCanonicalFileName: x => x, getCurrentDirectory: () => root, getNewLine: () => '\n' }));
  }
  for (const name of ['package.json', 'package-lock.json', 'pnpm-lock.yaml', 'yarn.lock']) if (existsSync(resolve(root, name))) read(resolve(root, name));
  if (inputs.has(named(destination))) throw new Error('Artifact would replace a source input');
  return { inputs, binaryInputs };
}

function writeArtifact(index, documents, { inputs, binaryInputs }) {
  const occurrence = it => `${it.range.join(',')}|${it.symbol}|${it.symbol_roles}`;
  const kept = [];
  const left = [];
  for (const [path, copies] of documents) {
    if (!inputs.has(path)) { left.push(path); continue; }
    const occurrences = new Map();
    const symbols = new Map();
    for (const copy of copies.map(it => it.toObject())) {
      copy.occurrences.forEach(it => { delete it.diagnostics; occurrences.set(occurrence(it), it); });
      copy.symbols.forEach(it => {
        const known = symbols.get(it.symbol);
        if (!known) symbols.set(it.symbol, { symbol: it.symbol, relationships: it.relationships ?? [] });
        else (it.relationships ?? []).forEach(relation => { if (!known.relationships.some(other => other.symbol === relation.symbol)) known.relationships.push(relation); });
      });
    }
    kept.push({ relative_path: path, language: copies[0].language, position_encoding: 2, occurrences: [...occurrences.values()], symbols: [...symbols.values()] });
  }
  if (kept.length === 0) throw new Error('No indexed source is a bound input');
  for (const [name, digest] of inputs) if (hash(readFileSync(resolve(root, name))) !== digest) throw new Error(`Source changed during indexing: ${name}`);
  for (const [path, digest] of binaryInputs) if (hash(readFileSync(path)) !== digest) throw new Error(`Input changed during indexing: ${path}`);
  const metadata = index.metadata.toObject();
  const artifact = JSON.stringify({ format: 'reaktor-scip-v1', target: targets[0], targets, compiler: `typescript/${ts.version}`,
    inputs: Object.fromEntries([...inputs].sort()), binaryInputs: Object.fromEntries([...binaryInputs].sort()), index: { metadata, documents: kept } });
  if (Buffer.byteLength(artifact) > maxArtifact) throw new Error('SCIP JSON exceeds the bounded artifact size; index fewer targets, or use --facts');
  writeFileSync(temporary, artifact);
  renameSync(temporary, destination);
  return { artifact: destination, inputs: inputs.size, binaryInputs: binaryInputs.size, documents: kept.length, left, digest: hash(artifact) };
}

function writeFacts(documents, left) {
  const global = symbol => symbol && !symbol.startsWith('local ');
  const declarable = symbol => global(symbol) && !/[/)\]]$/.test(symbol) && !symbol.endsWith(constructorSuffix);
  const defined = new Set();
  for (const copies of documents.values()) for (const document of copies) for (const it of document.occurrences)
    if (it.symbol_roles & 1 && declarable(it.symbol)) defined.add(it.symbol);
  const addresses = new Map();
  const address = symbol => {
    const parts = symbol.split(' ');
    const id = `ts:${parts[2].replace(/@/g, '')} ${parts.slice(4).join(' ')}`.replace(/@/g, '%40');
    if (!addresses.has(id)) addresses.set(id, addresses.size);
    return addresses.get(id);
  };
  const modules = new Map();
  const module = path => {
    const directory = dirname(path);
    if (!modules.has(directory)) modules.set(directory, existsSync(join(root, directory, 'package.json')) || directory === '.' ? (directory === '.' ? '' : directory) : module(directory));
    return modules.get(directory);
  };
  const files = [];
  const skipped = [...left];
  for (const [path, copies] of documents) {
    const absolute = resolve(root, path);
    try {
      if (local(absolute) === null) { skipped.push(`${path}: outside the source scope`); continue; }
      const bytes = readFileSync(absolute);
      if (bytes.length > maxSource) { skipped.push(`${path}: larger than 512 KiB`); continue; }
      if (statSync(absolute).mtimeMs > started) { skipped.push(`${path}: changed during indexing`); continue; }
      files.push({ path, digest: hash(bytes), module: module(path), ...file(path, bytes.toString('utf8'), copies, defined, global, declarable, address) });
    } catch (failure) {
      skipped.push(`${path}: ${failure.message ?? failure}`.slice(0, 300));
    }
  }
  if (named(destination).split('/')[0] !== '..' && documents.has(named(destination))) throw new Error('Facts would replace a source input');
  const text = JSON.stringify({ format: 'reaktor-typescript-facts-v1', provider, compilers, targets, addresses: [...addresses.keys()], files, skipped });
  writeFileSync(temporary, text);
  renameSync(temporary, destination);
  return { facts: destination, files: files.length, declarations: files.reduce((sum, it) => sum + it.declarations.length, 0),
    references: files.reduce((sum, it) => sum + it.references.length, 0), skipped: skipped.length, digest: hash(text) };
}

function file(path, text, copies, defined, global, declarable, address) {
  const kind = ts.SyntaxKind;
  const source = ts.createSourceFile(resolve(root, path), text, ts.ScriptTarget.Latest, true,
    path.endsWith('.tsx') ? ts.ScriptKind.TSX : path.endsWith('.jsx') ? ts.ScriptKind.JSX : /\.[cm]?js$/.test(path) ? ts.ScriptKind.JS : ts.ScriptKind.TS);
  const declared = new Set([kind.ClassDeclaration, kind.ClassExpression, kind.InterfaceDeclaration, kind.EnumDeclaration, kind.TypeAliasDeclaration, kind.ModuleDeclaration,
    kind.FunctionDeclaration, kind.FunctionExpression, kind.MethodDeclaration, kind.MethodSignature, kind.EnumMember, kind.VariableDeclaration, kind.PropertyAssignment,
    kind.ShorthandPropertyAssignment, kind.PropertyDeclaration, kind.PropertySignature, kind.GetAccessor, kind.SetAccessor, kind.BindingElement, kind.Parameter]);
  const names = new Map();
  const calls = new Set();
  const imports = [];
  const rightmost = node => node && (ts.isIdentifier(node) ? node : ts.isPropertyAccessExpression(node) ? node.name : ts.isQualifiedName(node) ? node.right : null);
  const callee = node => {
    while (node && (ts.isParenthesizedExpression(node) || ts.isNonNullExpression(node))) node = node.expression;
    const name = rightmost(node);
    if (name) calls.add(name.getStart(source));
  };
  const visit = node => {
    if (declared.has(node.kind) && node.name) names.set(node.name.getStart(source), node);
    if (ts.isCallExpression(node) || ts.isNewExpression(node) || ts.isDecorator(node)) callee(node.expression);
    else if (ts.isTaggedTemplateExpression(node)) callee(node.tag);
    else if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) callee(node.tagName);
    if (ts.isImportDeclaration(node) || ts.isImportEqualsDeclaration(node) || (ts.isExportDeclaration(node) && node.moduleSpecifier)) imports.push([node.getStart(source), node.getEnd()]);
    ts.forEachChild(node, visit);
  };
  visit(source);
  const offset = range => source.getPositionOfLineAndCharacter(range[0], range[1]);
  const seen = new Set();
  const occurrences = [];
  for (const document of copies) for (const it of document.occurrences) {
    const key = `${it.range.join(',')}|${it.symbol}|${it.symbol_roles}`;
    if (!seen.has(key)) { seen.add(key); occurrences.push(it); }
  }
  const at = new Map();
  for (const it of occurrences) if (declarable(it.symbol) && !at.has(offset(it.range))) at.set(offset(it.range), it.symbol);
  const related = new Map();
  for (const document of copies) for (const symbol of document.symbols)
    related.set(symbol.symbol, [...(related.get(symbol.symbol) ?? []), ...symbol.relationships.filter(it => it.is_implementation && it.is_reference).map(it => it.symbol)]);
  const resolved = node => { const symbol = node && at.get(node.getStart(source)); return symbol && defined.has(symbol) ? address(symbol) : undefined; };
  const functional = node => node.initializer && (ts.isArrowFunction(node.initializer) || ts.isFunctionExpression(node.initializer));
  const shown = (node, symbol) => {
    if (!node) return symbol.endsWith('().') ? 'function' : symbol.endsWith('#') ? 'class' : 'property';
    switch (node.kind) {
      case kind.ClassDeclaration: case kind.ClassExpression: return 'class';
      case kind.InterfaceDeclaration: return 'interface';
      case kind.EnumDeclaration: return 'enum';
      case kind.TypeAliasDeclaration: return 'typealias';
      case kind.ModuleDeclaration: return 'object';
      case kind.FunctionDeclaration: case kind.FunctionExpression: case kind.MethodDeclaration: case kind.MethodSignature: return 'function';
      case kind.EnumMember: return 'enum entry';
      case kind.VariableDeclaration: case kind.PropertyAssignment: case kind.PropertyDeclaration: return functional(node) ? 'function' : 'property';
      default: return 'property';
    }
  };
  const nodes = new Map();
  const found = [];
  for (const it of occurrences) {
    if (!(it.symbol_roles & 1) || !declarable(it.symbol)) continue;
    const node = names.get(offset(it.range));
    const row = { a: address(it.symbol), k: shown(node, it.symbol), n: node?.name?.getText(source) ?? it.symbol.match(/(?:`([^`]+)`|([^/#.:`]+))(?:\(\)\.|[.#:])$/)?.slice(1).find(Boolean) ?? '',
      l: it.range[0], c: it.range[1], e: node ? source.getLineAndCharacterOfPosition(node.getEnd()).line : it.range[0] };
    if (node && !nodes.has(node)) nodes.set(node, row.a);
    found.push({ row, node, symbol: it.symbol });
  }
  const declarations = found.map(({ row, node, symbol }) => {
    for (let parent = node?.parent; parent; parent = parent.parent) if (nodes.has(parent)) { row.p = nodes.get(parent); break; }
    if (node?.type) {
      row.t = node.type.getText(source).replace(/\s+/g, ' ').slice(0, 200);
      const type = ts.isTypeReferenceNode(node.type) ? resolved(rightmost(node.type.typeName)) : undefined;
      if (type !== undefined) row.ta = type;
    }
    const supertypes = [...new Set((node?.heritageClauses ?? []).flatMap(clause => clause.types).map(it => resolved(rightmost(it.expression))).filter(it => it !== undefined))];
    if (supertypes.length) row.s = supertypes;
    const overrides = [...new Set((related.get(symbol) ?? []).filter(it => defined.has(it)).map(address))];
    if (overrides.length) row.o = overrides;
    return row;
  });
  const spans = [...nodes].map(([node, owner]) => [node.getStart(source), node.getEnd(), owner]).sort((x, y) => x[0] - y[0] || y[1] - x[1]);
  const within = position => {
    let owner;
    for (const [start, end, it] of spans) { if (start > position) break; if (position < end) owner = it; }
    return owner;
  };
  const references = [];
  const used = new Set();
  for (const it of occurrences) {
    if (it.symbol_roles & 1 || !global(it.symbol)) continue;
    const constructed = it.symbol.endsWith(constructorSuffix);
    const symbol = constructed ? it.symbol.slice(0, -constructorSuffix.length) : it.symbol;
    if (!defined.has(symbol)) continue;
    const position = offset(it.range);
    if (used.has(`${position}|${symbol}`)) continue;
    used.add(`${position}|${symbol}`);
    const row = { l: it.range[0], c: it.range[1], t: [address(symbol)] };
    const owner = within(position);
    if (owner !== undefined) row.p = owner;
    const flags = (constructed || calls.has(position) ? 1 : 0) | (imports.some(([start, end]) => position >= start && position < end) ? 2 : 0);
    if (flags) row.f = flags;
    references.push(row);
  }
  return { declarations, references };
}
