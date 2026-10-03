import { memo, useDeferredValue, useMemo, useRef, type CSSProperties, type ReactNode } from 'react';
import { isLit, type Directed } from '../highlight';
import type { BlueprintLayout, Card, Link, Point } from '../types';
import type { Bounds } from './camera';
import { intersects, useHoveredPin, useRest, useScene, useSelection, type WireStyle } from './context';
import { cellsAround, DashCell, dashPieces, linkBox, type DashPiece } from './dashes';
import { useLook } from './looks';
import { linkMidpoint, linkPath } from './paths';

export interface Corridor {
  key: string;
  from: string;
  to: string;
  count: number;
  points: Point[];
}

const round = (value: number) => Math.round(value * 10) / 10;

export function corridorsOf(layout: BlueprintLayout, frameOf: Map<string, string>): Corridor[] {
  const frames = new Map(layout.frames.map(frame => [frame.key, frame]));
  const pairs = new Map<string, Corridor>();
  for (const link of layout.links) {
    if (!link.cross) continue;
    const a = frameOf.get(link.from);
    const b = frameOf.get(link.to);
    if (!a || !b || a === b) continue;
    const key = a < b ? `${a}|${b}` : `${b}|${a}`;
    const entry = pairs.get(key) ?? { key, from: a < b ? a : b, to: a < b ? b : a, count: 0, points: [] };
    entry.count += link.members.length;
    pairs.set(key, entry);
  }
  const edge = (frame: { x: number; y: number; width: number; height: number }, towards: Point): Point => {
    const cx = frame.x + frame.width / 2;
    const cy = frame.y + frame.height / 2;
    const dx = towards[0] - cx;
    const dy = towards[1] - cy;
    const scale = Math.min(dx === 0 ? Infinity : (frame.width / 2) / Math.abs(dx), dy === 0 ? Infinity : (frame.height / 2) / Math.abs(dy), 1);
    return [cx + dx * scale, cy + dy * scale];
  };
  return [...pairs.values()].map(corridor => {
    const a = frames.get(corridor.from)!;
    const b = frames.get(corridor.to)!;
    const start = edge(a, [b.x + b.width / 2, b.y + b.height / 2]);
    const end = edge(b, [a.x + a.width / 2, a.y + a.height / 2]);
    const dx = end[0] - start[0];
    const dy = end[1] - start[1];
    const bend = 0.12;
    const control: Point = [(start[0] + end[0]) / 2 - dy * bend, (start[1] + end[1]) / 2 + dx * bend];
    return { ...corridor, points: [start, control, end] };
  }).sort((x, y) => y.count - x.count || (x.key < y.key ? -1 : 1));
}

function corridorPath(points: Point[]): string {
  const [a, c, b] = points;
  return `M${round(a[0])} ${round(a[1])}Q${round(c[0])} ${round(c[1])} ${round(b[0])} ${round(b[1])}`;
}

function corridorMid(points: Point[]): Point {
  const [a, c, b] = points;
  return [0.25 * a[0] + 0.5 * c[0] + 0.25 * b[0], 0.25 * a[1] + 0.5 * c[1] + 0.25 * b[1]];
}

function tail(link: Link): { at: Point; angle: number } | null {
  const points = link.points;
  if (points.length < 2) return null;
  const end = link.reversed ? points[0] : points[points.length - 1];
  const before = link.reversed ? points[1] : points[points.length - 2];
  return { at: end, angle: Math.atan2(end[1] - before[1], end[0] - before[0]) * 180 / Math.PI };
}

const nothing = new Set<string>();
const PoolCrowd = 120;

function useNearCards(cards: Card[]): Set<string> {
  const window = useRest(rest => (rest.look === 'chapter' ? rest.window : null));
  return useMemo(() => (window ? new Set(cards.filter(card => intersects(window, card)).map(card => card.id)) : nothing), [window, cards]);
}

type Rows = ReadonlyMap<number, DashPiece[]>;
type Pieces = (link: Link, within: Bounds) => Rows;

interface March {
  link: Link;
  style: WireStyle;
  key: string;
  top: number;
  bottom: number;
}

const noRows: Rows = new Map();
const noMarches: March[] = [];

