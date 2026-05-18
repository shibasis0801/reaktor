import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { lstatSync, mkdirSync, readFileSync, symlinkSync, unlinkSync } from "node:fs"

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = join(__dirname, "..")
const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf8"))
const nodeModules = join(root, "node_modules")
const linkPath = join(nodeModules, pkg.name)

mkdirSync(nodeModules, { recursive: true })

try {
  const current = lstatSync(linkPath)
  if (!current.isSymbolicLink()) {
    unlinkSync(linkPath)
    symlinkSync("..", linkPath, "dir")
  }
} catch {
  symlinkSync("..", linkPath, "dir")
}
