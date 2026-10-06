import type { GraphDocument } from '../document';
import { CardWidth, cardHeight, FrameSide, FrameTop, pinY, pinsOf } from './engine';
import type { BlueprintLayout, Card, Frame, Link } from './types';

export function graphDocumentLayout(graph: GraphDocument): BlueprintLayout {
  const cards: Record<string, Card> = Object.create(null);
  for (const node of graph.nodes) {
    const linked = (key: string, provides: boolean) => graph.edges.some(edge => edge.type === 'port' && (provides ? edge.targetNode === node.id && edge.targetPort === key : edge.sourceNode === node.id && edge.sourcePort === key));
    const inputs = node.consumers.map(port => ({ ...port, wiring: linked(port.key, false) ? 'linked' as const : 'unlinked' as const }));
    const outputs = node.providers.map(port => ({ ...port, wiring: linked(port.key, true) ? 'linked' as const : 'unlinked' as const }));
    const rows = Math.max(inputs.length, outputs.length);
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
    const y = (card: Card, key: string | undefined, provides: boolean) => card.y + (key ? card.pins.find(pin => pin.key === key && pin.provides === provides)?.y ?? pinY(0) : card.height / 2);
    return { id: edge.id, from: from.id, to: to.id, fromPort, toPort, kind: port ? 'wire' : 'route', members: [edge.id], shape: 'curve', points: [[from.x + from.width, y(from, fromPort, true)], [to.x, y(to, toPort, false)]], cross: false, reversed: false };
  });
  return { key: graph.id, cards, frames, links, width: Math.max(1, ...frames.map(frame => frame.x + frame.width)), height: Math.max(1, ...frames.map(frame => frame.y + frame.height)) };
}