function byRow(pieces: DashPiece[]): Rows {
  const rows = new Map<number, DashPiece[]>();
  for (const piece of pieces) {
    const row = rows.get(piece.row);
    if (row) row.push(piece);
    else rows.set(piece.row, [piece]);
  }
  return rows;
}

function useDashes(layout: BlueprintLayout): Pieces {
  return useMemo(() => {
    const known = new Map<string, { box: Bounds; rows: Rows | null }>();
    return (link: Link, within: Bounds) => {
      let entry = known.get(link.id);
      if (!entry) { entry = { box: linkBox(link), rows: null }; known.set(link.id, entry); }
      if (!intersects(within, entry.box)) return noRows;
      return entry.rows ??= byRow(dashPieces(link));
    };
  }, [layout]);
}

function sameMarches(a: March[], b: March[]): boolean {
  return a.length === b.length && a.every((item, index) => item.link === b[index].link && item.key === b[index].key && item.style.label === b[index].style.label && item.style.title === b[index].style.title);
}

const MarchSegment = memo(function MarchSegment({ row, left, right, marches, pieces, urgent }: { row: number; left: number; right: number; marches: March[]; pieces: Pieces; urgent: boolean }) {
  const later = useDeferredValue(marches);
  const used = urgent ? marches : later;
  return useMemo(() => {
    const groups = new Map<string, { style: WireStyle; parts: string[] }>();
    const band = { x: left * DashCell - DashCell, y: row * DashCell - DashCell, width: (right - left + 3) * DashCell, height: 3 * DashCell };
    for (const { link, style, key } of used) {
      const list = pieces(link, band).get(row);
      if (!list) continue;
      for (const piece of list) {
        if (piece.column < left || piece.column > right) continue;
        const cell = `${key}|${piece.column}`;
        let group = groups.get(cell);
        if (!group) { group = { style, parts: [] }; groups.set(cell, group); }
        group.parts.push(piece.d);
      }
    }
    return <>{[...groups.entries()].map(([key, { style, parts }]) => <g key={key} className="bp-wire bp-wire--pooled" data-lit data-marching style={{ '--bp-wire': style.tone, '--bp-alpha': style.alpha ?? 1 } as CSSProperties}>
      <path className="bp-wire__line" d={parts.join('')} style={{ strokeWidth: style.width ?? 1.5 }}/>
    </g>)}</>;
  }, [used, row, left, right, pieces]);
});

function marchSegments(cells: { left: number; top: number; right: number; bottom: number }, view: Bounds | null) {
  const near = view ? {
    left: Math.floor((view.x - view.width / 4) / DashCell), right: Math.floor((view.x + view.width * 1.25) / DashCell),
    top: Math.floor((view.y - view.height / 4) / DashCell), bottom: Math.floor((view.y + view.height * 1.25) / DashCell),
  } : cells;
  const from = Math.min(Math.max(cells.left, near.left), cells.right + 1);
  const to = Math.max(Math.min(cells.right, near.right), from - 1);
  const segments: Array<{ key: string; row: number; left: number; right: number; urgent: boolean }> = [];
  for (let row = cells.top; row <= cells.bottom; row += 1) {
    const close = row >= near.top && row <= near.bottom;
    segments.push({ key: `${row}<`, row, left: cells.left, right: from - 1, urgent: false });
    segments.push({ key: `${row}=`, row, left: from, right: to, urgent: close });
    segments.push({ key: `${row}>`, row, left: to + 1, right: cells.right, urgent: false });
  }
  return segments.filter(segment => segment.left <= segment.right);
}

function marchesByRow(marches: March[]): Map<number, March[]> {
  const rows = new Map<number, March[]>();
  for (const march of marches) for (let row = march.top; row <= march.bottom; row += 1) {
    const list = rows.get(row);
    if (list) list.push(march);
    else rows.set(row, [march]);
  }
  return rows;
}

