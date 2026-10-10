import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';
import { checkTypes } from '../kernel/typescript/compiler.ts';
import { CommandFailure } from '../kernel/process/run.ts';

test('solution type checks reject errors in referenced projects and recover incrementally', async () => {
  const root = await mkdtemp(join(tmpdir(), 'dependeasy-types-'));
  const require = createRequire(import.meta.url);
  try {
    await mkdir(join(root, 'node_modules'));
    await symlink(dirname(require.resolve('typescript-native/package.json')), join(root, 'node_modules/typescript-native'), 'dir');
    await mkdir(join(root, 'app'));
    await writeFile(join(root, 'package.json'), '{"private":true}');
    await writeFile(join(root, 'tsconfig.json'), '{"files":[],"references":[{"path":"./app"}]}');
    await writeFile(join(root, 'app/tsconfig.json'), '{"compilerOptions":{"composite":true,"strict":true,"noEmit":true},"include":["*.ts"]}');
    await writeFile(join(root, 'app/value.ts'), 'const value: number = "wrong";');
    await assert.rejects(checkTypes(['--projects', 'tsconfig.json'], root), CommandFailure);
    await writeFile(join(root, 'app/value.ts'), 'const value: number = 1;');
    await checkTypes(['--projects', 'tsconfig.json'], root);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
