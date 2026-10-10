import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { workspaceDirectories } from '../api/workspace.ts';

test('workspace membership handles real YAML, exclusions and bounded paths', async () => {
  const root = await mkdtemp(join(tmpdir(), 'dependeasy-workspaces-'));
  try {
    for (const name of ['kept', 'excluded']) {
      await mkdir(join(root, 'packages', name), { recursive: true });
      await writeFile(join(root, 'packages', name, 'package.json'), '{}');
    }
    await writeFile(join(root, 'pnpm-workspace.yaml'), 'packages: ["packages/*", "!packages/excluded"] # inline list\n');
    assert.deepEqual(workspaceDirectories(root), ['packages/kept']);
    await writeFile(join(root, 'pnpm-workspace.yaml'), 'packages:\n  - ../outside\n');
    assert.throws(() => workspaceDirectories(root), /inside its root/);
    await writeFile(join(root, 'pnpm-workspace.yaml'), 'packages: [12]\n');
    assert.throws(() => workspaceDirectories(root), /must be strings/);
    await writeFile(join(root, 'pnpm-workspace.yaml'), 'packages: ["packages/*"\n');
    assert.throws(() => workspaceDirectories(root));
  } finally { await rm(root, { recursive: true, force: true }); }
});