export function BlueprintWires() {
  const scene = useScene();
  const look = useLook();
  const hovered = useHoveredPin(scene.pins);
  const selection = useSelection(value => value);
  const { layout } = scene;
  const cards = useMemo(() => Object.values(layout.cards), [layout]);
  const visible = useNearCards(cards);
  const area = useRest(rest => (rest.look === 'chapter' ? rest.window : null));
  const view = useRest(rest => (rest.look === 'chapter' ? rest.view : null));
  const cells = useMemo(() => (area ? cellsAround(area) : null), [area]);
  const dashes = useDashes(layout);
  const inCells = (piece: DashPiece) => !!cells && piece.column >= cells.left && piece.column <= cells.right && piece.row >= cells.top && piece.row <= cells.bottom;
  const paths = useMemo(() => new Map(layout.links.map(link => [link.id, linkPath(link)])), [layout]);
  const corridors = useMemo(() => corridorsOf(layout, scene.frameOf), [layout, scene.frameOf]);
  const owners = scene.owners;
  const ends = useMemo(() => owners && owners.size > 0 ? new Map<string, Directed>(layout.links.map(link => [link.id, { from: owners.get(link.from) ?? link.from, to: owners.get(link.to) ?? link.to }])) : null, [layout, owners]);
  const endsOf = (link: Link): Directed => ends?.get(link.id) ?? link;
  const highlighting = selection.highlight.size > 0;
  const batches = new Map<string, { style: WireStyle; parts: string[]; lit: boolean }>();
  const singles: Array<{ link: Link; style: WireStyle; lit: boolean }> = [];
  const touchesHover = (link: Link) => !!hovered && (
    (link.from === hovered.card && link.fromPort !== undefined && scene.rowOfPort.get(`${link.from}>${link.fromPort}`) === hovered.row)
    || (link.to === hovered.card && link.toPort !== undefined && scene.rowOfPort.get(`${link.to}<${link.toPort}`) === hovered.row));
  const states = layout.links.map(link => {
    const route = selection.litLinks.has(link.id);
    return { link, route, lit: route || isLit(endsOf(link), selection.highlight, selection.selected, selection.focus), hover: touchesHover(link) };
  });
  const crowd = states.reduce((count, state) => count + (state.lit ? 1 : 0), 0);
  const pooling = !!cells && crowd > PoolCrowd;
  const kept = useRef<March[]>(noMarches);
  const marches = useMemo(() => {
    if (!pooling) return kept.current = noMarches;
    const list: March[] = [];
    for (const link of layout.links) {
      if (selection.litLinks.has(link.id) || !isLit(ends?.get(link.id) ?? link, selection.highlight, selection.selected, selection.focus)) continue;
      const nearby = visible.has(link.from) || visible.has(link.to);
      const style = scene.wireStyle(link, { look, lit: true, route: false, hovered: false, nearby, crowd, highlighting, focus: selection.focus });
      if (!style?.batch || !style.marching) continue;
      const box = linkBox(link);
      list.push({ link, style, key: `${style.tone}|${style.width ?? 1.5}|${style.alpha ?? 1}`, top: Math.floor(box.y / DashCell) - 1, bottom: Math.floor((box.y + box.height) / DashCell) + 1 });
    }
    return kept.current = sameMarches(kept.current, list) ? kept.current : list;
  }, [pooling, layout, selection, visible, scene, look, crowd, highlighting, ends]);
  const marched = useMemo(() => new Set(marches.map(march => march.link.id)), [marches]);
  const marchRows = useMemo(() => marchesByRow(marches), [marches]);
  const pooledLabels = marches.filter(march => march.style.label);
  for (const { link, route, lit, hover } of states) {
    if (look === 'domain' && !lit && !hover) continue;
    if (marched.has(link.id) && !hover) continue;
    const nearby = look === 'chapter' && (visible.has(link.from) || visible.has(link.to));
    const style = scene.wireStyle(link, { look, lit, route, hovered: hover, nearby, crowd, highlighting, focus: selection.focus });
    if (!style) continue;
    const pooled = style.batch && !style.marching && !style.dash && !hover && (!lit || (crowd > 120 && !route));
    if (pooled) {
      const key = `${lit ? 'lit' : 'faint'}|${style.tone}|${style.width ?? 1.5}|${style.dash ?? ''}|${style.alpha ?? 1}|${style.glow ? 'glow' : ''}|${style.marching ? 'march' : ''}`;
      const batch = batches.get(key) ?? { style, parts: [], lit };
      batch.parts.push(paths.get(link.id) ?? '');
      batches.set(key, batch);
    } else singles.push({ link, style, lit: lit || hover });
  }
  singles.sort((a, b) => Number(a.lit) - Number(b.lit));
  const labels: ReactNode[] = [];
  const showCorridors = look === 'domain' && scene.corridors;
  if (showCorridors) for (const corridor of corridors.slice(0, 10)) {
    const [x, y] = corridorMid(corridor.points);
    labels.push(<span key={`corridor:${corridor.key}`} className="bp-pill bp-pill--corridor" data-part="corridor-label" style={{ left: x, top: y }}>×{corridor.count}</span>);
  }
  for (const { link, style } of [...pooledLabels, ...singles]) {
    if (!style.label) continue;
    const [x, y] = linkMidpoint(link);
    labels.push(<span key={`label:${link.id}`} className="bp-pill" data-part="wire-label" style={{ left: x, top: y, '--bp-wire': style.tone } as CSSProperties}>{style.label}</span>);
  }
  return <div className="bp-wires" data-part="wires" style={{ width: layout.width, height: layout.height }}>
    <svg className="bp-wires__svg" width={Math.max(layout.width, 1)} height={Math.max(layout.height, 1)} aria-hidden="true">
      {showCorridors && <g className="bp-corridors">
        {corridors.map(corridor => <path key={corridor.key} className="bp-corridor" d={corridorPath(corridor.points)} style={{ strokeWidth: Math.min(2 + Math.log2(corridor.count + 1) * 1.6, 12) }}/>)}
      </g>}
      <g className="bp-wires__batch">
        {[...batches.entries()].map(([key, { style, parts, lit }]) => <g key={key} className="bp-wire bp-wire--pooled" data-lit={lit || undefined} data-marching={style.marching || undefined} style={{ '--bp-wire': style.tone, '--bp-alpha': style.alpha ?? 1 } as CSSProperties}>
          {style.glow && <path className="bp-wire__glow" d={parts.join('')} style={{ strokeWidth: (style.width ?? 1.5) * 2.8 }}/>}
          <path className="bp-wire__line" d={parts.join('')} style={{ strokeWidth: style.width ?? 1.5, strokeDasharray: style.marching ? undefined : style.dash }}/>
        </g>)}
      </g>
      {cells && <g className="bp-wires__march">
        {marchSegments(cells, view).map(segment => <MarchSegment key={segment.key} row={segment.row} left={segment.left} right={segment.right} marches={marchRows.get(segment.row) ?? noMarches} pieces={dashes} urgent={segment.urgent}/>)}
      </g>}
      <g className="bp-wires__each">
        {singles.map(({ link, style, lit }) => {
          const d = paths.get(link.id) ?? '';
          const end = style.arrow ? tail(link) : null;
          const pieces = area && style.marching ? [...dashes(link, area).values()].flat().filter(inCells) : null;
          return <g key={link.id} className="bp-wire" data-link={link.id} data-lit={lit || undefined} data-marching={style.marching || undefined} style={{ '--bp-wire': style.tone, '--bp-alpha': style.alpha ?? 1 } as CSSProperties}>
            {style.glow && <path className="bp-wire__glow" d={d} style={{ strokeWidth: (style.width ?? 1.5) * 2.8 }}/>}
            {pieces ? pieces.map((piece, index) => <path key={index} className="bp-wire__line" d={piece.d} style={{ strokeWidth: style.width ?? 1.5 }}/>) : <path className="bp-wire__line" d={d} style={{ strokeWidth: style.width ?? 1.5, strokeDasharray: style.marching ? undefined : style.dash }}/>}
            {end && <g transform={`translate(${round(end.at[0])} ${round(end.at[1])}) rotate(${round(end.angle)})`}><path className="bp-wire__arrow" d="M-10 -5L0 0L-10 5Z"/></g>}
            <path className="bp-wire__hit" d={d}>{style.title && <title>{style.title}</title>}</path>
          </g>;
        })}
      </g>
    </svg>
    {labels.length > 0 && <div className="bp-wires__labels">{labels}</div>}
  </div>;
}
