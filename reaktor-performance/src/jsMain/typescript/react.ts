export type RenderReason = 'mount' | 'props' | 'state' | 'hooks' | 'context' | 'parent';

export interface ReactCommitSample {
  at: number;
  rendered: number;
  mounted: number;
  wasted: number;
  hostMounted: number;
  hostUpdated: number;
  actualMs: number | null;
}

export interface ReactComponentStat {
  name: string;
  renders: number;
  mounts: number;
  wasted: number;
  selfMs: number;
  reasons: Partial<Record<RenderReason, number>>;
  changedProps: Record<string, number>;
}

export interface ReactFlameNode {
  name: string;
  totalMs: number;
  selfMs: number;
  renders: number;
  children: ReactFlameNode[];
}

export interface ReactProfilerSample {
  id: string;
  phase: string;
  actualMs: number;
  baseMs: number;
  startTime: number;
  commitTime: number;
}

export interface ReactProbeSnapshot {
  profiling: boolean;
  commits: number;
  rendered: number;
  mounted: number;
  wasted: number;
  hostMounted: number;
  hostUpdated: number;
  unmounted: number;
  largest: number;
  actualMs: number;
  components: ReactComponentStat[];
  samples: ReactCommitSample[];
  profiler: ReactProfilerSample[];
  flame: ReactFlameNode;
}

export interface ReactProbeOptions {
  maxSamples?: number;
  flameDepth?: number;
}

