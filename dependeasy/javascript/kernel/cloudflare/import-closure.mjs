import { createHash, randomUUID } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, realpathSync, writeFileSync, renameSync, statSync } from 'node:fs';
import { dirname, extname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const sourceExtensions = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs']);
const moduleExtensions = new Set(['.js', '.mjs', '.cjs']);

function inside(root, path) {
  const local = relative(root, realpathSync(path));
  if (local === '..' || local.startsWith(`..${sep}`) || isAbsolute(local)) throw new Error(`Bundle dependency escapes its root: ${path}`);
  return local.split(sep).join('/');
}

function sources(directory) {
  if (!existsSync(directory)) return [];
  if (statSync(directory).isFile()) return sourceExtensions.has(extname(directory)) ? [directory] : [];
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) return ['node_modules', 'build', 'dist', '.git'].includes(entry.name) ? [] : sources(path);
    return entry.isFile() && sourceExtensions.has(extname(path)) ? [path] : [];
  });
}

function references(path, text, strict = false) {
  const found = new Set();
  const source = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true);
  function visit(node) {
    if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && !node.isTypeOnly && !node.importClause?.isTypeOnly) {
      found.add(node.moduleSpecifier.text);
    }
    if (ts.isCallExpression(node) && (node.expression.kind === ts.SyntaxKind.ImportKeyword || ts.isIdentifier(node.expression) && node.expression.text === 'require')) {
      const argument = node.arguments[0];
      if (!argument || !ts.isStringLiteralLike(argument)) {
        if (strict) throw new Error(`Nonliteral module dependency cannot be sealed: ${path}`);
      } else found.add(argument.text);
    }
    if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'URL' && node.arguments?.length === 2 && ts.isStringLiteralLike(node.arguments[0]) && node.arguments[1].getText(source) === 'import.meta.url') {
      found.add(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  return [...found];
}

export function generateImportClosure({ workspace, bundle: bundlePath, sourcePaths, output: outputPath }) {
  const root = realpathSync(workspace);
  const bundle = realpathSync(bundlePath);
  inside(root, bundle);
  const packagePath = resolve(bundle, 'package.json');
  inside(bundle, packagePath);
  const metadata = JSON.parse(readFileSync(packagePath, 'utf8'));
  const packageName = metadata.name;
  if (typeof packageName !== 'string' || !packageName) throw new Error('The bundle package has no name');
  const modules = new Map();
  const roots = new Set();
  for (const sourcePath of sourcePaths) {
    const sourceRoot = resolve(root, sourcePath);
    if (existsSync(sourceRoot)) inside(root, sourceRoot);
    for (const path of sources(sourceRoot)) {
      inside(root, path);
      for (const dependency of references(path, readFileSync(path, 'utf8'))) {
        if (dependency === packageName || dependency.startsWith(`${packageName}/`)) roots.add(dependency);
      }
    }
  }
  function readModule(path) {
    const local = inside(bundle, path);
    if (modules.has(local)) return modules.get(local);
    const bytes = readFileSync(path);
    const dependencies = moduleExtensions.has(extname(path)) ? references(path, bytes.toString('utf8'), true).filter(value => value.startsWith('.')).map(value => {
      const dependency = resolve(dirname(path), value);
      if (!existsSync(dependency)) throw new Error(`Bundle dependency is missing: ${dependency}`);
      inside(bundle, dependency);
      return dependency;
    }) : [];
    const module = { path: local, sha256: createHash('sha256').update(bytes).digest('hex'), dependencies };
    modules.set(local, module);
    return module;
  }
  const imports = {};
  for (const entry of [...roots].sort()) {
    const visited = new Map();
    function collect(path) {
      const module = readModule(path);
      if (visited.has(module.path)) return;
      visited.set(module.path, { path: module.path, sha256: module.sha256 });
      module.dependencies.forEach(collect);
    }
    collect(packagePath);
    collect(resolve(bundle, entry === packageName ? metadata.main : entry.substring(packageName.length + 1)));
    imports[entry] = [...visited.values()].sort((a, b) => a.path.localeCompare(b.path));
  }
  const manifest = { format: 1, imports };
  const output = resolve(root, outputPath);
  inside(root, dirname(output));
  const temporary = `${output}.${randomUUID()}.next`;
  writeFileSync(temporary, `${JSON.stringify(manifest, null, 2)}\n`, { flag: 'wx' });
  renameSync(temporary, output);
  return { imports: roots.size, files: modules.size, manifest: output };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const values = new Map();
  const sourcePaths = [];
  for (let index = 2; index < process.argv.length; index += 2) {
    const key = process.argv[index];
    const value = process.argv[index + 1];
    if (!value || !['--root', '--bundle', '--output', '--source'].includes(key)) throw new Error(`Invalid import-closure argument: ${key}`);
    if (key === '--source') sourcePaths.push(value); else values.set(key, value);
  }
  for (const key of ['--root', '--bundle', '--output']) if (!values.has(key)) throw new Error(`Missing argument: ${key}`);
  console.log(JSON.stringify(generateImportClosure({ workspace: values.get('--root'), bundle: values.get('--bundle'), output: values.get('--output'), sourcePaths })));
}
