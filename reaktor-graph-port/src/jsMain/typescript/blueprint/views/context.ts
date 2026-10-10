import { createContext, useContext, useSyncExternalStore, type ReactNode } from 'react';
import type { Focus } from '../highlight';
import type { BlueprintLayout, Card, Frame, Link } from '../types';
import type { Bounds, Viewport } from './camera';
import type { Look, LookThresholds } from './looks';

export interface WireState {
  look: Look;
  lit: boolean;
  route: boolean;
  hovered: boolean;
  nearby: boolean;
  crowd: number;
  highlighting: boolean;
  focus: Focus;
}

export interface WireStyle {
  tone: string;
  width?: number;
  alpha?: number;
  dash?: string;
  arrow?: boolean;
  glow?: boolean;
  marching?: boolean;
  label?: string;
  title?: string;
  batch?: boolean;
}

export interface PinFocus {
  card: string;
  row: number;
  x: number;
  y: number;
  ready: boolean;
}

export interface Selection {
  selected: string | null;
  selectedCard?: string | null;
  selectedRow: number | null;
  highlight: ReadonlySet<string>;
  focus: Focus;
  litLinks: ReadonlySet<string>;
  beads?: ReadonlyMap<string, number>;
}

export function selectedCardOf(selection: Selection): string | null {
  return selection.selectedCard ?? selection.selected;
}

export class SelectionStore {
  private value: Selection;
  private readonly listeners = new Set<() => void>();
  constructor(initial: Selection) {
    this.value = initial;
  }
  get = () => this.value;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  set(value: Selection) {
    const current = this.value;
    if (current.selected === value.selected && (current.selectedCard ?? null) === (value.selectedCard ?? null) && current.selectedRow === value.selectedRow && current.highlight === value.highlight && current.focus === value.focus && current.litLinks === value.litLinks && current.beads === value.beads) return;
    this.value = value;
    this.listeners.forEach(listener => listener());
  }
}

export interface MapScene {
  layout: BlueprintLayout;
  cards: CardGate;
  thresholds: LookThresholds;
  selection: SelectionStore;
  corridors: boolean;
  frameOf: Map<string, string>;
  owners?: Map<string, string>;
  frames?: Map<string, Frame>;
  rowOfPort: Map<string, number>;
  renderCard: (card: Card, look: Look) => ReactNode;
  renderFrame: (frame: Frame, look: Look) => ReactNode;
  wireStyle: (link: Link, state: WireState) => WireStyle | null;
  pins: HoverStore;
  rest: RestStore;
}

export interface CameraControls {
  ready: boolean;
  canCentre: boolean;
  fit(duration?: number): void;
  centre(): void;
  zoomTo(zoom: number): void;
  zoomBy(factor: number): void;
  panBy(dx: number, dy: number): void;
  frameBounds(bounds: { x: number; y: number; width: number; height: number }, maxZoom?: number, duration?: number): void;
  back(): void;
  forward(): void;
  viewport(): Viewport;
  setViewport(viewport: Viewport, remember?: boolean, duration?: number): void;
}

export class HoverStore {
  private value: PinFocus | null = null;
  private readonly listeners = new Set<() => void>();
  get = () => this.value;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  set(value: PinFocus | null) {
    if (value === this.value || (value && this.value && value.card === this.value.card && value.row === this.value.row && value.ready === this.value.ready && value.x === this.value.x && value.y === this.value.y)) return;
    this.value = value;
    this.listeners.forEach(listener => listener());
  }
}

export interface CameraRest {
  x: number;
  y: number;
  zoom: number;
  look: Look;
  view: Bounds;
  window: Bounds;
  width: number;
  height: number;
}

export class RestStore {
  private value: CameraRest;
  private readonly listeners = new Set<() => void>();
  moving = false;
  constructor(initial: CameraRest) {
    this.value = initial;
  }
  get = () => this.value;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  set(value: CameraRest) {
    const current = this.value;
    if (current.x === value.x && current.y === value.y && current.zoom === value.zoom && current.look === value.look && current.width === value.width && current.height === value.height) return;
    this.value = value;
    this.listeners.forEach(listener => listener());
  }
}

export function intersects(a: Bounds, b: { x: number; y: number; width: number; height: number }): boolean {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;
}

const StepBudget = 72;

export interface CardShown {
  look: Look;
  rows: boolean;
}

interface Gated {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  rows: number;
}