export function installReactProbe(options: ReactProbeOptions = {}): void {
  type Hook = { memoizedState: unknown; queue: unknown; next: Hook | null };
  type Dependency = { context: unknown; memoizedValue: unknown; next: Dependency | null };
  type Fiber = {
    tag: number;
    flags: number;
    type: unknown;
    elementType: unknown;
    child: Fiber | null;
    sibling: Fiber | null;
    alternate: Fiber | null;
    memoizedProps: Record<string, unknown> | null;
    memoizedState: unknown;
    dependencies: { firstContext: Dependency | null } | null;
    actualDuration?: number;
  };
  type Root = { current: Fiber };
  type DevtoolsHook = Record<string, unknown> & {
    onCommitFiberRoot?: (id: number, root: Root, priority?: number, error?: boolean) => void;
    onCommitFiberUnmount?: (id: number, fiber: Fiber) => void;
    inject?: (internals: unknown) => number;
  };
  const scope = globalThis as unknown as { __REACT_DEVTOOLS_GLOBAL_HOOK__?: DevtoolsHook; __reaktorReact?: unknown };
  if (scope.__reaktorReact) return;
  const maxSamples = options.maxSamples ?? 5000;
  const flameDepth = options.flameDepth ?? 14;
  const components = new Set([0, 1, 2, 9, 11, 14, 15]);
  const classes = new Set([1]);
  const memos = new Set([14, 15]);
  const hosts = new Set([5, 6]);
  type Stat = { renders: number; mounts: number; wasted: number; selfMs: number; reasons: Record<string, number>; changedProps: Map<string, number> };
  type Flame = { name: string; totalMs: number; selfMs: number; renders: number; children: Map<string, Flame> };
  const freshFlame = (): Flame => ({ name: 'commits', totalMs: 0, selfMs: 0, renders: 0, children: new Map() });
  const state = {
    profiling: false, commits: 0, rendered: 0, mounted: 0, wasted: 0, hostMounted: 0, hostUpdated: 0, unmounted: 0, largest: 0, actualMs: 0,
    stats: new Map<string, Stat>(), samples: [] as ReactCommitSample[], profiler: [] as ReactProfilerSample[], flame: freshFlame(),
  };
  const nameOf = (fiber: Fiber): string => {
    const type = (fiber.type ?? fiber.elementType) as { displayName?: string; name?: string; render?: { displayName?: string; name?: string }; type?: { displayName?: string; name?: string } } | string | null;
    if (!type) return `tag${fiber.tag}`;
    if (typeof type === 'string') return type;
    const named = type.displayName || type.name || type.render?.displayName || type.render?.name || type.type?.displayName || type.type?.name;
    if (named) return memos.has(fiber.tag) ? `Memo(${named})` : fiber.tag === 11 ? `ForwardRef(${named})` : named;
    return fiber.tag === 9 ? 'Context.Consumer' : `Anonymous${fiber.tag}`;
  };
  const statOf = (name: string): Stat => {
    let entry = state.stats.get(name);
    if (!entry) { entry = { renders: 0, mounts: 0, wasted: 0, selfMs: 0, reasons: {}, changedProps: new Map() }; state.stats.set(name, entry); }
    return entry;
  };
  const changedKeys = (previous: Record<string, unknown> | null, next: Record<string, unknown> | null): string[] => {
    if (previous === next) return [];
    if (!previous || !next) return ['*'];
    const keys = new Set([...Object.keys(previous), ...Object.keys(next)]);
    const changed: string[] = [];
    for (const key of keys) if (!Object.is(previous[key], next[key])) changed.push(key);
    return changed;
  };
  const hooksChanged = (previous: unknown, next: unknown): boolean => {
    let a = previous as Hook | null;
    let b = next as Hook | null;
    while (a && b) {
      if (b.queue !== null && b.queue !== undefined && !Object.is(a.memoizedState, b.memoizedState)) return true;
      a = a.next;
      b = b.next;
    }
    return false;
  };
  const contextChanged = (previous: Fiber, next: Fiber): boolean => {
    let a = previous.dependencies?.firstContext ?? null;
    let b = next.dependencies?.firstContext ?? null;
    while (a && b) {
      if (!Object.is(a.memoizedValue, b.memoizedValue)) return true;
      a = a.next;
      b = b.next;
    }
    return false;
  };
  const reasonOf = (previous: Fiber | null, next: Fiber, changedProps: string[]): RenderReason => {
    if (!previous) return 'mount';
    if (contextChanged(previous, next)) return 'context';
    if (classes.has(next.tag)) {
      if (!Object.is(previous.memoizedState, next.memoizedState)) return 'state';
    } else if (hooksChanged(previous.memoizedState, next.memoizedState)) return 'hooks';
    if (changedProps.length > 0) return 'props';
    return 'parent';
  };
  const childDuration = (fiber: Fiber): number => {
    let total = 0;
    for (let child = fiber.child; child; child = child.sibling) total += child.actualDuration ?? 0;
    return total;
  };
  const visit = (next: Fiber, previous: Fiber | null, counts: ReactCommitSample, flame: Flame, depth: number) => {
    let branch = flame;
    if (components.has(next.tag)) {
      const rendered = !previous || (next.flags & 1) === 1;
      if (rendered) {
        const name = nameOf(next);
        const stat = statOf(name);
        const props = previous ? changedKeys(previous.memoizedProps, next.memoizedProps) : [];
        const reason = reasonOf(previous, next, props);
        stat.renders += 1;
        stat.reasons[reason] = (stat.reasons[reason] ?? 0) + 1;
        for (const key of props.slice(0, 6)) stat.changedProps.set(key, (stat.changedProps.get(key) ?? 0) + 1);
        const total = next.actualDuration;
        const self = typeof total === 'number' ? Math.max(0, total - childDuration(next)) : 0;
        stat.selfMs += self;
        counts.rendered += 1;
        if (!previous) { stat.mounts += 1; counts.mounted += 1; }
        if (reason === 'parent' && !memos.has(next.tag)) { stat.wasted += 1; counts.wasted += 1; }
        if (depth < flameDepth) {
          let node = flame.children.get(name);
          if (!node) { node = { name, totalMs: 0, selfMs: 0, renders: 0, children: new Map() }; flame.children.set(name, node); }
          node.renders += 1;
          node.totalMs += typeof total === 'number' ? total : 0;
          node.selfMs += self;
          branch = node;
        }
      }
    } else if (hosts.has(next.tag)) {
      if (!previous) counts.hostMounted += 1;
      else if (previous.memoizedProps !== next.memoizedProps) counts.hostUpdated += 1;
    }
    if (previous && next.child === previous.child) return;
    const nextDepth = branch === flame ? depth : depth + 1;
    for (let child = next.child; child; child = child.sibling) visit(child, previous ? child.alternate : null, counts, branch, nextDepth);
  };
  const onCommit = (root: Root) => {
    const current = root.current;
    const counts: ReactCommitSample = { at: performance.now(), rendered: 0, mounted: 0, wasted: 0, hostMounted: 0, hostUpdated: 0, actualMs: null };
    if (typeof current.actualDuration === 'number') {
      state.profiling = true;
      counts.actualMs = Math.round(current.actualDuration * 100) / 100;
      state.actualMs += current.actualDuration;
      state.flame.totalMs += current.actualDuration;
    }
    const previous = current.alternate;
    if (previous) {
      if (previous.child !== current.child) for (let child = current.child; child; child = child.sibling) visit(child, child.alternate, counts, state.flame, 0);
    } else for (let child = current.child; child; child = child.sibling) visit(child, null, counts, state.flame, 0);
    state.commits += 1;
    state.rendered += counts.rendered;
    state.mounted += counts.mounted;
    state.wasted += counts.wasted;
    state.hostMounted += counts.hostMounted;
    state.hostUpdated += counts.hostUpdated;
    state.largest = Math.max(state.largest, counts.rendered);
    state.flame.renders += counts.rendered;
    if (state.samples.length < maxSamples) state.samples.push(counts);
  };
  const onUnmount = (fiber: Fiber) => { if (components.has(fiber.tag)) state.unmounted += 1; };
  const existing = scope.__REACT_DEVTOOLS_GLOBAL_HOOK__;
  if (existing && typeof existing === 'object') {
    const commit = existing.onCommitFiberRoot;
    const unmount = existing.onCommitFiberUnmount;
    existing.onCommitFiberRoot = function (id, root, priority, error) { onCommit(root); return commit?.call(existing, id, root, priority, error); };
    existing.onCommitFiberUnmount = function (id, fiber) { onUnmount(fiber); return unmount?.call(existing, id, fiber); };
  } else {
    let next = 1;
    const renderers = new Map<number, unknown>();
    scope.__REACT_DEVTOOLS_GLOBAL_HOOK__ = {
      supportsFiber: true,
      renderers,
      inject(internals: unknown) { const id = next++; renderers.set(id, internals); return id; },
      checkDCE() { return undefined; },
      onScheduleFiberRoot() { return undefined; },
      onPostCommitFiberRoot() { return undefined; },
      onCommitFiberUnmount(_id: number, fiber: Fiber) { onUnmount(fiber); },
      onCommitFiberRoot(_id: number, root: Root) { onCommit(root); },
    };
  }
  const flameOut = (node: Flame): ReactFlameNode => ({
    name: node.name, totalMs: Math.round(node.totalMs * 100) / 100, selfMs: Math.round(node.selfMs * 100) / 100, renders: node.renders,
    children: [...node.children.values()].sort((a, b) => b.totalMs - a.totalMs || b.renders - a.renders).map(flameOut),
  });
  scope.__reaktorReact = {
    reset() {
      Object.assign(state, { commits: 0, rendered: 0, mounted: 0, wasted: 0, hostMounted: 0, hostUpdated: 0, unmounted: 0, largest: 0, actualMs: 0 });
      state.stats = new Map(); state.samples = []; state.profiler = []; state.flame = freshFlame();
    },
    profilerRender(id: string, phase: string, actualMs: number, baseMs: number, startTime: number, commitTime: number) {
      if (state.profiler.length < maxSamples) state.profiler.push({ id, phase, actualMs: Math.round(actualMs * 100) / 100, baseMs: Math.round(baseMs * 100) / 100, startTime, commitTime });
    },
    snapshot(): ReactProbeSnapshot {
      return {
        profiling: state.profiling, commits: state.commits, rendered: state.rendered, mounted: state.mounted, wasted: state.wasted,
        hostMounted: state.hostMounted, hostUpdated: state.hostUpdated, unmounted: state.unmounted, largest: state.largest, actualMs: Math.round(state.actualMs * 100) / 100,
        components: [...state.stats].map(([name, stat]) => ({
          name, renders: stat.renders, mounts: stat.mounts, wasted: stat.wasted, selfMs: Math.round(stat.selfMs * 100) / 100, reasons: stat.reasons,
          changedProps: Object.fromEntries([...stat.changedProps].sort((a, b) => b[1] - a[1]).slice(0, 6)),
        })).sort((a, b) => b.renders - a.renders || b.selfMs - a.selfMs).slice(0, 40),
        samples: state.samples.slice(),
        profiler: state.profiler.slice(),
        flame: flameOut(state.flame),
      };
    },
  };
}

