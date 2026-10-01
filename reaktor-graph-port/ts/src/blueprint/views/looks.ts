import { useStore } from '@xyflow/react';

export type Look = 'domain' | 'resource' | 'chapter';

export interface LookThresholds {
  far: number;
  near: number;
}

export const DefaultThresholds: LookThresholds = { far: 0.18, near: 0.42 };

export function lookFor(zoom: number, thresholds: LookThresholds = DefaultThresholds): Look {
  if (zoom < thresholds.far) return 'domain';
  if (zoom < thresholds.near) return 'resource';
  return 'chapter';
}

export function useLook(thresholds: LookThresholds = DefaultThresholds): Look {
  return useStore(state => lookFor(state.transform[2], thresholds));
}

export function useZoomPercent(): number {
  return useStore(state => Math.round(state.transform[2] * 100));
}
