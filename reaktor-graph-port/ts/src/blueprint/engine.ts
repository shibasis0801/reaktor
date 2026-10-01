import { hashValue } from './hash';
import type {
  BlueprintEdge,
  BlueprintGroup,
  BlueprintLayout,
  BlueprintNode,
  Card,
  ElkChild,
  ElkEdgeInput,
  ElkFrameResult,
  ElkGraphInput,
  ElkPort,
  FlatFrame,
  Frame,
  FrameRequest,
  LayoutOptions,
  Link,
  LinkKind,
  Pin,
  PinSpec,
  Point,
} from './types';

export const CardWidth = 272;
export const HeaderHeight = 46;
export const RowHeight = 18;
export const FooterHeight = 8;
export const FrameTop = 40;
export const FrameSide = 18;
export const GroupGap = 64;
export const WrapAt = 12;
export const ColumnGap = 40;
export const RowGap = 18;
export const KitVersion = 'blueprint-kit-1';
export const ElkVersion = '0.12.0';

export interface Joined {
  id: string;
  from: string;
  to: string;
  fromPort?: string;
  toPort?: string;
  kind: LinkKind;
  family?: string;
  reversed: boolean;
  curved: boolean;
  members: string[];
}

export interface BlueprintPlan {
  key: string;
  groups: BlueprintGroup[];
  pins: Map<string, Pin[]>;
  groupOf: Map<string, string>;
  joined: Joined[];
  inside: Map<string, Joined[]>;
  requests: FrameRequest[];
}

export function rowsOf(node: BlueprintNode): number {
  if (node.rows !== undefined) return node.rows;
  const sides = [node.inputs ?? [], node.outputs ?? []];
  return sides.reduce((most, specs) => Math.max(most, specs.reduce((deepest, spec, index) => Math.max(deepest, (spec.row ?? index) + 1), 0)), 0);
}

export function cardHeight(rows: number): number {
  return HeaderHeight + rows * RowHeight + (rows > 0 ? FooterHeight : 0);
}

export function pinY(row: number): number {
  return HeaderHeight + row * RowHeight + RowHeight / 2;
}

export function pinsOf(node: BlueprintNode): Pin[] {
  const side = (specs: PinSpec[] | undefined, provides: boolean): Pin[] => (specs ?? []).map((spec, index) => {
    const row = spec.row ?? index;
    return { key: spec.key, type: spec.type, provides, row, y: pinY(row), wiring: spec.wiring };
  });
  return [...side(node.inputs, false), ...side(node.outputs, true)];
}

export function join(edges: BlueprintEdge[], pins: Map<string, Pin[]>): Joined[] {
  const ports = new Map<string, Set<string>>();
  pins.forEach((list, id) => ports.set(id, new Set(list.map(pin => `${pin.provides ? '>' : '<'}${pin.key}`))));
  const joined = new Map<string, Joined>();
  for (const edge of edges) {
    const fromPorts = ports.get(edge.from);
    const toPorts = ports.get(edge.to);
    if (edge.from === edge.to || !fromPorts || !toPorts) continue;
    const fromPort = edge.fromPort !== undefined && fromPorts.has(`>${edge.fromPort}`) ? edge.fromPort : undefined;
    const toPort = edge.toPort !== undefined && toPorts.has(`<${edge.toPort}`) ? edge.toPort : undefined;
    const kind = edge.kind ?? 'wire';
    const id = kind === 'route'
      ? `route:${edge.from}>${edge.to}`
      : `${edge.from}>${fromPort ?? ''}|${edge.to}<${toPort ?? ''}${edge.family ? `#${edge.family}` : ''}`;
    let entry = joined.get(id);
    if (!entry) {
      entry = { id, from: edge.from, to: edge.to, fromPort, toPort, kind, family: edge.family, reversed: !!edge.reversed, curved: !!edge.curved, members: [] };
      joined.set(id, entry);
    }
    entry.members.push(edge.id);
  }
  return [...joined.values()];
}