export function recordProfilerRender(id: string, phase: string, actualDuration: number, baseDuration: number, startTime: number, commitTime: number): void {
  (globalThis as unknown as { __reaktorReact?: { profilerRender(...args: unknown[]): void } }).__reaktorReact?.profilerRender(id, phase, actualDuration, baseDuration, startTime, commitTime);
}

export const reactProbeReset = (): void => {
  (globalThis as unknown as { __reaktorReact?: { reset(): void } }).__reaktorReact?.reset();
};

export const reactProbeSnapshot = (): ReactProbeSnapshot | null =>
  (globalThis as unknown as { __reaktorReact?: { snapshot(): ReactProbeSnapshot } }).__reaktorReact?.snapshot() ?? null;

export interface ReactDevtoolsCore {
  initialize?: (settings?: unknown, shouldStartProfilingNow?: boolean, profilingSettings?: unknown) => void;
  connectToDevTools: (options?: { host?: string; port?: number; useHttps?: boolean; websocket?: unknown; resolveRNStyle?: unknown; isAppActive?: () => boolean; onSettingsUpdated?: unknown }) => void;
}

export interface ReactDevtoolsOptions {
  host?: string;
  port?: number;
  flag?: string;
  always?: boolean;
}

export function connectReactDevtools(core: ReactDevtoolsCore, options: ReactDevtoolsOptions = {}): boolean {
  if (typeof window === 'undefined') return false;
  const params = new URLSearchParams(window.location.search);
  const flag = options.flag ?? 'devtools';
  if (!options.always && !params.has(flag)) return false;
  const port = Number(params.get(`${flag}Port`) ?? options.port ?? 8097);
  const host = params.get(`${flag}Host`) ?? options.host ?? 'localhost';
  core.initialize?.();
  core.connectToDevTools({ host, port });
  return true;
}
