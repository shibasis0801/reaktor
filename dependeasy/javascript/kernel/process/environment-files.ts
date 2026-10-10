import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { runCommand } from './run.ts'

function unquote(value: string) {
  const trimmed = value.trim()
  if (trimmed.startsWith("'") && trimmed.endsWith("'")) return trimmed.slice(1, -1).replaceAll("'\\''", "'")
  if (trimmed.startsWith('"') && trimmed.endsWith('"')) return trimmed.slice(1, -1).replaceAll('\\"', '"')
  return trimmed
}

/** Read literal export assignments; never evaluate shell expressions or change the parent environment. */
export function parseExportFile(source: string): Record<string, string> {
  return Object.fromEntries(source.split(/\r?\n/).flatMap(line => {
    const match = line.match(/^export\s+([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
    return match ? [[match[1], unquote(match[2])]] : []
  }))
}

export async function runWithEnvironmentFiles(directory: string, arguments_ = process.argv.slice(2)) {
  const separator = arguments_.indexOf('--')
  if (separator < 1 || !arguments_[separator + 1]) throw new Error('Expected <env-file>... -- <command> [args...]')
  const environment = { ...process.env }
  for (const file of arguments_.slice(0, separator)) {
    Object.assign(environment, parseExportFile(await readFile(resolve(directory, file), 'utf8')))
  }
  await runCommand(arguments_[separator + 1], arguments_.slice(separator + 2), { cwd: directory, env: environment })
}
