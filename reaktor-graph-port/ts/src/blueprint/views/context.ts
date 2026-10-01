import { createContext, useContext, useSyncExternalStore, type ReactNode } from 'react';
import type { Focus } from '../highlight';
import type { BlueprintLayout, Card, Frame, Link } from '../types';
import type { Viewport } from './camera';
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

export interface MapScene {
  layout: BlueprintLayout;
  thresholds: LookThresholds;
  selected: string | null;
  selectedRow: number | null;
  highlight: ReadonlySet<string>;
  focus: Focus;
  litLinks: ReadonlySet<string>;
  corridors: boolean;
  frameOf: Map<string, string>;
  rowOfPort: Map<string, number>;
  renderCard: (card: Card, look: Look) => ReactNode;
  renderFrame: (frame: Frame, look: Look) => ReactNode;
  wireStyle: (link: Link, state: WireState) => WireStyle | null;
  pins: HoverStore;
}

export interface CameraControls {
  ready: boolean;
  canCentre: boolean;
  fit(): void;
  centre(): void;
  zoomTo(zoom: number): void;
  zoomBy(factor: number): void;
  panBy(dx: number, dy: number): void;
  frameBounds(bounds: { x: number; y: number; width: number; height: number }, maxZoom?: number): void;
  back(): void;
  forward(): void;
  viewport(): Viewport;
  setViewport(viewport: Viewport, remember?: boolean): void;
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

export type FramePart = 'body' | 'banner';

export const SceneContext = createContext<MapScene | null>(null);
export const FramePartContext = createContext<FramePart>('body');
export const CameraContext = createContext<CameraControls | null>(null);

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

export function useHoveredPin(store: HoverStore): PinFocus | null {
  return useSyncExternalStore(store.subscribe, store.get, store.get);
}
