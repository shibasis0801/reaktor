export interface MutationCounts {
  total: number;
  structural: number;
  added: number;
  removed: number;
  attributes: number;
  text: number;
  camera: number;
  byAttribute: Record<string, number>;
  byTarget: Record<string, number>;
  firstAt: number | null;
  lastAt: number | null;
}

export interface MutationProbeOptions {
  root: string;
  camera?: string;
}

export function installMutationProbe(options: MutationProbeOptions): void {
  const scope = globalThis as unknown as { __reaktorMutations?: unknown };
  if (scope.__reaktorMutations) return;
  const empty = (): MutationCounts => ({ total: 0, structural: 0, added: 0, removed: 0, attributes: 0, text: 0, camera: 0, byAttribute: {}, byTarget: {}, firstAt: null, lastAt: null });
  let counts = empty();
  let observed: Element | null = null;
  const describe = (node: Node | null): string => {
    const element = (node && node.nodeType === 1 ? node : node?.parentElement) as Element | null;
    if (!element) return '#text';
    const classes = typeof element.className === 'string' ? element.className.split(/\s+/).filter(Boolean).slice(0, 2).join('.') : '';
    return `${element.tagName.toLowerCase()}${classes ? `.${classes}` : ''}`;
  };
  const record = (mutations: MutationRecord[]) => {
    const now = performance.now();
    for (const mutation of mutations) {
      if (mutation.type === 'attributes' && options.camera && mutation.attributeName === 'style' && (mutation.target as Element).matches?.(options.camera)) {
        counts.camera += 1;
        continue;
      }
      counts.total += 1;
      counts.firstAt ??= now;
      counts.lastAt = now;
      const target = describe(mutation.target);
      counts.byTarget[target] = (counts.byTarget[target] ?? 0) + 1;
      if (mutation.type === 'childList') {
        counts.structural += 1;
        counts.added += mutation.addedNodes.length;
        counts.removed += mutation.removedNodes.length;
      } else if (mutation.type === 'attributes') {
        counts.attributes += 1;
        const name = mutation.attributeName ?? '?';
        counts.byAttribute[name] = (counts.byAttribute[name] ?? 0) + 1;
      } else counts.text += 1;
    }
  };
  const observer = new MutationObserver(record);
  const attach = () => {
    const root = document.querySelector(options.root);
    if (!root || root === observed) return;
    observer.disconnect();
    observed = root;
    observer.observe(root, { subtree: true, childList: true, attributes: true, characterData: true });
  };
  const watcher = new MutationObserver(attach);
  const start = () => {
    watcher.observe(document.documentElement, { childList: true, subtree: true });
    attach();
  };
  if (document.documentElement) start();
  else document.addEventListener('readystatechange', start, { once: true });
  scope.__reaktorMutations = {
    reset() { record(observer.takeRecords()); counts = empty(); },
    snapshot(): MutationCounts {
      record(observer.takeRecords());
      const top = (table: Record<string, number>) => Object.fromEntries(Object.entries(table).sort((a, b) => b[1] - a[1]).slice(0, 12));
      return { ...counts, byAttribute: top(counts.byAttribute), byTarget: top(counts.byTarget) };
    },
  };
}

export const mutationReset = (): void => {
  (globalThis as unknown as { __reaktorMutations?: { reset(): void } }).__reaktorMutations?.reset();
};

export const mutationSnapshot = (): MutationCounts | null =>
  (globalThis as unknown as { __reaktorMutations?: { snapshot(): MutationCounts } }).__reaktorMutations?.snapshot() ?? null;