export class CardGate {
  private shown = new Map<string, CardShown>();
  private queue: Array<{ id: string; want: CardShown; cost: number }> = [];
  private waiting = false;
  private gated: { layout: BlueprintLayout | null; items: Gated[]; ids: Set<string> } = { layout: null, items: [], ids: new Set() };
  private readonly listeners = new Set<() => void>();
  constructor(private readonly rest: RestStore) {}
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  get = (id: string): CardShown | undefined => this.shown.get(id);
  private itemsOf(layout: BlueprintLayout): Gated[] {
    if (this.gated.layout !== layout) {
      const items: Gated[] = [
        ...Object.values(layout.cards),
        ...layout.frames.filter(frame => frame.parent !== undefined).map(frame => ({ id: `frame:${frame.key}`, x: frame.x, y: frame.y, width: frame.width, height: frame.height, rows: 0 })),
      ];
      this.gated = { layout, items, ids: new Set(items.map(item => item.id)) };
    }
    return this.gated.items;
  }
  update(layout: BlueprintLayout | null, rest: CameraRest) {
    if (!layout) { this.queue = []; return; }
    const { view, look } = rest;
    const close = view;
    const centreX = view.x + view.width / 2;
    const centreY = view.y + view.height / 2;
    const next = new Map(this.shown);
    const later: Array<{ id: string; want: CardShown; cost: number; distance: number }> = [];
    let changed = false;
    for (const card of this.itemsOf(layout)) {
      const rows = look === 'chapter' && intersects(rest.window, card);
      const now = this.shown.get(card.id);
      if (now && now.look === look && now.rows === rows) continue;
      const want = { look, rows };
      if (intersects(close, card) || (!now && !rows)) { next.set(card.id, want); changed = true; continue; }
      const shown = now ?? { look, rows: false };
      if (!now) { next.set(card.id, shown); changed = true; }
      later.push({ id: card.id, want, cost: (shown.look === look ? 0 : 4) + (rows ? Math.max(1, card.rows) : 1), distance: Math.hypot(card.x + card.width / 2 - centreX, card.y + card.height / 2 - centreY) });
    }
    for (const id of this.shown.keys()) if (!this.gated.ids.has(id)) { next.delete(id); changed = true; }
    this.queue = later.sort((a, b) => a.distance - b.distance).map(({ id, want, cost }) => ({ id, want, cost }));
    if (changed) { this.shown = next; this.emit(); }
    this.later();
  }
  private later() {
    if (this.waiting || this.queue.length === 0) return;
    this.waiting = true;
    requestAnimationFrame(() => setTimeout(() => {
      this.waiting = false;
      this.step();
    }, 0));
  }
  private step() {
    if (this.rest.moving || this.queue.length === 0) return;
    const next = new Map(this.shown);
    let budget = StepBudget;
    while (this.queue.length > 0 && budget > 0) {
      const item = this.queue.shift()!;
      next.set(item.id, item.want);
      budget -= item.cost;
    }
    this.shown = next;
    this.emit();
    this.later();
  }
  private emit() {
    this.listeners.forEach(listener => listener());
  }
}

export type FramePart = 'body' | 'banner';

const silent = () => () => undefined;

export const SceneContext = createContext<MapScene | null>(null);
export const FramePartContext = createContext<FramePart>('body');
export const CameraContext = createContext<CameraControls | null>(null);
export const RestContext = createContext<RestStore | null>(null);

export function useScene(): MapScene {
  const scene = useContext(SceneContext);
  if (!scene) throw new Error('Blueprint views must render inside a BlueprintMap');
  return scene;
}

export function useCameraControls(): CameraControls {
  const controls = useContext(CameraContext);
  if (!controls) throw new Error('Blueprint camera controls must render inside a BlueprintMap');
  return controls;
}

export function useSelection<T>(select: (selection: Selection) => T): T {
  const scene = useContext(SceneContext);
  const read = () => select(scene ? scene.selection.get() : emptySelection);
  return useSyncExternalStore(scene ? scene.selection.subscribe : silent, read, read);
}

const emptySelection: Selection = { selected: null, selectedCard: null, selectedRow: null, highlight: new Set(), focus: 'all', litLinks: new Set() };


export function useCardLook(id: string): Look {
  const scene = useContext(SceneContext);
  const rest = useContext(RestContext);
  const read = () => scene?.cards.get(id)?.look ?? rest?.get().look ?? 'chapter';
  return useSyncExternalStore(scene ? scene.cards.subscribe : silent, read, read);
}

export function useRowsOpen(id: string): boolean {
  const scene = useContext(SceneContext);
  const read = () => scene?.cards.get(id)?.rows ?? false;
  return useSyncExternalStore(scene ? scene.cards.subscribe : silent, read, read);
}

export function useHoveredPin(store: HoverStore): PinFocus | null {
  return useSyncExternalStore(store.subscribe, store.get, store.get);
}

export function useRest<T>(select: (rest: CameraRest) => T, store?: RestStore | null): T {
  const context = useContext(RestContext);
  const source = store ?? context;
  const read = () => select(source ? source.get() : fallbackRest);
  return useSyncExternalStore(source ? source.subscribe : silent, read, read);
}

const fallbackRest: CameraRest = { x: 0, y: 0, zoom: 1, look: 'chapter', view: { x: 0, y: 0, width: 0, height: 0 }, window: { x: 0, y: 0, width: 0, height: 0 }, width: 0, height: 0 };
