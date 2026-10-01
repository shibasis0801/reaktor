export type PinWiring = 'linked' | 'unlinked' | 'problem' | 'muted';

export interface PinSpec {
  key: string;
  type: string;
  wiring: PinWiring;
  row?: number;
}

export interface BlueprintNode {
  id: string;
  lane: number;
  inputs?: PinSpec[];
  outputs?: PinSpec[];
  rows?: number;
  folded?: number;
}

export interface BlueprintGroup {
  key: string;
  label: string;
  nodes: BlueprintNode[];
  detail?: string[];
  muted?: boolean;
  loose?: boolean;
  lanes?: boolean;
}

export type LinkKind = 'wire' | 'route';

export type LinkShape = 'routed' | 'curve';

export interface BlueprintEdge {
  id: string;
  from: string;
  to: string;
  fromPort?: string;
  toPort?: string;
  kind?: LinkKind;
  family?: string;
  reversed?: boolean;
  curved?: boolean;
}

export type Point = readonly [number, number];

export interface Pin {
  key: string;
  type: string;
  provides: boolean;
  row: number;
  y: number;
  wiring: PinWiring;
}

export interface Card {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  rows: number;
  pins: Pin[];
  folded: number;
}

export interface Frame {
  key: string;
  label: string;
  x: number;
  y: number;
  width: number;
  height: number;
  nodes: string[];
  detail: string[];
  muted: boolean;
  loose: boolean;
}

export interface Link {
  id: string;
  from: string;
  to: string;
  fromPort?: string;
  toPort?: string;
  kind: LinkKind;
  family?: string;
  members: string[];
  shape: LinkShape;
  points: Point[];
  cross: boolean;
  reversed: boolean;
}

export interface BlueprintLayout {
  key: string;
  cards: Record<string, Card>;
  frames: Frame[];
  links: Link[];
  width: number;
  height: number;
}

export interface ElkPort {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  layoutOptions: Record<string, string>;
}

export interface ElkChild {
  id: string;
  width: number;
  height: number;
  ports: ElkPort[];
  layoutOptions: Record<string, string>;
}

export interface ElkEdgeInput {
  id: string;
  sources: string[];
  targets: string[];
}

export interface ElkGraphInput {
  id: string;
  layoutOptions: Record<string, string>;
  children: ElkChild[];
  edges: ElkEdgeInput[];
}

export interface ElkFrameResult {
  width: number;
  height: number;
  nodes: number[];
  edges: number[][];
}

export interface FrameRequest {
  key: string;
  hash: string;
  graph: ElkGraphInput;
  members: string[];
  routed: string[];
}

export interface FlatFrame {
  cards: Card[];
  links: Link[];
  width: number;
  height: number;
}

export interface LayoutOptions {
  hintMinimum?: number;
}
