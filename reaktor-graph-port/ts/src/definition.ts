/** Wire projection of commonMain GraphDefinition. Layout and runtime activations are separate. */
export interface RegionDefinition {id: string; label: string; parent?: string | null}
export type PortPolarity = 'Offer' | 'Require' | 'Neutral';
export interface PortDefinition {id: string; profile: string; contract: string; polarity: PortPolarity}
export interface NodeDefinition {id: string; label: string; kind: string; region: string; ports: PortDefinition[]}
export interface Incidence {node: string; port: string; role: string; ordinal?: number}
export interface RelationDefinition {id: string; kind: string; incidences: Incidence[]}
export interface GraphDefinition {id: string; revision: string; regions: RegionDefinition[]; nodes: NodeDefinition[]; relations: RelationDefinition[]}

export function definitionProblems(graph: GraphDefinition): string[] {
  const errors: string[] = [];
  const unique = (values: string[], label: string) => {
    const seen = new Set<string>();
    for (const value of values) {if (!value?.trim()) errors.push(`Blank ${label}`); if (seen.has(value)) errors.push(`Duplicate ${label}: ${value}`); seen.add(value);}
  };
  if (!graph.id?.trim() || !graph.revision?.trim()) errors.push('Graph identity and revision are required');
  unique(graph.regions.map(value => value.id), 'region'); unique(graph.nodes.map(value => value.id), 'node'); unique(graph.relations.map(value => value.id), 'relation');
  const regions = new Map(graph.regions.map(region => [region.id, region]));
  for (const region of graph.regions) {
    if (region.parent != null && !regions.has(region.parent)) errors.push(`Unknown parent: ${region.id}`);
    const visited = new Set<string>(); let at: string | null | undefined = region.id;
    while (at && regions.has(at)) {if (visited.has(at)) {errors.push(`Region cycle: ${region.id}`); break;} visited.add(at); at = regions.get(at)?.parent;}
  }
  const nodes = new Map(graph.nodes.map(node => [node.id, node]));
  for (const node of graph.nodes) {
    if (!regions.has(node.region)) errors.push(`Unknown region: ${node.id}`);
    if (!node.kind?.trim()) errors.push(`Missing node dialect: ${node.id}`);
    unique(node.ports.map(port => port.id), `port on ${node.id}`);
    for (const port of node.ports) if (!port.profile?.trim() || !port.contract?.trim() || !['Offer','Require','Neutral'].includes(port.polarity)) errors.push(`Incomplete port: ${node.id}.${port.id}`);
  }
  for (const relation of graph.relations) {
    if (!relation.kind?.trim() || !relation.incidences.length) errors.push(`Incomplete relation: ${relation.id}`);
    unique(relation.incidences.map(end => `${end.role}:${end.ordinal ?? 0}`), `role ordinal on ${relation.id}`);
    for (const end of relation.incidences) {
      if (!end.role?.trim() || !Number.isSafeInteger(end.ordinal ?? 0) || (end.ordinal ?? 0) < 0) errors.push(`Invalid role: ${relation.id}`);
      if (!nodes.get(end.node)?.ports.some(port => port.id === end.port)) errors.push(`Unknown endpoint: ${relation.id}/${end.node}.${end.port}`);
    }
  }
  return errors;
}
