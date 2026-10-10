import type { TraceEvent } from './trace';

export interface CdpLike {
  send(method: string, params?: Record<string, unknown>): Promise<any>;
  on(event: string, listener: (payload: any) => void): unknown;
  off?(event: string, listener: (payload: any) => void): unknown;
}

export const timelineCategories = [
  'blink.console',
  'blink.user_timing',
  'devtools.timeline',
  'disabled-by-default-devtools.timeline',
  'disabled-by-default-devtools.timeline.frame',
  'disabled-by-default-devtools.timeline.stack',
  'disabled-by-default-v8.cpu_profiler',
  'disabled-by-default-devtools.v8-source-rundown',
  'disabled-by-default-devtools.target-rundown',
  'latencyInfo',
  'loading',
  'toplevel',
  'v8',
  'v8.execute',
  'cc',
  'gpu',
  'benchmark',
];

export const invalidationCategories = [
  'disabled-by-default-devtools.timeline.invalidationTracking',
];

export const screenshotCategories = ['disabled-by-default-devtools.screenshot'];

export interface TraceOptions {
  categories?: string[];
  bufferKb?: number;
}

export async function startTrace(cdp: CdpLike, options: TraceOptions = {}): Promise<void> {
  await cdp.send('Tracing.start', {
    transferMode: 'ReturnAsStream',
    streamFormat: 'json',
    traceConfig: {
      recordMode: 'recordAsMuchAsPossible',
      traceBufferSizeInKb: options.bufferKb ?? 400_000,
      includedCategories: options.categories ?? timelineCategories,
      excludedCategories: ['*'],
    },
  });
}

export async function stopTrace(cdp: CdpLike): Promise<{ traceEvents: TraceEvent[]; dataLoss: boolean }> {
  const complete = new Promise<{ stream: string; dataLossOccurred?: boolean }>(resolve => {
    const listener = (payload: { stream: string; dataLossOccurred?: boolean }) => {
      cdp.off?.('Tracing.tracingComplete', listener);
      resolve(payload);
    };
    cdp.on('Tracing.tracingComplete', listener);
  });
  await cdp.send('Tracing.end');
  const { stream, dataLossOccurred } = await complete;
  const parts: string[] = [];
  for (;;) {
    const chunk = await cdp.send('IO.read', { handle: stream, size: 4 * 1024 * 1024 }) as { data: string; eof: boolean; base64Encoded?: boolean };
    parts.push(chunk.base64Encoded ? decodeBase64(chunk.data) : chunk.data);
    if (chunk.eof) break;
  }
  await cdp.send('IO.close', { handle: stream });
  const parsed = JSON.parse(parts.join('')) as TraceEvent[] | { traceEvents: TraceEvent[] };
  return { traceEvents: Array.isArray(parsed) ? parsed : parsed.traceEvents, dataLoss: !!dataLossOccurred };
}

function decodeBase64(data: string): string {
  const binary = atob(data);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return new TextDecoder().decode(bytes);
}

export async function performanceMetrics(cdp: CdpLike): Promise<Record<string, number>> {
  const reply = await cdp.send('Performance.getMetrics') as { metrics: Array<{ name: string; value: number }> };
  return Object.fromEntries(reply.metrics.map(item => [item.name, item.value]));
}

export function metricDelta(before: Record<string, number>, after: Record<string, number>): Record<string, number> {
  const delta: Record<string, number> = {};
  for (const [name, value] of Object.entries(after)) {
    const previous = before[name];
    if (typeof previous === 'number') delta[name] = Math.round((value - previous) * 1000) / 1000;
  }
  return delta;
}

export async function heapUsage(cdp: CdpLike, collect = false): Promise<{ usedBytes: number; totalBytes: number }> {
  if (collect) await cdp.send('HeapProfiler.collectGarbage');
  const reply = await cdp.send('Runtime.getHeapUsage') as { usedSize: number; totalSize: number };
  return { usedBytes: reply.usedSize, totalBytes: reply.totalSize };
}

