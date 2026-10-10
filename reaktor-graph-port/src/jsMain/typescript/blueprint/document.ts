import { graphScopes, type GraphDocument, type GraphEdge, type GraphNode } from '../document';
import { CardWidth, cardHeight, FrameSide, FrameTop, LayerGap, pinsOf } from './engine';
import type { BlueprintLayout, Card, Frame, Link } from './types';

export function graphDocumentLayout(graph: GraphDocument): BlueprintLayout {
  const cards: Record<string, Card> = Object.create(null);
  for (const node of graph.nodes) {
    const linked = (key: string, provides: boolean) => graph.edges.some(edge => edge.type === 'port' && (provides ? edge.targetNode === node.id && edge.targetPort === key : edge.sourceNode === node.id && edge.sourcePort === key));
    const inputs = node.consumers.map(port => ({ ...port, wiring: linked(port.key, false) ? 'linked' as const : 'unlinked' as const }));
    const outputs = node.providers.map(port => ({ ...port, wiring: linked(port.key, true) ? 'linked' as const : 'unlinked' as const }));
    const rows = Math.max(inputs.length, outputs.length) + (node.graph ? 1 : 0);
    cards[node.id] = { id: node.id, ...node.position, width: CardWidth, height: cardHeight(rows), rows, pins: pinsOf({ id: node.id, lane: 0, inputs, outputs }), folded: 0 };
  }
  const groups = new Map<string, string[]>();
  for (const node of graph.nodes) { const group = node.group || graph.label; groups.set(group, [...(groups.get(group) ?? []), node.id]); }
  const frames: Frame[] = [...groups].map(([label, nodes], index) => {
    const members = nodes.map(id => cards[id]), x = Math.min(...members.map(card => card.x)) - FrameSide, y = Math.min(...members.map(card => card.y)) - FrameTop;
    return { key: `group_${index}`, label, x, y, width: Math.max(...members.map(card => card.x + card.width)) - x + FrameSide, height: Math.max(...members.map(card => card.y + card.height)) - y + FrameSide, nodes, detail: [], muted: false, loose: false };
  });
  const links: Link[] = graph.edges.map(edge => {
    const port = edge.type === 'port', from = cards[port ? edge.targetNode : edge.sourceNode], to = cards[port ? edge.sourceNode : edge.targetNode];
    const fromPort = port ? edge.targetPort : undefined, toPort = port ? edge.sourcePort : undefined;
    const y = (card: Card, key: string | undefined, provides: boolean) => card.y + (key ? card.pins.find(pin => pin.key === key && pin.provides === provides)?.y ?? card.height / 2 : card.height / 2);
    const start: [number, number] = [from.x + from.width, y(from, fromPort, true)], end: [number, number] = [to.x, y(to, toPort, false)];
    const points: [number, number][] = from.id === to.id ? [start, [from.x + from.width + 70, from.y - 50], [from.x - 70, from.y - 50], end] : [start, end];
    return { id: edge.id, from: from.id, to: to.id, fromPort, toPort, kind: port ? 'wire' : 'route', family: edge.type, members: [edge.id], shape: 'curve', points, cross: false, reversed: false };
  });
  return { key: graph.id, cards, frames, links, width: Math.max(1, ...frames.map(frame => frame.x + frame.width)), height: Math.max(1, ...frames.map(frame => frame.y + frame.height)) };
}

export const graphNodeKey = (scope: string, node: string) => `node:${JSON.stringify([scope, node])}`;
export type GraphDetail = 'compact' | 'linked' | 'every';
export interface GraphSubject { scope: GraphDocument; node: GraphNode; offset?: { x: number; y: number } }
export interface GraphScene {
  layout: BlueprintLayout;
  nodes: Map<string, GraphSubject>;
  edges: Map<string, { scope: GraphDocument; edge: GraphEdge }>;
}