export function elkOptions(group: BlueprintGroup): Record<string, string> {
  const lanes = group.lanes ?? !group.loose;
  const options: Record<string, string> = {
    'elk.algorithm': 'layered',
    'elk.direction': 'RIGHT',
    'elk.edgeRouting': 'ORTHOGONAL',
    'elk.spacing.nodeNode': '18',
    'elk.layered.spacing.nodeNodeBetweenLayers': '72',
    'elk.layered.spacing.edgeNodeBetweenLayers': '16',
    'elk.layered.spacing.edgeEdgeBetweenLayers': '7',
    'elk.spacing.edgeEdge': '7',
    'elk.spacing.edgeNode': '12',
    'elk.randomSeed': '7',
    'elk.partitioning.activate': String(lanes),
    'elk.hierarchyHandling': 'SEPARATE_CHILDREN',
    'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
  };
  if (group.loose) options['elk.aspectRatio'] = '1.4';
  return options;
}

function hintPairs(curved: Joined[], routed: Joined[], minimum: number): Array<[string, string]> {
  if (!Number.isFinite(minimum)) return [];
  const linked = new Set(routed.flatMap(edge => [`${edge.from}|${edge.to}`, `${edge.to}|${edge.from}`]));
  const pairs = new Map<string, { forward: number; backward: number; low: string; high: string }>();
  for (const edge of curved) {
    const forward = edge.from < edge.to;
    const low = forward ? edge.from : edge.to;
    const high = forward ? edge.to : edge.from;
    const key = `${low}|${high}`;
    const entry = pairs.get(key) ?? { forward: 0, backward: 0, low, high };
    if (forward) entry.forward += edge.members.length; else entry.backward += edge.members.length;
    pairs.set(key, entry);
  }
  const hints: Array<[string, string]> = [];
  pairs.forEach((entry, key) => {
    if (entry.forward + entry.backward < minimum || linked.has(key)) return;
    hints.push(entry.forward >= entry.backward ? [entry.low, entry.high] : [entry.high, entry.low]);
  });
  return hints;
}

function frameRequest(group: BlueprintGroup, pins: Map<string, Pin[]>, joined: Joined[], options: LayoutOptions): FrameRequest {
  const lanes = group.lanes ?? !group.loose;
  const routed = joined.filter(edge => !edge.curved);
  const hints = hintPairs(joined.filter(edge => edge.curved), routed, options.hintMinimum ?? 1);
  const indexOf = new Map(group.nodes.map((node, index) => [node.id, index]));
  const used = group.nodes.map(() => new Set<string>());
  const portId = (id: string, port: string | undefined, provides: boolean): string => {
    const index = indexOf.get(id)!;
    const list = pins.get(id) ?? [];
    const pinIndex = port === undefined ? -1 : list.findIndex(pin => pin.provides === provides && pin.key === port);
    const name = `n${index}${provides ? 'e' : 'w'}${pinIndex >= 0 ? pinIndex : ''}`;
    used[index].add(name);
    return name;
  };
  const edges: ElkEdgeInput[] = [
    ...routed.map((edge, index) => ({ id: `e${index}`, sources: [portId(edge.from, edge.fromPort, true)], targets: [portId(edge.to, edge.toPort, false)] })),
    ...hints.map(([from, to], index) => ({ id: `e${routed.length + index}`, sources: [portId(from, undefined, true)], targets: [portId(to, undefined, false)] })),
  ];
  const children: ElkChild[] = group.nodes.map((node, index) => {
    const ports: ElkPort[] = [];
    (pins.get(node.id) ?? []).forEach((pin, pinIndex) => {
      const name = `n${index}${pin.provides ? 'e' : 'w'}${pinIndex}`;
      if (used[index].has(name)) ports.push({ id: name, x: pin.provides ? CardWidth : -1, y: pin.y, width: 1, height: 1, layoutOptions: { 'elk.port.side': pin.provides ? 'EAST' : 'WEST' } });
    });
    if (used[index].has(`n${index}w`)) ports.push({ id: `n${index}w`, x: -1, y: HeaderHeight / 2, width: 1, height: 1, layoutOptions: { 'elk.port.side': 'WEST' } });
    if (used[index].has(`n${index}e`)) ports.push({ id: `n${index}e`, x: CardWidth, y: HeaderHeight / 2, width: 1, height: 1, layoutOptions: { 'elk.port.side': 'EAST' } });
    const layoutOptions: Record<string, string> = { 'elk.portConstraints': 'FIXED_POS' };
    if (lanes) layoutOptions['elk.partitioning.partition'] = String(node.lane);
    return { id: `n${index}`, width: CardWidth, height: cardHeight(rowsOf(node)), ports, layoutOptions };
  });
  const graph: ElkGraphInput = { id: 'frame', layoutOptions: elkOptions(group), children, edges };
  return { key: group.key, hash: hashValue([KitVersion, ElkVersion, graph]), graph, members: group.nodes.map(node => node.id), routed: routed.map(edge => edge.id) };
}

