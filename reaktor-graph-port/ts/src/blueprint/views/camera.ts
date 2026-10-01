export interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

export interface Bounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Insets {
  top: number;
  start: number;
  bottom: number;
}

export interface CameraMemory {
  viewport: Viewport | null;
  fitted: string | null;
  fitZoom: number;
  revealed: number;
  history: Viewport[];
  future: Viewport[];
}

export const GestureZoom = { min: 0.05, max: 2.5 };
export const ProgrammaticZoom = { min: 0.03, max: 4 };

const memories = new Map<string, CameraMemory>();

export function cameraMemory(key: string): CameraMemory {
  let memory = memories.get(key);
  if (!memory) {
    memory = { viewport: null, fitted: null, fitZoom: GestureZoom.min, revealed: 0, history: [], future: [] };
    memories.set(key, memory);
  }
  return memory;
}

export function forgetCamera(key: string): void {
  memories.delete(key);
}

export function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

export function place(centreX: number, centreY: number, zoom: number, width: number, height: number, insets: Insets): Viewport {
  const room = height - insets.top - insets.bottom;
  const span = width - insets.start;
  return { x: insets.start + span / 2 - centreX * zoom, y: insets.top + room / 2 - centreY * zoom, zoom };
}

export function fitBounds(bounds: Bounds, width: number, height: number, insets: Insets, padding = 0.03, maxZoom = 1): Viewport | null {
  const whole = { ...insets, bottom: 0 };
  const room = height - whole.top;
  const span = width - whole.start;
  if (span <= 0 || room <= 0) return null;
  const zoom = clamp(Math.min(
    span * (1 - 2 * padding) / Math.max(bounds.width, 1),
    room * (1 - 2 * padding) / Math.max(bounds.height, 1),
  ), ProgrammaticZoom.min, maxZoom);
  return place(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, zoom, width, height, whole);
}

export function centreOn(x: number, y: number, current: number, width: number, height: number, insets: Insets, readable = 0.45, fallback = 0.9): Viewport {
  return place(x, y, current >= readable ? current : fallback, width, height, insets);
}

export function zoomAround(viewport: Viewport, factor: number, pointX: number, pointY: number, low: number, high: number): Viewport {
  const zoom = clamp(viewport.zoom * factor, Math.min(low, viewport.zoom), Math.max(high, viewport.zoom));
  const ratio = zoom / viewport.zoom;
  return { x: pointX - (pointX - viewport.x) * ratio, y: pointY - (pointY - viewport.y) * ratio, zoom };
}

export function wheelFactor(deltaY: number, deltaMode: number): number {
  const pixels = deltaMode === 1 ? deltaY * 20 : deltaMode === 2 ? deltaY * 400 : deltaY;
  return clamp(Math.exp(-pixels * 0.01), 0.88, 1.16);
}
