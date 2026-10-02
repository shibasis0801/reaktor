import type { Bounds } from './camera';
import type { Link, Point } from '../types';

export const MarchPeriod = 12;
export const DashCell = 256;
const MinPiece = MarchPeriod * 4;
const Sampling = 16;

type Piece =
  | { kind: 'L'; a: Point; b: Point; length: number }
  | { kind: 'Q'; a: Point; c: Point; b: Point; length: number }
  | { kind: 'C'; points: readonly [Point, Point, Point, Point]; length: number };

interface Samples {
  table: Float64Array;
  xs: Float64Array;
  ys: Float64Array;
}

interface Mark {
  piece: number;
  t: number;
  x: number;
  y: number;
}

const round = (value: number) => Math.round(value * 10) / 10;

export class DashPiece {
  private drawn: string | null = null;
  readonly column: number;
  readonly row: number;
  constructor(private readonly pieces: Piece[], private readonly from: Mark, private readonly to: Mark) {
    this.column = Math.floor(from.x / DashCell);
    this.row = Math.floor(from.y / DashCell);
  }
  get d(): string {
    return this.drawn ??= pathBetween(this.pieces, this.from, this.to);
  }
}

function bezier(t: number, a: number, b: number, c: number, d: number): number {
  const u = 1 - t;
  return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d;
}

function slope(t: number, a: number, b: number, c: number, d: number): number {
  const u = 1 - t;
  return 3 * (u * u * (b - a) + 2 * u * t * (c - b) + t * t * (d - c));
}

function sampled(points: readonly [Point, Point, Point, Point]): Samples {
  const [[ax, ay], [bx, by], [cx, cy], [dx, dy]] = points;
  const hull = Math.hypot(bx - ax, by - ay) + Math.hypot(cx - bx, cy - by) + Math.hypot(dx - cx, dy - cy);
  const steps = Math.min(256, Math.max(12, Math.ceil(hull / Sampling)));
  const table = new Float64Array(steps + 1);
  const xs = new Float64Array(steps + 1);
  const ys = new Float64Array(steps + 1);
  xs[0] = ax;
  ys[0] = ay;
  for (let index = 1; index <= steps; index += 1) {
    const t = index / steps;
    xs[index] = bezier(t, ax, bx, cx, dx);
    ys[index] = bezier(t, ay, by, cy, dy);
    table[index] = table[index - 1] + Math.hypot(xs[index] - xs[index - 1], ys[index] - ys[index - 1]);
  }
  return { table, xs, ys };
}

function quadLength(a: Point, c: Point, b: Point): number {
  let length = 0;
  let px = a[0];
  let py = a[1];
  for (let index = 1; index <= 8; index += 1) {
    const t = index / 8;
    const u = 1 - t;
    const x = u * u * a[0] + 2 * u * t * c[0] + t * t * b[0];
    const y = u * u * a[1] + 2 * u * t * c[1] + t * t * b[1];
    length += Math.hypot(x - px, y - py);
    px = x;
    py = y;
  }
  return length;
}

function piecesOf(link: Link, samples: Map<number, Samples>, radius = 7): Piece[] {
  const points = link.points;
  if (points.length < 2) return [];
  if (link.shape === 'curve' && points.length >= 4) {
    const snap = (point: Point): Point => [round(point[0]), round(point[1])];
    const curve = [snap(points[0]), snap(points[1]), snap(points[2]), snap(points[3])] as const;
    const table = sampled(curve);
    samples.set(0, table);
    return [{ kind: 'C', points: curve, length: table.table[table.table.length - 1] }];
  }
  const pieces: Piece[] = [];
  let current: Point = points[0];
  const line = (to: Point) => { pieces.push({ kind: 'L', a: current, b: to, length: Math.hypot(to[0] - current[0], to[1] - current[1]) }); current = to; };
  for (let index = 1; index < points.length - 1; index += 1) {
    const [px, py] = points[index - 1];
    const [cx, cy] = points[index];
    const [nx, ny] = points[index + 1];
    const into = Math.hypot(cx - px, cy - py);
    const out = Math.hypot(nx - cx, ny - cy);
    const r = Math.min(radius, into / 2, out / 2);
    if (r < 0.5 || into === 0 || out === 0) { line([cx, cy]); continue; }
    const a: Point = [cx - (cx - px) / into * r, cy - (cy - py) / into * r];
    const b: Point = [cx + (nx - cx) / out * r, cy + (ny - cy) / out * r];
    line(a);
    pieces.push({ kind: 'Q', a, c: [cx, cy], b, length: quadLength(a, [cx, cy], b) });
    current = b;
  }
  line(points[points.length - 1]);
  return pieces;
}

function markAt(pieces: Piece[], index: number, t: number): Mark {
  const piece = pieces[index];
  if (piece.kind === 'C') {
    const [[ax, ay], [bx, by], [cx, cy], [dx, dy]] = piece.points;
    return { piece: index, t, x: bezier(t, ax, bx, cx, dx), y: bezier(t, ay, by, cy, dy) };
  }
  if (piece.kind === 'Q') return t <= 0 ? { piece: index, t: 0, x: piece.a[0], y: piece.a[1] } : { piece: index, t: 1, x: piece.b[0], y: piece.b[1] };
  return { piece: index, t, x: piece.a[0] + (piece.b[0] - piece.a[0]) * t, y: piece.a[1] + (piece.b[1] - piece.a[1]) * t };
}