export function plan(key: string, groups: BlueprintGroup[], edges: BlueprintEdge[], options: LayoutOptions = {}): BlueprintPlan {
  const pins = new Map<string, Pin[]>();
  const groupOf = new Map<string, string>();
  for (const group of groups) for (const node of group.nodes) {
    groupOf.set(node.id, group.key);
    pins.set(node.id, pinsOf(node));
  }
  const joined = join(edges, pins);
  const inside = new Map<string, Joined[]>();
  for (const edge of joined) {
    const group = groupOf.get(edge.from)!;
    if (group !== groupOf.get(edge.to)) continue;
    inside.set(group, [...(inside.get(group) ?? []), edge]);
  }
  const requests = groups.filter(group => group.nodes.length > 0).map(group => frameRequest(group, pins, inside.get(group.key) ?? [], options));
  return { key, groups, pins, groupOf, joined, inside, requests };
}

function anchorIn(card: Card | undefined, port: string | undefined, provides: boolean): Point {
  if (!card) return [0, 0];
  const pin = port === undefined ? undefined : card.pins.find(item => item.provides === provides && item.key === port);
  return [provides ? card.x + card.width : card.x, card.y + (pin?.y ?? HeaderHeight / 2)];
}

export function bezier(from: Point, to: Point): Point[] {
  const [sx, sy] = from;
  const [ex, ey] = to;
  const wide = Math.max(90, Math.abs(ex - sx) * 0.45);
  const reach = ex < sx ? Math.min(wide, 320) : wide;
  return [[sx, sy], [sx + reach, sy], [ex - reach, ey], [ex, ey]];
}

export function curve(from: Point, to: Point): Point[] {
  const [[sx, sy], [ax, ay], [bx, by], [ex, ey]] = bezier(from, to);
  return Array.from({ length: 25 }, (_, step) => {
    const t = step / 24;
    const u = 1 - t;
    return [
      u * u * u * sx + 3 * u * u * t * ax + 3 * u * t * t * bx + t * t * t * ex,
      u * u * u * sy + 3 * u * u * t * ay + 3 * u * t * t * by + t * t * t * ey,
    ] as Point;
  });
}

export function wrapTallLayers(flat: FlatFrame): FlatFrame {
  const layers = new Map<number, Card[]>();
  for (const card of flat.cards) {
    const key = Math.trunc(card.x);
    layers.set(key, [...(layers.get(key) ?? []), card]);
  }
  const ordered = [...layers.entries()].sort(([a], [b]) => a - b).map(([, layer]) => layer);
  if (!ordered.some(layer => layer.length > WrapAt)) return flat;
  const cards = new Map(flat.cards.map(card => [card.id, card]));
  const moved = new Set<string>();
  const shifts: Array<[number, number]> = [];
  let shifted = 0;
  for (const layer of ordered) {
    const right = layer[0].x + CardWidth;
    if (shifted !== 0) for (const card of layer) cards.set(card.id, { ...card, x: card.x + shifted });
    if (layer.length <= WrapAt) continue;
    const stacked = [...layer].sort((a, b) => a.y - b.y);
    const rows = Math.ceil(Math.sqrt(stacked.length * 2));
    const columns = Math.ceil(stacked.length / rows);
    const top = stacked[0].y;
    for (let column = 0; column < columns; column += 1) {
      let y = top;
      for (const card of stacked.slice(column * rows, column * rows + rows)) {
        cards.set(card.id, { ...card, x: card.x + shifted + column * (CardWidth + ColumnGap), y });
        moved.add(card.id);
        y += card.height + RowGap;
      }
    }
    const widened = (columns - 1) * (CardWidth + ColumnGap);
    shifts.push([right, widened]);
    shifted += widened;
  }
  const shift = (x: number) => x + shifts.filter(([from]) => x >= from).reduce((sum, [, by]) => sum + by, 0);
  const links = flat.links.map(link => (moved.has(link.from) || moved.has(link.to))
    ? { ...link, shape: 'curve' as const, points: bezier(anchorIn(cards.get(link.from), link.fromPort, true), anchorIn(cards.get(link.to), link.toPort, false)) }
    : { ...link, points: link.points.map(([x, y]) => [shift(x), y] as Point) });
  const laid = flat.cards.map(card => cards.get(card.id)!);
  const right = flat.width - Math.max(...flat.cards.map(card => card.x + card.width));
  const bottom = flat.height - Math.max(...flat.cards.map(card => card.y + card.height));
  return {
    cards: laid,
    links,
    width: Math.max(...laid.map(card => card.x + card.width)) + right,
    height: Math.max(...laid.map(card => card.y + card.height)) + bottom,
  };
}

