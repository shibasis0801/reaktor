import { spawn } from 'node:child_process';
import type { SpawnOptions } from 'node:child_process';
import { basename } from 'node:path';
import { constants } from 'node:os';

export interface CommandOptions extends SpawnOptions { label?: string }
export class CommandFailure extends Error {
  readonly exitCode: number;
  readonly signal: NodeJS.Signals | null;
  constructor(label: string, code: number | null, signal: NodeJS.Signals | null) {
    super(`${label} exited ${signal ?? code}`);
    this.exitCode = signal ? 128 + constants.signals[signal] : code ?? 1;
    this.signal = signal;
  }
}

/** Argument vectors, child cancellation and listener ownership live in the kernel. */
export function runCommand(command: string, arguments_: readonly string[],
  { label = basename(command), ...options }: CommandOptions = {}): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, arguments_, { stdio: 'inherit', ...options });
    const signals = (['SIGINT', 'SIGTERM'] as const).map(signal =>
      [signal, () => { child.kill(signal); }] as const);
    for (const [signal, handler] of signals) process.once(signal, handler);
    let settled = false;
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      for (const [signal, handler] of signals) process.removeListener(signal, handler);
      error ? reject(error) : resolve();
    };
    child.once('error', finish);
    child.once('exit', (code, signal) => finish(code === 0 ? undefined : new CommandFailure(label, code, signal)));
  });
}
