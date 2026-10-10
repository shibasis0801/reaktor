export { exportModule, native } from './api/interop.ts';
export { StateFlow } from './api/flow.ts';
export type { Flow } from './api/flow.ts';
import { exportModule, native } from './api/interop.ts';
import { StateFlow } from './api/flow.ts';

// Keep the existing developer probe on the same public interop path.
exportModule('reaktor.diagnostics', { hello: native.module('reaktor.diagnostics').function('hello') });
Object.assign(globalThis, { Flow: StateFlow });