export function readFrame(blueprint: BlueprintPlan, request: FrameRequest, result: ElkFrameResult): FlatFrame {
  const group = blueprint.groups.find(item => item.key === request.key)!;
  const routed = (blueprint.inside.get(request.key) ?? []).filter(edge => !edge.curved);
  const cards: Card[] = group.nodes.map((node, index) => {
    const rows = rowsOf(node);
    return {
      id: node.id,
      x: result.nodes[index * 2] ?? 0,
      y: result.nodes[index * 2 + 1] ?? 0,
      width: CardWidth,
      height: cardHeight(rows),
      rows,
      pins: blueprint.pins.get(node.id) ?? [],
      folded: node.folded ?? 0,
    };
  });
  const links: Link[] = routed.map((edge, index) => {
    const flatPoints = result.edges[index] ?? [];
    const points: Point[] = [];
    for (let cursor = 0; cursor + 1 < flatPoints.length; cursor += 2) points.push([flatPoints[cursor], flatPoints[cursor + 1]]);
    return {
      id: edge.id, from: edge.from, to: edge.to, fromPort: edge.fromPort, toPort: edge.toPort, kind: edge.kind, family: edge.family,
      members: edge.members, shape: 'routed', points, cross: false, reversed: edge.reversed,
    };
  });
  return wrapTallLayers({ cards, links, width: result.width, height: result.height });
}

interface Block {
  group: BlueprintGroup;
  flat: FlatFrame;
  width: number;
  height: number;
}

export function pack<T extends { width: number; height: number; group: { loose?: boolean } }>(blocks: T[], aspect: number): Array<[T, Point]> {
  const own = blocks.filter(block => !block.group.loose);
  const loose = blocks.filter(block => block.group.loose);
  const shelves = (group: T[], limit: number): T[][] => group.reduce<T[][]>((rows, block) => {
    const row = rows.find(candidate => candidate.reduce((sum, item) => sum + item.width, 0) + GroupGap * candidate.length + block.width <= limit);
    if (row) row.push(block); else rows.push([block]);
    return rows;
  }, []);
  const rowsFor = (limit: number) => [...shelves(own, limit), ...shelves(loose, limit)];
  const widthOf = (rows: T[][]) => rows.reduce((most, row) => Math.max(most, row.reduce((sum, item) => sum + item.width, 0) + GroupGap * (row.length - 1)), 0);
  const heightOf = (rows: T[][]) => rows.reduce((sum, row) => sum + Math.max(...row.map(item => item.height)), 0) + GroupGap * Math.max(rows.length - 1, 0);
  const span = (group: T[]) => group.reduce((sum, item) => sum + item.width, 0) + GroupGap * Math.max(group.length - 1, 0);
  const widest = blocks.reduce((most, block) => Math.max(most, block.width), 0);
  const longest = Math.max(span(own), span(loose));
  const limits = [...new Set(Array.from({ length: 61 }, (_, step) => widest + (longest - widest) * step / 60))];
  let chosen: T[][] = [];
  let best = Infinity;
  for (const limit of limits) {
    const rows = rowsFor(limit);
    const score = Math.abs(Math.log(widthOf(rows) / Math.max(heightOf(rows), 1) / aspect)) + 0.015 * rows.length;
    if (score < best) {
      best = score;
      chosen = rows;
    }
  }
  const placed: Array<[T, Point]> = [];
  let y = 0;
  for (const row of chosen) {
    let x = 0;
    for (const block of row) {
      placed.push([block, [x, y]]);
      x += block.width + GroupGap;
    }
    y += Math.max(...row.map(item => item.height)) + GroupGap;
  }
  return placed;
}

