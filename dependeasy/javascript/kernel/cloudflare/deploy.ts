import { readFile, writeFile, unlink } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { runCommand } from '../process/run.ts'

export type Configuration = { [key: string]: string | number | boolean | null | Configuration | unknown[] };

export function mergeConfiguration(defaults: Configuration, local: Configuration): Configuration {
  const result = { ...defaults }
  for (const [key, value] of Object.entries(local)) {
    result[key] = value && typeof value === 'object' && !Array.isArray(value)
      ? mergeConfiguration(typeof defaults[key] === 'object' && !Array.isArray(defaults[key]) ? defaults[key] as Configuration ?? {} : {}, value) : value
  }
  return result
}

export async function deploy(directory = process.cwd(), args = process.argv.slice(2)) {
  const environment = process.env.TARGET_ENV ?? 'prod'
  if (!['dev', 'prod'].includes(environment)) throw new Error(`Unsupported environment '${environment}'`)
  const read = async (path: string): Promise<Configuration> => JSON.parse(await readFile(path, 'utf8'))
  const config = environment === 'dev' ? await read(resolve(directory, 'wrangler.dev.json'))
    : mergeConfiguration(await read(resolve(directory, '../../wrangler.json')), await read(resolve(directory, 'wrangler.json')))
  const path = resolve(directory, `wrangler.dependeasy-${randomUUID()}.json`)
  try {
    await writeFile(path, JSON.stringify(config, null, 2) + '\n', { flag: 'wx' })
    await runCommand('pnpm', ['exec', 'wrangler', 'deploy', '--minify', '--config', path, ...args], { cwd: directory, label: 'Wrangler' })
  } finally { await unlink(path).catch((error: NodeJS.ErrnoException) => { if (error.code !== 'ENOENT') throw error }) }
}

