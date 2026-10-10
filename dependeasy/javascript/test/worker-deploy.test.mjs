import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { mergeConfiguration, deploy } from '../api/cloudflare.ts'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

test('local configuration overrides defaults without mutating either source', () => {
  const defaults = { observability: { enabled: true, logs: { enabled: true } }, buckets: ['default'] }
  const local = { name: 'worker', observability: { logs: { enabled: false } }, buckets: ['local'] }
  const before = JSON.stringify([defaults, local])
  assert.deepEqual(mergeConfiguration(defaults, local), {
    name: 'worker', observability: { enabled: true, logs: { enabled: false } }, buckets: ['local'],
  })
  assert.equal(JSON.stringify([defaults, local]), before)
})

test('cancellation reaches Wrangler and removes its temporary configuration', { timeout: 10000 }, async () => {
  const root = await mkdtemp(join(tmpdir(), 'dependeasy-worker-cancel-'))
  try {
    const directory = join(root, 'targets', 'sample')
    const bin = join(root, 'bin')
    await mkdir(directory, { recursive: true }); await mkdir(bin)
    await writeFile(join(root, 'wrangler.json'), '{}')
    await writeFile(join(directory, 'wrangler.json'), '{"name":"worker"}')
    await writeFile(join(bin, 'pnpm'), `#!${process.execPath}\nconsole.log('ready'); setInterval(() => {}, 1000);\n`, { mode: 0o755 })
    const child = spawn(process.execPath, [fileURLToPath(new URL('../cli/worker-deploy.ts', import.meta.url))], {
      cwd: directory, env: { ...process.env, PATH: bin + ':' + process.env.PATH, TARGET_ENV: 'prod' }, stdio: ['ignore', 'pipe', 'ignore'],
    })
    const exit = new Promise(resolve => child.once('exit', (code, signal) => resolve({ code, signal })))
    await new Promise((resolve, reject) => { child.stdout.once('data', resolve); child.once('error', reject) })
    child.kill('SIGTERM')
    assert.equal((await exit).code, 1)
    assert.equal((await readdir(directory)).filter(name => name.startsWith('wrangler.dependeasy-')).length, 0)
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('production and dev use isolated temporary configs and propagate command failures', async () => {
  const root = await mkdtemp(join(tmpdir(), 'dependeasy-worker-'))
  const original = { path: process.env.PATH, environment: process.env.TARGET_ENV }
  try {
    const directory = join(root, 'targets', 'sample')
    const bin = join(root, 'bin')
    await mkdir(directory, { recursive: true }); await mkdir(bin)
    await writeFile(join(root, 'wrangler.json'), JSON.stringify({ compatibility_date: '2026-10-01', vars: { default: 'yes' } }))
    const source = JSON.stringify({ name: 'production-worker', vars: { own: 'yes' } })
    await writeFile(join(directory, 'wrangler.json'), source)
    await writeFile(join(directory, 'wrangler.dev.json'), JSON.stringify({ name: 'dev-worker', vars: { dev: 'yes' } }))
    await writeFile(join(bin, 'pnpm'), `#!${process.execPath}\nconst fs = require('node:fs');\nconst args = process.argv.slice(2);\nconst config = args[args.indexOf('--config') + 1];\nfs.writeFileSync('observed.json', fs.readFileSync(config));\nif (args.includes('--fail')) process.exit(7);\n`, { mode: 0o755 })
    process.env.PATH = bin + ':' + original.path
    process.env.TARGET_ENV = 'prod'
    await deploy(directory, ['--dry-run'])
    assert.equal(JSON.parse(await readFile(join(directory, 'observed.json'))).vars.default, 'yes')
    process.env.TARGET_ENV = 'dev'
    await deploy(directory, ['--dry-run'])
    const dev = JSON.parse(await readFile(join(directory, 'observed.json')))
    assert.equal(dev.name, 'dev-worker'); assert.equal(dev.vars.default, undefined)
    await assert.rejects(deploy(directory, ['--fail']), /Wrangler exited 7/)
    assert.equal(await readFile(join(directory, 'wrangler.json'), 'utf8'), source)
    assert.equal((await readdir(directory)).filter(name => name.startsWith('wrangler.dependeasy-')).length, 0)
    process.env.TARGET_ENV = 'unknown'
    await assert.rejects(deploy(directory), /Unsupported environment/)
  } finally {
    process.env.PATH = original.path
    if (original.environment === undefined) delete process.env.TARGET_ENV; else process.env.TARGET_ENV = original.environment
    await rm(root, { recursive: true, force: true })
  }
})