export function assemble(blueprint: BlueprintPlan, flats: Map<string, FlatFrame>, aspect: number): BlueprintLayout {
  const blocks: Block[] = blueprint.groups.flatMap(group => {
    const flat = flats.get(group.key);
    if (!flat || flat.cards.length === 0) return [];
    return [{ group, flat, width: flat.width + 2 * FrameSide, height: flat.height + FrameTop + FrameSide }];
  });
  const cards: Record<string, Card> = {};
  const links: Link[] = [];
  const frames: Frame[] = [];
  for (const [block, [x, y]] of pack(blocks, aspect)) {
    const ox = x + FrameSide;
    const oy = y + FrameTop;
    for (const card of block.flat.cards) cards[card.id] = { ...card, x: card.x + ox, y: card.y + oy };
    for (const link of block.flat.links) links.push({ ...link, points: link.points.map(([px, py]) => [px + ox, py + oy] as Point) });
    frames.push({
      key: block.group.key, label: block.group.label, x, y, width: block.width, height: block.height,
      nodes: block.flat.cards.map(card => card.id), detail: block.group.detail ?? [], muted: !!block.group.muted, loose: !!block.group.loose,
    });
  }
  for (const edge of blueprint.joined) {
    const from = cards[edge.from];
    const to = cards[edge.to];
    if (!from || !to) continue;
    const cross = blueprint.groupOf.get(edge.from) !== blueprint.groupOf.get(edge.to);
    if (!cross && !edge.curved) continue;
    links.push({
      id: edge.id, from: edge.from, to: edge.to, fromPort: edge.fromPort, toPort: edge.toPort, kind: edge.kind, family: edge.family,
      members: edge.members, shape: 'curve', points: bezier(anchorIn(from, edge.fromPort, true), anchorIn(to, edge.toPort, false)), cross, reversed: edge.reversed,
    });
  }
  return {
    key: blueprint.key,
    cards,
    frames,
    links,
    width: frames.reduce((most, frame) => Math.max(most, frame.x + frame.width), 0),
    height: frames.reduce((most, frame) => Math.max(most, frame.y + frame.height), 0),
  };
}

interface ElkLaidSection {
  startPoint: { x: number; y: number };
  endPoint: { x: number; y: number };
  bendPoints?: Array<{ x: number; y: number }>;
}

export interface ElkLaidGraph {
  id?: string;
  width?: number;
  height?: number;
  children?: Array<{ id?: string; x?: number; y?: number }>;
  edges?: Array<{ id?: string; sections?: ElkLaidSection[] }>;
}

export function compactElkResult(graph: ElkLaidGraph): ElkFrameResult {
  return {
    width: graph.width ?? 0,
    height: graph.height ?? 0,
    nodes: (graph.children ?? []).flatMap(child => [child.x ?? 0, child.y ?? 0]),
    edges: (graph.edges ?? []).map(edge => (edge.sections ?? []).flatMap(section => [
      section.startPoint.x, section.startPoint.y,
      ...(section.bendPoints ?? []).flatMap(point => [point.x, point.y]),
      section.endPoint.x, section.endPoint.y,
    ])),
  };
}

export function layoutWith(key: string, groups: BlueprintGroup[], edges: BlueprintEdge[], aspect: number, layouter: (graph: ElkGraphInput) => ElkFrameResult, options: LayoutOptions = {}): BlueprintLayout {
  const blueprint = plan(key, groups, edges, options);
  const flats = new Map(blueprint.requests.map(request => [request.key, readFrame(blueprint, request, layouter(request.graph))]));
  return assemble(blueprint, flats, aspect);
}

export function aspectFor(width: number, height: number, low = 0.5, high = 6): number {
  if (width <= 0 || height <= 0) return 1.6;
  return Math.min(high, Math.max(low, Math.round(width / height * 4) / 4));
}

export function layoutBounds(layout: BlueprintLayout): { x: number; y: number; width: number; height: number } {
  if (layout.frames.length === 0) return { x: 0, y: 0, width: 0, height: 0 };
  const left = Math.min(...layout.frames.map(frame => frame.x));
  const top = Math.min(...layout.frames.map(frame => frame.y));
  return { x: left, y: top, width: layout.width - left, height: layout.height - top };
}
