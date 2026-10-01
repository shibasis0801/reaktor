import { useMemo, type CSSProperties, type ReactNode } from 'react';
import { isLit } from '../highlight';
import type { BlueprintLayout, Card, Link, Point } from '../types';
import { intersects, useHoveredPin, useRest, useScene, useSelection, type WireStyle } from './context';
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

function useNearCards(cards: Card[]): Set<string> {
  const window = useRest(rest => (rest.look === 'chapter' ? rest.window : null));
  return useMemo(() => (window ? new Set(cards.filter(card => intersects(window, card)).map(card => card.id)) : nothing), [window, cards]);
}

export function BlueprintWires() {
  const scene = useScene();
  const look = useLook();
  const hovered = useHoveredPin(scene.pins);
  const selection = useSelection(value => value);
  const { layout } = scene;
  const cards = useMemo(() => Object.values(layout.cards), [layout]);
  const visible = useNearCards(cards);
  const paths = useMemo(() => new Map(layout.links.map(link => [link.id, linkPath(link)])), [layout]);
  const corridors = useMemo(() => corridorsOf(layout, scene.frameOf), [layout, scene.frameOf]);
  const highlighting = selection.highlight.size > 0;
  const batches = new Map<string, { style: WireStyle; parts: string[]; lit: boolean }>();
  const singles: Array<{ link: Link; style: WireStyle; lit: boolean }> = [];
  const touchesHover = (link: Link) => !!hovered && (
    (link.from === hovered.card && link.fromPort !== undefined && scene.rowOfPort.get(`${link.from}>${link.fromPort}`) === hovered.row)
    || (link.to === hovered.card && link.toPort !== undefined && scene.rowOfPort.get(`${link.to}<${link.toPort}`) === hovered.row));
  const states = layout.links.map(link => {
    const route = selection.litLinks.has(link.id);
    return { link, route, lit: route || isLit(link, selection.highlight, selection.selected, selection.focus), hover: touchesHover(link) };
  });
  const crowd = states.reduce((count, state) => count + (state.lit ? 1 : 0), 0);
  for (const { link, route, lit, hover } of states) {
    if (look === 'domain' && !lit && !hover) continue;
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
  for (const { link, style } of singles) {
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
      <g className="bp-wires__each">
        {singles.map(({ link, style, lit }) => {
          const d = paths.get(link.id) ?? '';
          const end = style.arrow ? tail(link) : null;
          return <g key={link.id} className="bp-wire" data-link={link.id} data-lit={lit || undefined} data-marching={style.marching || undefined} style={{ '--bp-wire': style.tone, '--bp-alpha': style.alpha ?? 1 } as CSSProperties}>
            {style.glow && <path className="bp-wire__glow" d={d} style={{ strokeWidth: (style.width ?? 1.5) * 2.8 }}/>}
            <path className="bp-wire__line" d={d} style={{ strokeWidth: style.width ?? 1.5, strokeDasharray: style.marching ? undefined : style.dash }}/>
            {end && <g transform={`translate(${round(end.at[0])} ${round(end.at[1])}) rotate(${round(end.angle)})`}><path className="bp-wire__arrow" d="M-10 -5L0 0L-10 5Z"/></g>}
            <path className="bp-wire__hit" d={d}>{style.title && <title>{style.title}</title>}</path>
          </g>;
        })}
      </g>
    </svg>
    {labels.length > 0 && <div className="bp-wires__labels">{labels}</div>}
  </div>;
}
