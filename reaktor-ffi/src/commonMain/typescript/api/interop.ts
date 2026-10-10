import { platform, registry } from '../kernel/registry.ts';
import type { Operations } from '../kernel/registry.ts';
import { decode, encode } from '../kernel/wire.ts';

export function exportModule<T extends Operations>(name: string, operations: T): void { registry.export(name, operations); }

/** Native modules and Kotlin exports share the same typed import surface. */
export const native = {
  module(name: string) {
    return {
      function<Request, Response>(operation: string): (request: Request) => Response {
        return request => {
          if (!platform.ReaktorNative) throw new Error('Native interop is unavailable in this runtime');
          return decode(platform.ReaktorNative.invoke(name, operation, encode(request))) as Response;
        };
      },
    };
  },
};