export function dashPieces(link: Link): DashPiece[] {
  const samples = new Map<number, Samples>();
  const pieces = piecesOf(link, samples);
  if (pieces.length === 0) return [];
  let total = 0;
  for (const piece of pieces) total += piece.length;
  const marks: Mark[] = [markAt(pieces, 0, 0)];
  let begun = 0;
  let column = Math.floor(marks[0].x / DashCell);
  let row = Math.floor(marks[0].y / DashCell);
  let pending = -1;
  let start = 0;
  for (let index = 0; index < pieces.length; index += 1) {
    const piece = pieces[index];
    if (piece.kind === 'Q') {
      if (pending >= 0 && pending < start + piece.length) pending = Math.ceil((start + piece.length) / MarchPeriod) * MarchPeriod;
      if (pending >= total - MarchPeriod) pending = -1;
      if (pending < 0 && start + piece.length - begun >= MinPiece && (Math.floor(piece.b[0] / DashCell) !== column || Math.floor(piece.b[1] / DashCell) !== row)) {
        const next = Math.ceil((start + piece.length) / MarchPeriod) * MarchPeriod;
        if (next < total - MarchPeriod) pending = next;
      }
      start += piece.length;
      continue;
    }
    const curve = piece.kind === 'C' ? samples.get(index)! : null;
    const steps = curve ? curve.table.length - 1 : Math.max(1, Math.ceil(piece.length / Sampling));
    let previous = 0;
    for (let step = 1; step <= steps; step += 1) {
      const distance = curve ? curve.table[step] : piece.length * step / steps;
      const at = start + distance;
      if (pending >= 0 && pending <= at) {
        const span = distance - previous;
        const t = ((step - 1) + (span > 0 ? (pending - start - previous) / span : 0)) / steps;
        const mark = markAt(pieces, index, Math.min(1, Math.max(0, t)));
        marks.push(mark);
        begun = pending;
        column = Math.floor(mark.x / DashCell);
        row = Math.floor(mark.y / DashCell);
        pending = -1;
      }
      const x = curve ? curve.xs[step] : piece.kind === 'L' ? piece.a[0] + (piece.b[0] - piece.a[0]) * step / steps : 0;
      const y = curve ? curve.ys[step] : piece.kind === 'L' ? piece.a[1] + (piece.b[1] - piece.a[1]) * step / steps : 0;
      if (pending < 0 && at - begun >= MinPiece && (Math.floor(x / DashCell) !== column || Math.floor(y / DashCell) !== row)) {
        const next = Math.ceil(at / MarchPeriod) * MarchPeriod;
        if (next < total - MarchPeriod) pending = next;
      }
      previous = distance;
    }
    start += piece.length;
  }
  marks.push(markAt(pieces, pieces.length - 1, 1));
  const result: DashPiece[] = [];
  for (let index = 0; index + 1 < marks.length; index += 1) result.push(new DashPiece(pieces, marks[index], marks[index + 1]));
  return result;
}

function pathBetween(pieces: Piece[], from: Mark, to: Mark): string {
  let d = `M${round(from.x)} ${round(from.y)}`;
  for (let index = from.piece; index <= to.piece; index += 1) {
    const piece = pieces[index];
    const t0 = index === from.piece ? from.t : 0;
    const t1 = index === to.piece ? to.t : 1;
    if (t1 <= t0) continue;
    if (piece.kind === 'L') {
      d += `L${round(piece.a[0] + (piece.b[0] - piece.a[0]) * t1)} ${round(piece.a[1] + (piece.b[1] - piece.a[1]) * t1)}`;
    } else if (piece.kind === 'Q') {
      d += `Q${round(piece.c[0])} ${round(piece.c[1])} ${round(piece.b[0])} ${round(piece.b[1])}`;
    } else {
      const [[ax, ay], [bx, by], [cx, cy], [dx, dy]] = piece.points;
      const span = (t1 - t0) / 3;
      const x0 = bezier(t0, ax, bx, cx, dx);
      const y0 = bezier(t0, ay, by, cy, dy);
      const x3 = bezier(t1, ax, bx, cx, dx);
      const y3 = bezier(t1, ay, by, cy, dy);
      d += `C${round(x0 + span * slope(t0, ax, bx, cx, dx))} ${round(y0 + span * slope(t0, ay, by, cy, dy))} ${round(x3 - span * slope(t1, ax, bx, cx, dx))} ${round(y3 - span * slope(t1, ay, by, cy, dy))} ${round(x3)} ${round(y3)}`;
    }
  }
  return d;
}

export function linkBox(link: Link): Bounds {
  let left = Infinity;
  let top = Infinity;
  let right = -Infinity;
  let bottom = -Infinity;
  for (const [x, y] of link.points) {
    left = Math.min(left, x);
    top = Math.min(top, y);
    right = Math.max(right, x);
    bottom = Math.max(bottom, y);
  }
  return { x: left, y: top, width: right - left, height: bottom - top };
}

export function cellsAround(bounds: Bounds): { left: number; top: number; right: number; bottom: number } {
  return {
    left: Math.floor(bounds.x / DashCell) - 1,
    top: Math.floor(bounds.y / DashCell) - 1,
    right: Math.floor((bounds.x + bounds.width) / DashCell),
    bottom: Math.floor((bounds.y + bounds.height) / DashCell),
  };
}
