import type {GraphDefinition, Incidence} from '../definition';
import {definitionProblems} from '../definition';
import {layoutReferences, type ReferenceGraph} from './referenceGraph';
import type {ElkLaidGraph} from './engine';
import type {BlueprintLayout, ElkGraphInput} from './types';

export const definitionNodeKey = (id: string) => `node:${id}`;
export const definitionRelationKey = (id: string) => `relation:${id}`;
export interface DialectPresentation {label: string; symbol: string; ui: string}
export type DefinitionPresentation = Readonly<Record<string, DialectPresentation>>;

export function definitionReferences(graph: GraphDefinition, presentation: DefinitionPresentation = {}): ReferenceGraph {
  const errors = definitionProblems(graph);
  if (errors.length) throw new Error(errors.join('; '));
  const byNode = new Map(graph.nodes.map(node => [node.id, node]));
  const endpoint = (end: Incidence) => byNode.get(end.node)!.ports.find(port => port.id === end.port)!;
  const groups = graph.regions.map(region => ({id:region.id, title:region.label, parent:region.parent}));
  const nodes: ReferenceGraph['nodes'] = graph.nodes.map(node => ({id:definitionNodeKey(node.id), title:node.label, group:node.region,
    glyph:presentation[node.kind]?.symbol,
    subtitle:`${node.kind} · ${presentation[node.kind]?.label ?? node.id}`,
    rows:node.ports.map(port=>`${presentation[port.profile]?.symbol ? `${presentation[port.profile].symbol} ` : ''}${port.id} · ${port.profile}`),
    ports:node.ports.map(port=>({...port,anchor:'',label:port.id})),
  }));
  const ancestors = (region: string) => {const path: string[] = []; let at: string | null | undefined = region; while (at) {path.unshift(at); at = graph.regions.find(value => value.id === at)?.parent;} return path;};
  const edges: ReferenceGraph['edges'] = [];
  for (const relation of graph.relations) {
    const paths = relation.incidences.map(end => ancestors(byNode.get(end.node)!.region));
    const common = paths[0].filter((id, i) => paths.every(path => path[i] === id)).at(-1);
    const region = common ?? '@relations';
    if (!groups.some(group=>group.id===region)) groups.push({id:region,title:'Cross-region relations',parent:null});
    const id = definitionRelationKey(relation.id);
    nodes.push({id, title:relation.id, group:region, relation:relation.kind, glyph:presentation[relation.kind]?.symbol,
      subtitle:`${relation.kind} · ${presentation[relation.kind]?.label ?? 'hyperedge'}`,
      rows:relation.incidences.map(end=>`${end.role}[${end.ordinal??0}] · ${end.node}.${end.port}`),
      ports:relation.incidences.map((end,row)=>({id:`incidence:${row}`,anchor:'',label:`${end.role}[${end.ordinal??0}]`,profile:endpoint(end).profile,contract:endpoint(end).contract,
        polarity:endpoint(end).polarity==='Require'?'Offer':'Require'})),
    });
    relation.incidences.forEach((end, row) => {
      const incoming = endpoint(end).polarity !== 'Require';
      edges.push({id:`${relation.id}:${row}`, from:incoming ? definitionNodeKey(end.node) : id, to:incoming ? id : definitionNodeKey(end.node),
        fromPort:incoming ? end.port : `incidence:${row}`, toPort:incoming ? `incidence:${row}` : end.port, relation:relation.kind});
    });
  }
  return {revision:`${graph.id}:${graph.revision}`,groups,nodes,edges};
}

export async function layoutDefinition(graph: GraphDefinition, layout: (input: ElkGraphInput) => Promise<ElkLaidGraph>): Promise<BlueprintLayout> {
  return layoutReferences(definitionReferences(graph),layout);
}
export type {GraphDefinition} from '../definition';