export async function domCounters(cdp: CdpLike): Promise<{ documents: number; nodes: number; jsEventListeners: number }> {
  return await cdp.send('Memory.getDOMCounters') as { documents: number; nodes: number; jsEventListeners: number };
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

async function paced<T>(count: number, intervalMs: number, step: (index: number) => Promise<T>): Promise<void> {
  const started = performance.now();
  for (let index = 0; index < count; index += 1) {
    await step(index);
    const due = started + (index + 1) * intervalMs;
    const wait = due - performance.now();
    if (wait > 0) await sleep(wait);
  }
}

export interface WheelOptions {
  x: number;
  y: number;
  deltaX?: number;
  deltaY?: number;
  steps: number;
  intervalMs?: number;
  ctrl?: boolean;
}

export async function wheel(cdp: CdpLike, options: WheelOptions): Promise<void> {
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: options.x, y: options.y });
  await paced(options.steps, options.intervalMs ?? 8.33, () => cdp.send('Input.dispatchMouseEvent', {
    type: 'mouseWheel', x: options.x, y: options.y, deltaX: options.deltaX ?? 0, deltaY: options.deltaY ?? 0, modifiers: options.ctrl ? 2 : 0, pointerType: 'mouse',
  }));
}

export interface DragOptions {
  from: { x: number; y: number };
  to: { x: number; y: number };
  steps: number;
  intervalMs?: number;
}

export async function drag(cdp: CdpLike, options: DragOptions): Promise<void> {
  const { from, to } = options;
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: from.x, y: from.y });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: from.x, y: from.y, button: 'left', buttons: 1, clickCount: 1 });
  await paced(options.steps, options.intervalMs ?? 8.33, index => {
    const t = (index + 1) / options.steps;
    return cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: from.x + (to.x - from.x) * t, y: from.y + (to.y - from.y) * t, button: 'left', buttons: 1 });
  });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: to.x, y: to.y, button: 'left', buttons: 0, clickCount: 1 });
}

export interface PinchOptions {
  x: number;
  y: number;
  scaleFactor: number;
  relativeSpeed?: number;
  source?: 'mouse' | 'touch' | 'default';
}

export async function pinch(cdp: CdpLike, options: PinchOptions): Promise<void> {
  await cdp.send('Input.synthesizePinchGesture', { x: options.x, y: options.y, scaleFactor: options.scaleFactor, relativeSpeed: options.relativeSpeed ?? 600, gestureSourceType: options.source ?? 'mouse' });
}

export interface ScrollGestureOptions {
  x: number;
  y: number;
  xDistance?: number;
  yDistance?: number;
  speed?: number;
  repeatCount?: number;
  source?: 'mouse' | 'touch' | 'default';
}

export async function scrollGesture(cdp: CdpLike, options: ScrollGestureOptions): Promise<void> {
  await cdp.send('Input.synthesizeScrollGesture', {
    x: options.x, y: options.y, xDistance: options.xDistance ?? 0, yDistance: options.yDistance ?? 0, speed: options.speed ?? 800,
    gestureSourceType: options.source ?? 'mouse', repeatCount: options.repeatCount ?? 0, preventFling: true,
  });
}

export async function click(cdp: CdpLike, x: number, y: number): Promise<void> {
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', buttons: 1, clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', buttons: 0, clickCount: 1 });
}

export interface ScreencastFrame {
  data: string;
  timestamp: number;
  offsetTop: number;
  pageScaleFactor: number;
  deviceWidth: number;
  deviceHeight: number;
}

export interface ScreencastOptions {
  format?: 'jpeg' | 'png';
  quality?: number;
  maxWidth?: number;
  maxHeight?: number;
  everyNthFrame?: number;
}

export interface Screencast {
  frames: ScreencastFrame[];
  stop(): Promise<ScreencastFrame[]>;
}

