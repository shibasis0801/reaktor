import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { runCommand } from '../process/run.ts';

/** The workspace owns the compiler; the API compatibility package is a separate dependency. */
export async function checkTypes(arguments_: readonly string[], root = process.cwd()): Promise<void> {
  const require = createRequire(join(root, 'package.json'));
  const compiler = join(dirname(require.resolve('typescript-native/package.json')), 'bin/tsc');
  if (arguments_[0] !== '--projects') {
    await runCommand(process.execPath, [compiler, ...arguments_], { cwd: root });
    return;
  }
  const projects = arguments_.slice(1);
  if (!projects.length) throw new Error('Type checking needs at least one project');
  await runCommand(process.execPath, [compiler, '--build', '--noEmit', ...projects], { cwd: root });
}
