export type Focus = 'all' | 'near' | 'upstream' | 'downstream' | 'path';

export interface Directed {
  from: string;
  to: string;
}

export function adjacency(links: Iterable<Directed>): { next: Map<string, string[]>; previous: Map<string, string[]> } {
  const next = new Map<string, string[]>();
  const previous = new Map<string, string[]>();
  for (const link of links) {
    if (link.from === link.to) continue;
    next.set(link.from, [...(next.get(link.from) ?? []), link.to]);
    previous.set(link.to, [...(previous.get(link.to) ?? []), link.from]);
  }
  return { next, previous };
}

export function walk(start: string, edges: Map<string, string[]>): Set<string> {
  const seen = new Set([start]);
  const frontier = [start];
  for (let cursor = 0; cursor < frontier.length; cursor += 1) {
    for (const next of edges.get(frontier[cursor]) ?? []) {
      if (seen.has(next)) continue;
      seen.add(next);
      frontier.push(next);
    }
  }
  return seen;
}

function intersect(a: Set<string>, b: Set<string>): Set<string> {
  return new Set([...a].filter(item => b.has(item)));
}

export function highlightFor(links: Iterable<Directed>, selected: string | null, focus: Focus, pathFrom: string | null, matched: ReadonlySet<string> | null): Set<string> {
  const matches = new Set(matched ?? []);
  if (selected === null) return matches;
  const { next, previous } = adjacency(links);
  let focused: Set<string>;
  if (focus === 'upstream') focused = walk(selected, previous);
  else if (focus === 'downstream') focused = walk(selected, next);
  else if (focus === 'path') {
    if (pathFrom === null || pathFrom === selected) focused = new Set([selected]);
    else {
      const forward = intersect(walk(pathFrom, next), walk(selected, previous));
      const backward = intersect(walk(selected, next), walk(pathFrom, previous));
      focused = new Set([...forward, ...backward, pathFrom, selected]);
    }
  } else focused = new Set([selected, ...(next.get(selected) ?? []), ...(previous.get(selected) ?? [])]);
  return matched ? new Set([...focused, ...matches]) : focused;
}

export function isLit(link: Directed, highlight: ReadonlySet<string>, selected: string | null, focus: Focus): boolean {
  return highlight.size > 0 && highlight.has(link.from) && highlight.has(link.to)
    && (selected === null || focus !== 'all' || link.from === selected || link.to === selected);
}
