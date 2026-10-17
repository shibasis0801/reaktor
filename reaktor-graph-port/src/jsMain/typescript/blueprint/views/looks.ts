import { useRest } from './context';

export type Look = 'domain' | 'resource' | 'chapter';

export interface LookThresholds {
  far: number;
  near: number;
  farIn?: number;
  farOut?: number;
  nearIn?: number;
  nearOut?: number;
}

export const DefaultThresholds: LookThresholds = { far: 0.18, near: 0.42, farIn: 0.2, farOut: 0.16, nearIn: 0.45, nearOut: 0.39 };

export function lookFor(zoom: number, thresholds: LookThresholds = DefaultThresholds): Look {
  if (zoom < thresholds.far) return 'domain';
  if (zoom < thresholds.near) return 'resource';
  return 'chapter';
}

export function nextLook(current: Look | null, zoom: number, thresholds: LookThresholds = DefaultThresholds): Look {
  if (current === null) return lookFor(zoom, thresholds);
  const farIn = thresholds.farIn ?? thresholds.far;
  const farOut = thresholds.farOut ?? thresholds.far;
  const nearIn = thresholds.nearIn ?? thresholds.near;
  const nearOut = thresholds.nearOut ?? thresholds.near;
  if (current === 'domain') return zoom >= nearIn ? 'chapter' : zoom >= farIn ? 'resource' : 'domain';
  if (current === 'chapter') return zoom < farOut ? 'domain' : zoom < nearOut ? 'resource' : 'chapter';
  return zoom < farOut ? 'domain' : zoom >= nearIn ? 'chapter' : 'resource';
}

export function useLook(): Look {
  return useRest(rest => rest.look);
}

export function useZoomPercent(): number {
  return useRest(rest => Math.round(rest.zoom * 100));
}
