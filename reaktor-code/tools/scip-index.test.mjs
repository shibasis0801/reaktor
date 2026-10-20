import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./scip-index.mjs', import.meta.url));

const workspace = files => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'scip-index-')));
  for (const [path, text] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
  }
  return root;
};

const run = (...args) => JSON.parse(execFileSync(process.execPath, [script, ...args], { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim().split('\n').at(-1));

const project = {
  'package.json': '{"name":"demo","version":"1.0.0"}',
  'tsconfig.json': '{"compilerOptions":{"strict":true,"target":"ES2022","module":"ES2022","moduleResolution":"Bundler","noEmit":true},"include":["src"]}',
  'tests/tsconfig.json': '{"extends":"../tsconfig.json","include":["../src","."]}',
  'src/api.ts': [
    'export interface Port {',
    '  answer(value: number): number',
    '}',
    '',
    'export class Service implements Port {',
    '  answer(value: number): number { return value }',
    '}',
    '',
    'export class Loud extends Service {',
    '  override answer(value: number): number { return super.answer(value) * 2 }',
    '}',
    '',
  ].join('\n'),
  'src/use.ts': [
    "import { Loud, type Port } from './api'",
    '',
    'export const port: Port = new Loud()',
    '',
    'export function twice(value: number) {',
    '  return port.answer(port.answer(value))',
    '}',
    '',
    'function helper() {',
    '  return twice(2)',
    '}',
    '',
    'const named = helper()',
    'export { named }',
    '',
  ].join('\n'),
  'tests/use.test.ts': "import { twice } from '../src/use'\n\nexport const checked = twice(1) === 4\n",
};

test('facts list each declaration with its parent, supertypes and overrides, and each resolved use with the declaration it sits in', () => {
  const root = workspace(project);
  try {
    const summary = run('--facts', root, 'tsconfig.json', 'tests/tsconfig.json', join(root, 'out/facts.json'));
    assert.deepEqual(summary.compilers, ['typescript/5.9.3']);
    const facts = JSON.parse(readFileSync(join(root, 'out/facts.json'), 'utf8'));
    assert.equal(facts.format, 'reaktor-typescript-facts-v1');
    assert.deepEqual(facts.files.map(it => it.path).sort(), ['src/api.ts', 'src/use.ts', 'tests/use.test.ts'], 'a file both projects compile is listed once');
    const address = index => facts.addresses[index];
    const api = facts.files.find(it => it.path === 'src/api.ts');
    const declared = Object.fromEntries(api.declarations.map(it => [address(it.a), it]));
    const loud = declared['ts:demo src/`api.ts`/Loud#'];
    assert.deepEqual([loud.k, loud.n, loud.l, loud.e], ['class', 'Loud', 8, 10]);
    assert.deepEqual(loud.s.map(address), ['ts:demo src/`api.ts`/Service#']);
    assert.deepEqual(declared['ts:demo src/`api.ts`/Service#'].s.map(address), ['ts:demo src/`api.ts`/Port#'], 'implements is a supertype too');
    const loudAnswer = declared['ts:demo src/`api.ts`/Loud#answer().'];
    assert.deepEqual([loudAnswer.k, address(loudAnswer.p), loudAnswer.t], ['function', 'ts:demo src/`api.ts`/Loud#', 'number']);
    assert.ok(loudAnswer.o.map(address).includes('ts:demo src/`api.ts`/Service#answer().'), JSON.stringify(loudAnswer.o.map(address)));
    assert.equal(declared['ts:demo src/`api.ts`/Port#'].k, 'interface');
    const use = facts.files.find(it => it.path === 'src/use.ts');
    const port = use.declarations.find(it => it.n === 'port');
    assert.deepEqual([port.k, address(port.ta)], ['property', 'ts:demo src/`api.ts`/Port#']);
    assert.deepEqual(use.declarations.filter(it => it.p === undefined).map(it => [it.n, it.h ?? false]),
      [['port', false], ['twice', false], ['helper', true], ['named', false]], 'a top-level declaration the file neither exports nor lists in an export clause is hidden');
    const references = use.references.map(it => [it.l, it.c, address(it.t[0]), it.p === undefined ? null : address(it.p), it.f ?? 0]);
    assert.deepEqual(references.filter(it => it[0] === 0).map(it => [it[2], it[4]]), [['ts:demo src/`api.ts`/Loud#', 2], ['ts:demo src/`api.ts`/Port#', 2]], 'imports are flagged');
    assert.deepEqual(references.find(it => it[0] === 2 && it[2].endsWith('Loud#')), [2, 30, 'ts:demo src/`api.ts`/Loud#', 'ts:demo src/`use.ts`/port.', 1], 'new is a call of the class');
    assert.deepEqual(references.filter(it => it[0] === 5 && it[2].endsWith('answer().')).map(it => [it[1], it[3], it[4]]),
      [[14, 'ts:demo src/`use.ts`/twice().', 1], [26, 'ts:demo src/`use.ts`/twice().', 1]]);
    const test = facts.files.find(it => it.path === 'tests/use.test.ts');
    assert.ok(test.references.some(it => address(it.t[0]) === 'ts:demo src/`use.ts`/twice().' && (it.f & 1)), 'the test project resolves uses into the main sources');
    assert.match(test.digest, /^[0-9a-f]{64}$/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('an artifact covers several targets once each, binds what lies outside the source scope by digest, and names the first target', () => {
  const root = workspace({ ...project, 'build/generated/base.json': '{"compilerOptions":{"strict":true}}',
    'tsconfig.json': '{"extends":"./build/generated/base.json","compilerOptions":{"target":"ES2022","module":"ES2022","moduleResolution":"Bundler","noEmit":true},"include":["src"]}' });
  try {
    const summary = run(root, 'tsconfig.json', 'tests/tsconfig.json', join(root, 'out/index.json'));
    const artifact = JSON.parse(readFileSync(join(root, 'out/index.json'), 'utf8'));
    assert.equal(artifact.target, 'tsconfig.json');
    assert.deepEqual(artifact.targets, ['tsconfig.json', 'tests/tsconfig.json']);
    assert.equal(artifact.compiler, 'typescript/5.9.3');
    assert.deepEqual(artifact.index.documents.map(it => it.relative_path).sort(), ['src/api.ts', 'src/use.ts', 'tests/use.test.ts']);
    assert.deepEqual(Object.keys(artifact.binaryInputs), [join(root, 'build/generated/base.json')]);
    assert.ok(['src/api.ts', 'tests/tsconfig.json', 'package.json'].every(it => it in artifact.inputs), JSON.stringify(Object.keys(artifact.inputs)));
    assert.ok(artifact.index.documents.every(document => document.symbols.every(symbol => symbol.documentation === undefined)));
    assert.deepEqual(summary.left, []);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a target on an unqualified TypeScript is refused for an artifact and left out of facts with the reason', () => {
  const root = workspace({ ...project,
    'next/tsconfig.json': '{"include":["*.ts"]}', 'next/main.ts': 'export const next = 1\n',
    'next/node_modules/typescript/package.json': '{"name":"typescript","version":"9.9.9","main":"index.js"}',
    'next/node_modules/typescript/index.js': "module.exports = { version: '9.9.9' }\n" });
  try {
    assert.throws(() => execFileSync(process.execPath, [script, root, 'next/tsconfig.json', join(root, 'out/index.json')], { stdio: 'pipe' }), /9\.9\.9 is not qualified/);
    run('--facts', root, 'tsconfig.json', 'next/tsconfig.json', join(root, 'out/facts.json'));
    const facts = JSON.parse(readFileSync(join(root, 'out/facts.json'), 'utf8'));
    assert.deepEqual(facts.skipped, ['next/tsconfig.json: TypeScript 9.9.9 is not qualified for scip-typescript/0.4.0']);
    assert.deepEqual(facts.files.map(it => it.path).sort(), ['src/api.ts', 'src/use.ts']);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
