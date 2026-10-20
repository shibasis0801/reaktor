export function installComposeWebProbe(options?: { maxSamples?: number; textureUploads?: boolean }): void;
export function instrumentComposeWeb(source: string): string;
export function composeWebSummary(snapshot: unknown, budgetMs?: number): Record<string, unknown>;
export function composeWebMetrics(summary: Record<string, unknown>, prefix?: string, scope?: Record<string, unknown>): Array<{ name: string; value: number; unit: string; domain: 'Runtime'; scope: Record<string, unknown> }>;
