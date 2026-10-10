import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { parseExportFile, runWithEnvironmentFiles } from '../api/process.ts'
import { runCommand } from '../api/process.ts'

test('literal export parsing preserves quotes without evaluating expressions', () => {
  assert.deepEqual(parseExportFile(`export NAME='it'\\''s literal'\nexport VALUE="a\\"b"\nexport COMMAND=$(exit 9)\n# ignored`), {
    NAME: "it's literal", VALUE: 'a"b', COMMAND: '$(exit 9)',
  })
})

test('ordered environment files affect only the child and use the supplied working directory', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'dependeasy-environment-'))
  const previous = process.env.DEPENDEASY_ENV_FIXTURE
  try {
    await writeFile(join(directory, 'first.env'), "export DEPENDEASY_ENV_FIXTURE='first'\n")
    await writeFile(join(directory, 'second.env'), "export DEPENDEASY_ENV_FIXTURE='second'\n")
    await runWithEnvironmentFiles(directory, ['first.env', 'second.env', '--', process.execPath, '-e',
      "require('node:fs').writeFileSync('observed.json', JSON.stringify({cwd: process.cwd(), value: process.env.DEPENDEASY_ENV_FIXTURE}))"])
    const observed = JSON.parse(await readFile(join(directory, 'observed.json')))
    assert.equal(observed.value, 'second')
    assert.equal(await readFile(join(directory, 'first.env'), 'utf8'), "export DEPENDEASY_ENV_FIXTURE='first'\n")
    assert.equal(process.env.DEPENDEASY_ENV_FIXTURE, previous)
  } finally { await rm(directory, { recursive: true, force: true }) }
})

test('child exit status and launch failures reject and restore signal listeners', async () => {
  const listeners = process.listenerCount('SIGTERM')
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.exit(7)']), error => error.exitCode === 7)
  await assert.rejects(runCommand('/dependeasy-fixture-missing-command', []), error => error.code === 'ENOENT')
  assert.equal(process.listenerCount('SIGTERM'), listeners)
})
