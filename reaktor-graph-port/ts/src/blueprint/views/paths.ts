import type { Link, Point } from '../types';

const round = (value: number) => Math.round(value * 10) / 10;

export function routedPath(points: readonly Point[], radius = 7): string {
  if (points.length < 2) return '';
  let path = `M${round(points[0][0])} ${round(points[0][1])}`;
  for (let index = 1; index < points.length - 1; index += 1) {
    const [px, py] = points[index - 1];
    const [cx, cy] = points[index];
    const [nx, ny] = points[index + 1];
    const into = Math.hypot(cx - px, cy - py);
    const out = Math.hypot(nx - cx, ny - cy);
    const r = Math.min(radius, into / 2, out / 2);
    if (r < 0.5 || into === 0 || out === 0) {
      path += `L${round(cx)} ${round(cy)}`;
      continue;
    }
    const ax = cx - (cx - px) / into * r;
    const ay = cy - (cy - py) / into * r;
    const bx = cx + (nx - cx) / out * r;
    const by = cy + (ny - cy) / out * r;
    path += `L${round(ax)} ${round(ay)}Q${round(cx)} ${round(cy)} ${round(bx)} ${round(by)}`;
  }
  const [lx, ly] = points[points.length - 1];
  return `${path}L${round(lx)} ${round(ly)}`;
}

export function curvePath(points: readonly Point[]): string {
  if (points.length < 4) return routedPath(points);
  const [a, b, c, d] = points;
  return `M${round(a[0])} ${round(a[1])}C${round(b[0])} ${round(b[1])} ${round(c[0])} ${round(c[1])} ${round(d[0])} ${round(d[1])}`;
}

export function linkPath(link: Link): string {
  return link.shape === 'curve' ? curvePath(link.points) : routedPath(link.points);
}

export function bezierAt(points: readonly Point[], t: number): Point {
  const [a, b, c, d] = points;
  const u = 1 - t;
  return [
    u * u * u * a[0] + 3 * u * u * t * b[0] + 3 * u * t * t * c[0] + t * t * t * d[0],
    u * u * u * a[1] + 3 * u * u * t * b[1] + 3 * u * t * t * c[1] + t * t * t * d[1],
  ];
}

export function linkMidpoint(link: Link): Point {
  if (link.shape === 'curve' && link.points.length >= 4) return bezierAt(link.points, 0.5);
  const lengths = link.points.slice(1).map((point, index) => Math.hypot(point[0] - link.points[index][0], point[1] - link.points[index][1]));
  let remaining = lengths.reduce((sum, length) => sum + length, 0) / 2;
  for (let index = 0; index < lengths.length; index += 1) {
    if (remaining <= lengths[index]) {
      const t = lengths[index] === 0 ? 0 : remaining / lengths[index];
      const [ax, ay] = link.points[index];
      const [bx, by] = link.points[index + 1];
      return [ax + (bx - ax) * t, ay + (by - ay) * t];
    }
    remaining -= lengths[index];
  }
  return link.points[0] ?? [0, 0];
}