export function graphDocumentScene(root: GraphDocument, expanded: ReadonlySet<string>, detail: GraphDetail): GraphScene {
  const nodes = new Map<string, GraphSubject>(), edges = new Map<string, { scope: GraphDocument; edge: GraphEdge }>();
  const exposed = new Set<string>();
  for (const scope of graphScopes(root)) {
    for (const node of scope.nodes) {
      nodes.set(graphNodeKey(scope.id, node.id), { scope, node });
      for (const binding of node.boundary ?? []) exposed.add(JSON.stringify([node.graph!.id, binding.node, binding.direction, binding.port]));
    }
    for (const edge of scope.edges) edges.set(`edge:${JSON.stringify([scope.id, edge.id])}`, { scope, edge });
  }
  const build = (scope: GraphDocument): BlueprintLayout => {
    const children = new Map(scope.nodes.filter(node => node.graph && expanded.has(node.graph.id)).map(node => [node.id, build(node.graph!)]));
    const ports = scope.nodes.map(node => {
      const used = (key: string, provides: boolean) => scope.edges.some(edge => edge.type === 'port' && (provides ? edge.targetNode === node.id && edge.targetPort === key : edge.sourceNode === node.id && edge.sourcePort === key)) || node.boundary?.some(binding => binding.direction === (provides ? 'providers' : 'consumers') && binding.key === key) || exposed.has(JSON.stringify([scope.id, node.id, provides ? 'providers' : 'consumers', key]));
      return { ...node, consumers: detail === 'compact' ? [] : detail === 'linked' ? node.consumers.filter(port => used(port.key, false)) : node.consumers, providers: detail === 'compact' ? [] : detail === 'linked' ? node.providers.filter(port => used(port.key, true)) : node.providers };
    });
    const base = graphDocumentLayout({ ...scope, nodes: ports });
    const bounds = (layout: BlueprintLayout) => {
      const items = [...Object.values(layout.cards), ...layout.frames];
      const x = Math.min(0, ...items.map(item => item.x)), y = Math.min(0, ...items.map(item => item.y));
      return { x, y, width: Math.max(CardWidth, ...items.map(item => item.x + item.width)) - x, height: Math.max(FrameTop, ...items.map(item => item.y + item.height)) - y };
    };
    const placed: Array<{ x: number; y: number; width: number; height: number }> = [];
    const scene: BlueprintLayout = { key: scope.id, cards: Object.create(null), frames: [], links: [], width: 1, height: 1 };
    const groups = new Map<string, string[]>();
    for (const node of [...scope.nodes].sort((a, b) => a.position.y - b.position.y || a.position.x - b.position.x || a.id.localeCompare(b.id))) {
      const card = base.cards[node.id], child = children.get(node.id), box = child ? bounds(child) : null;
      const block = { x: card.x, y: card.y, width: box ? Math.max(card.width, box.width + FrameSide * 2) : card.width, height: box ? card.height + FrameTop + box.height + FrameSide : card.height };
      // Opening ownership frames must not place their contents over sibling nodes.
      if (children.size) for (let attempt = 0; attempt < placed.length; attempt++) {
        const collisions = placed.filter(other => block.x < other.x + other.width + LayerGap && block.x + block.width + LayerGap > other.x && block.y < other.y + other.height && block.y + block.height > other.y);
        if (!collisions.length) break;
        block.x = Math.max(...collisions.map(other => other.x + other.width + LayerGap));
      }
      placed.push(block);
      const key = graphNodeKey(scope.id, node.id), group = node.group || scope.label, groupKey = `group:${JSON.stringify([scope.id, group])}`;
      const subject = nodes.get(key)!; subject.offset = { x: block.x - node.position.x, y: block.y - node.position.y };
      scene.cards[key] = { ...card, id: key, x: block.x, y: block.y, folded: node.consumers.length + node.providers.length - card.pins.length };
      for (const pin of scene.cards[key].pins) if (exposed.has(JSON.stringify([scope.id, node.id, pin.provides ? 'providers' : 'consumers', pin.key]))) pin.wiring = 'linked';
      groups.set(group, [...(groups.get(group) ?? []), key]);
      if (!child || !box) continue;
      const dx = block.x + FrameSide - box.x, dy = block.y + card.height + FrameTop - box.y;
      for (const item of Object.values(child.cards)) { scene.cards[item.id] = { ...item, x: item.x + dx, y: item.y + dy }; const subject = nodes.get(item.id)!; subject.offset = { x: subject.offset!.x + dx, y: subject.offset!.y + dy }; }
      scene.links.push(...child.links.map(link => ({ ...link, points: link.points.map(point => [point[0] + dx, point[1] + dy] as [number, number]) })));
      const frameKey = `scope:${node.graph!.id}`;
      const defaultGroup = `group:${JSON.stringify([node.graph!.id, node.graph!.label])}`, plain = child.frames.find(frame => frame.key === defaultGroup);
      scene.frames.push({ key: frameKey, label: node.label, x: block.x - FrameSide, y: block.y - FrameTop, width: block.width + FrameSide * 2, height: block.height + FrameTop + FrameSide, nodes: [key, ...(plain?.nodes ?? [])], detail: [], parent: groupKey, owner: key, loose: false, muted: false });
      scene.frames.push(...child.frames.filter(frame => frame.key !== defaultGroup).map(frame => ({ ...frame, x: frame.x + dx, y: frame.y + dy, parent: !frame.parent || frame.parent === defaultGroup ? frameKey : frame.parent })));
      for (const binding of node.boundary ?? []) {
        const inner = scene.cards[graphNodeKey(node.graph!.id, binding.node)], outer = scene.cards[key], provides = binding.direction === 'providers';
        if (!inner) continue;
        const innerPin = inner.pins.find(pin => pin.key === binding.port && pin.provides === provides), outerPin = outer.pins.find(pin => pin.key === binding.key && pin.provides === provides);
        if (innerPin) innerPin.wiring = 'linked'; if (outerPin) outerPin.wiring = 'linked';
        const a: [number, number] = [inner.x + (provides ? inner.width : 0), inner.y + (innerPin?.y ?? inner.height / 2)], b: [number, number] = [outer.x + (provides ? outer.width : 0), outer.y + (outerPin?.y ?? outer.height / 2)];
        scene.links.push({ id: `boundary:${JSON.stringify([scope.id, node.id, binding.direction, binding.key])}`, from: provides ? inner.id : outer.id, to: provides ? outer.id : inner.id, fromPort: provides ? binding.port : binding.key, toPort: provides ? binding.key : binding.port, kind: 'wire', family: 'boundary', members: [key], shape: 'curve', points: provides ? [a, b] : [b, a], cross: false, reversed: false });
      }
    }
    for (const [label, members] of groups) {
      const key = `group:${JSON.stringify([scope.id, label])}`;
      const items = [...members.map(id => scene.cards[id]), ...scene.frames.filter(frame => frame.parent === key)];
      const x = Math.min(...items.map(item => item.x)) - FrameSide, y = Math.min(...items.map(item => item.y)) - FrameTop;
      scene.frames.push({ key, label, x, y, width: Math.max(...items.map(item => item.x + item.width)) - x + FrameSide, height: Math.max(...items.map(item => item.y + item.height)) - y + FrameSide, nodes: members.filter(id => !children.has(nodes.get(id)!.node.id)), detail: [], muted: false, loose: false });
    }
    scene.links.push(...base.links.map(link => {
      const from = scene.cards[graphNodeKey(scope.id, link.from)], to = scene.cards[graphNodeKey(scope.id, link.to)];
      const oldFrom = base.cards[link.from], oldTo = base.cards[link.to];
      const points = link.points.map((point, index) => { const card = index < link.points.length / 2 ? from : to, old = index < link.points.length / 2 ? oldFrom : oldTo; return [point[0] + card.x - old.x, point[1] + card.y - old.y] as [number, number]; });
      return { ...link, id: `edge:${JSON.stringify([scope.id, link.id])}`, from: from.id, to: to.id, members: [`edge:${JSON.stringify([scope.id, link.id])}`], points };
    }));
    scene.width = Math.max(1, ...scene.frames.map(frame => frame.x + frame.width)); scene.height = Math.max(1, ...scene.frames.map(frame => frame.y + frame.height));
    return scene;
  };
  return { layout: build(root), nodes, edges };
}