export async function startScreencast(cdp: CdpLike, options: ScreencastOptions = {}): Promise<Screencast> {
  const frames: ScreencastFrame[] = [];
  let running = true;
  const listener = (payload: { data: string; sessionId: number; metadata: { timestamp?: number; offsetTop: number; pageScaleFactor: number; deviceWidth: number; deviceHeight: number } }) => {
    if (running) frames.push({ data: payload.data, timestamp: (payload.metadata.timestamp ?? 0) * 1000, offsetTop: payload.metadata.offsetTop, pageScaleFactor: payload.metadata.pageScaleFactor, deviceWidth: payload.metadata.deviceWidth, deviceHeight: payload.metadata.deviceHeight });
    void cdp.send('Page.screencastFrameAck', { sessionId: payload.sessionId }).catch(() => undefined);
  };
  cdp.on('Page.screencastFrame', listener);
  await cdp.send('Page.startScreencast', { format: options.format ?? 'jpeg', quality: options.quality ?? 70, maxWidth: options.maxWidth, maxHeight: options.maxHeight, everyNthFrame: options.everyNthFrame ?? 1 });
  return {
    frames,
    async stop() {
      running = false;
      await cdp.send('Page.stopScreencast').catch(() => undefined);
      cdp.off?.('Page.screencastFrame', listener);
      return frames.slice();
    },
  };
}

export async function showPaintRects(cdp: CdpLike, on: boolean): Promise<void> {
  if (on) await cdp.send('Overlay.enable').catch(() => undefined);
  await cdp.send('Overlay.setShowPaintRects', { result: on });
}

export interface LayerInfo {
  layerId: string;
  parentLayerId?: string;
  nodeId?: number;
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
  drawsContent: boolean;
  invisible?: boolean;
  paintCount: number;
  name?: string;
  reasons?: string[];
}

export interface LayerSummary {
  count: number;
  drawing: number;
  area: number;
  estimatedBytes: number;
  largest: Array<{ id: string; name: string; width: number; height: number; paintCount: number; reasons: string[] }>;
  reasons: Record<string, number>;
  paints: number;
}

export async function layerTree(cdp: CdpLike, options: { deviceScaleFactor?: number; reasons?: number; timeoutMs?: number } = {}): Promise<LayerSummary> {
  const layers = await new Promise<LayerInfo[]>(resolve => {
    const timer = setTimeout(() => { cdp.off?.('LayerTree.layerTreeDidChange', listener); resolve([]); }, options.timeoutMs ?? 2000);
    const listener = (payload: { layers?: LayerInfo[] }) => {
      if (!payload.layers) return;
      clearTimeout(timer);
      cdp.off?.('LayerTree.layerTreeDidChange', listener);
      resolve(payload.layers);
    };
    cdp.on('LayerTree.layerTreeDidChange', listener);
    void cdp.send('LayerTree.enable').then(() => cdp.send('Runtime.evaluate', { expression: 'document.documentElement.style.setProperty("--reaktor-layer-probe", String(Math.random()))' })).catch(() => undefined);
  });
  await cdp.send('LayerTree.disable').catch(() => undefined);
  const scale = (options.deviceScaleFactor ?? 1) ** 2;
  const drawing = layers.filter(layer => layer.drawsContent && !layer.invisible);
  const area = drawing.reduce((sum, layer) => sum + layer.width * layer.height, 0);
  const largest = [...drawing].sort((a, b) => b.width * b.height - a.width * a.height).slice(0, options.reasons ?? 8);
  const reasons: Record<string, number> = {};
  for (const layer of largest) {
    try {
      const reply = await cdp.send('LayerTree.compositingReasons', { layerId: layer.layerId }) as { compositingReasonIds?: string[]; compositingReasons?: string[] };
      layer.reasons = reply.compositingReasonIds ?? reply.compositingReasons ?? [];
    } catch {
      layer.reasons = [];
    }
    for (const reason of layer.reasons) reasons[reason] = (reasons[reason] ?? 0) + 1;
  }
  return {
    count: layers.length,
    drawing: drawing.length,
    area: Math.round(area),
    estimatedBytes: Math.round(area * scale * 4),
    largest: largest.map(layer => ({ id: layer.layerId, name: layer.name ?? layer.layerId, width: Math.round(layer.width), height: Math.round(layer.height), paintCount: layer.paintCount, reasons: layer.reasons ?? [] })),
    reasons,
    paints: layers.reduce((sum, layer) => sum + (layer.paintCount ?? 0), 0),
  };
}

export const frameEventCategories = [
  'devtools.timeline',
  'disabled-by-default-devtools.timeline',
  'disabled-by-default-devtools.timeline.frame',
  'blink.user_timing',
  'loading',
  'latencyInfo',
  'toplevel',
  'v8.execute',
  'cc',
  'gpu',
  'benchmark',
  'disabled-by-default-devtools.target-rundown',
];
