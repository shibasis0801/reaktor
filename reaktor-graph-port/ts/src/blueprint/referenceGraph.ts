import {plan, assemble, readFrame, requestFor, type ElkLaidGraph, compactElkResult, nestedNode, balancedLanes, cardHeight, FrameSide, FrameTop} from './engine';
import type {BlueprintGroup, BlueprintEdge, BlueprintLayout, ElkGraphInput, FlatFrame, BlueprintNode} from './types';

export interface ReferencePort {id: string; anchor: string; label: string; profile: string; contract?: string; polarity?: 'Offer' | 'Require' | 'Neutral'}
export interface ReferenceNode {id: string; title: string; group: string; subtitle?: string; rows: string[]; folded?: number; ports?: ReferencePort[]; owner?: string; relation?: string; sourceTitle?: string; glyph?: string}
export interface ReferenceGroup {id: string; title: string; parent?: string | null; owner?: string}
export interface ReferenceEdge {id?: string; from: string; to: string; fromPort?: string; toPort?: string; relation: string}
export interface ReferenceGraph {revision: string; nodes: ReferenceNode[]; groups: ReferenceGroup[]; edges: ReferenceEdge[]}

export async function layoutReferences(graph: ReferenceGraph, layout: (input: ElkGraphInput) => Promise<ElkLaidGraph>): Promise<BlueprintLayout> {
  const groups = new Map(graph.groups.map(group => [group.id, {key: `reference-region:${group.id}`, label: group.title, owner: group.owner, nodes: [] as BlueprintNode[]} as BlueprintGroup]));
  for (const node of graph.nodes) {
    const incoming = new Set(graph.edges.filter(edge => edge.to === node.id).map(edge => edge.toPort));
    const outgoing = new Set(graph.edges.filter(edge => edge.from === node.id).map(edge => edge.fromPort));
    groups.get(node.group)?.nodes.push({id: node.id, lane: 0, rows: node.rows.length, folded: node.folded ?? 0, owner: node.owner,
      inputs: node.ports?.flatMap((port,row) => incoming.has(port.id) || port.polarity === 'Require' ? [{key:port.id,type:port.contract??port.profile,wiring:incoming.has(port.id)?'linked' as const:'unlinked' as const,row}] : []),
      outputs: node.ports?.flatMap((port,row) => outgoing.has(port.id) || port.polarity === 'Offer' || (!port.polarity && !incoming.has(port.id)) ? [{key:port.id,type:port.contract??port.profile,wiring:outgoing.has(port.id)?'linked' as const:'unlinked' as const,row}] : []),
    });
  }
  const attach = (id: string): BlueprintGroup => {
    const group = groups.get(id)!;
    for (const child of graph.groups.filter(value => value.parent === id)) {
      const nested = attach(child.id);
      if (nested.nodes.length) group.nodes.push(nestedNode(nested));
    }
    if (group.owner) {const lanes=balancedLanes(group.nodes.map(node=>cardHeight(node.rows??0)));group.nodes.forEach((node,at)=>node.lane=lanes[at]);group.lanes=true;}
    return group;
  };
  const roots = graph.groups.filter(group => !group.parent).map(group => attach(group.id)).filter(group => group.nodes.length);
  const ids = new Set(graph.nodes.map(node => node.id));
  const edges: BlueprintEdge[] = graph.edges.filter(edge => ids.has(edge.from) && ids.has(edge.to)).map((edge, index) => ({id: edge.id ?? `reference:${index}`, from: edge.from, to: edge.to, fromPort:edge.fromPort, toPort:edge.toPort, kind: edge.fromPort ? 'wire' : 'route', family: edge.relation, curved: true}));
  const blueprint = plan(graph.revision, roots, edges);
  const flats = new Map<string, FlatFrame>();
  for (const [index, stage] of blueprint.stages.entries()) {
    const requests = index === 0 ? blueprint.requests : stage.map(key => requestFor(blueprint, key, flats));
    for (const request of requests) flats.set(request.key, readFrame(blueprint, request, compactElkResult(await layout(request.graph)), flats));
  }
  return assemble(blueprint, flats, 1.8);
}

export function subsetReferences(layout: BlueprintLayout, ids: ReadonlySet<string>): BlueprintLayout {
  const cards = Object.fromEntries(Object.entries(layout.cards).filter(([id]) => ids.has(id)));
  const kept = new Map<string, BlueprintLayout['frames'][number]>();
  const depth = (frame: BlueprintLayout['frames'][number]): number => frame.parent ? 1 + depth(layout.frames.find(value => value.key === frame.parent)!) : 0;
  for (const frame of [...layout.frames].sort((a,b) => depth(b)-depth(a))) {
    const nodes = frame.nodes.filter(id => cards[id]);
    const visible = [...nodes.map(id => cards[id]), ...[...kept.values()].filter(child => child.parent === frame.key)];
    if (!visible.length) continue;
    const x = Math.min(...visible.map(card => card.x)) - FrameSide;
    const y = Math.min(...visible.map(card => card.y)) - FrameTop;
    kept.set(frame.key, {...frame, nodes, x, y,
      width: Math.max(...visible.map(card => card.x + card.width)) + FrameSide - x,
      height: Math.max(...visible.map(card => card.y + card.height)) + FrameSide - y});
  }
  const frames = layout.frames.flatMap(frame => kept.has(frame.key) ? [kept.get(frame.key)!] : []);
  return {...layout, cards, frames, links: layout.links.filter(link => ids.has(link.from) && ids.has(link.to)),
    width: Math.max(0, ...frames.map(frame => frame.x + frame.width)),
    height: Math.max(0, ...frames.map(frame => frame.y + frame.height))};
}

export {AtlasCardRows} from './engine';
