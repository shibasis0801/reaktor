import { decode, encode } from './wire.ts';

export type Operations = Record<string, (request: never) => unknown>;
export interface NativeDispatch { invoke(module: string, operation: string, request: string): string }

export class ModuleRegistry {
  private readonly modules = new Map<string, Operations>();
  export(module: string, operations: Operations): void {
    if (!module || this.modules.has(module)) throw new Error(`Module '${module}' needs a unique nonempty name`);
    if (!Object.values(operations).every(operation => typeof operation === 'function')) throw new Error('Exported operations must be functions');
    this.modules.set(module, { ...operations });
  }
  invoke(module: string, operation: string, request: string): string {
    const operations = this.modules.get(module);
    const implementation = operations && Object.prototype.hasOwnProperty.call(operations, operation) ? operations[operation] : undefined;
    if (!implementation) throw new Error(`No TypeScript operation '${module}.${operation}' is exported`);
    const response: unknown = Reflect.apply(implementation, undefined, [decode(request)]);
    if (response && typeof response === 'object' && 'then' in response)
      throw new Error('Synchronous interop cannot return a Promise; use an explicit asynchronous transport');
    return encode(response);
  }
}

export const registry = new ModuleRegistry();
export const platform = globalThis as typeof globalThis & { ReaktorNative?: NativeDispatch; ReaktorTypeScript?: NativeDispatch };
platform.ReaktorTypeScript = registry;
