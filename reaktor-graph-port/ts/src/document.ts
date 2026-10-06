/** Authored counterpart of Graph.toJsonElement; positions belong to presentation. */
export interface GraphPort { key: string; type: string }
export interface GraphNode {
  id: string; label: string; type: string; description?: string; group?: string; pattern?: string;
  position: { x: number; y: number }; providers: GraphPort[]; consumers: GraphPort[];
}
export interface GraphEdge {
  id: string; type: 'port' | 'navigation'; sourceNode: string; targetNode: string;
  sourcePort?: string; targetPort?: string; portType?: string;
}
export interface GraphDocument { schema: 1; id: string; label: string; nodes: GraphNode[]; edges: GraphEdge[] }
const row = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const id = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(value) && !value.startsWith('__blueprint_');
const text = (value: unknown, max = 200): value is string => typeof value === 'string' && !!value.trim() && value.length <= max;
const fields = (value: Record<string, unknown>, names: string[]) => Object.keys(value).every(key => names.includes(key));

export function graphProblem(value: unknown): string | null {
  if (!row(value) || value.schema !== 1 || !id(value.id) || !text(value.label) || !Array.isArray(value.nodes) || !Array.isArray(value.edges)) return 'Send a Reaktor graph with an id, name, nodes and edges.';
  if (value.children !== undefined) return 'Open each child graph as its own graph note.';
  if (!fields(value, ['schema', 'id', 'label', 'nodes', 'edges'])) return 'This graph contains unsupported fields.';
  if (value.nodes.length > 250 || value.edges.length > 500) return 'A graph supports up to 250 nodes and 500 connections.';
  const nodes = new Map<string, { type: string; providers: GraphPort[]; consumers: GraphPort[] }>(), edges = new Set<string>(), consumers = new Set<string>(), routes = new Set<string>();
  for (const node of value.nodes) {
    if (!row(node) || !id(node.id) || nodes.has(node.id) || !text(node.label) || !text(node.type) || !row(node.position) || ![node.position.x, node.position.y].every(n => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 100000)) return 'Nodes need unique ids, names, types and finite positions.';
    if (!fields(node, ['id', 'label', 'type', 'description', 'group', 'pattern', 'position', 'providers', 'consumers']) || !fields(node.position, ['x', 'y'])) return 'This node contains unsupported fields.';
    if ((node.description !== undefined && (typeof node.description !== 'string' || node.description.length > 4000)) || (node.group !== undefined && (typeof node.group !== 'string' || node.group.length > 100)) || (node.pattern !== undefined && (typeof node.pattern !== 'string' || node.pattern.length > 200))) return 'Keep node descriptions, groups and routes within their limits.';
    for (const side of ['providers', 'consumers']) {
      const ports = node[side];
      if (!Array.isArray(ports) || ports.length > 32) return 'Nodes support up to 32 ports on each side.';
      const keys = new Set<string>();
      for (const port of ports) {
        if (!row(port) || !fields(port, ['key', 'type']) || !text(port.key, 100) || !text(port.type, 100) || keys.has(port.key)) return 'Ports need a contract type and a unique key on each side.';
        keys.add(port.key);
      }
    }
    nodes.set(node.id, { type: node.type, providers: node.providers as GraphPort[], consumers: node.consumers as GraphPort[] });
  }
  for (const edge of value.edges) {
    if (!row(edge) || !id(edge.id) || edges.has(edge.id) || !id(edge.sourceNode) || !id(edge.targetNode) || !nodes.has(edge.sourceNode) || !nodes.has(edge.targetNode)) return 'Connections need unique ids and endpoints inside this graph.';
    if (!fields(edge, ['id', 'type', 'sourceNode', 'targetNode', 'sourcePort', 'targetPort', 'portType'])) return 'This connection contains unsupported fields.';
    if (['sourcePort', 'targetPort', 'portType'].some(key => edge[key] !== undefined && !text(edge[key], 100))) return 'Connection ports and contracts must be text.';
    edges.add(edge.id);
    const source = nodes.get(edge.sourceNode)!, target = nodes.get(edge.targetNode)!;
    if (edge.type === 'navigation') {
      if (source.type !== 'RouteNode' || target.type !== 'RouteNode') return 'Navigation connects two route nodes.';
      const key = JSON.stringify([edge.sourceNode, edge.targetNode]);
      if (routes.has(key)) return 'These routes are already connected.';
      routes.add(key);
    } else if (edge.type === 'port') {
      // Reaktor serializes consumer → provider; the canvas draws data flowing back.
      const consumer = source.consumers.find(port => port.key === edge.sourcePort), provider = target.providers.find(port => port.key === edge.targetPort);
      if (!consumer || !provider || consumer.type !== provider.type || edge.portType !== consumer.type) return 'Connect a provider to a consumer with the same contract type.';
      const key = JSON.stringify([edge.sourceNode, edge.sourcePort]);
      if (consumers.has(key)) return 'A consumer can connect to only one provider.';
      consumers.add(key);
    } else return 'Choose a port or navigation connection.';
  }
  if (new TextEncoder().encode(JSON.stringify(value)).length > 96 * 1024) return 'A graph supports up to 96 KiB of content.';
  return null;
}

export function removeGraphNode(graph: GraphDocument, id: string): GraphDocument {
  return { ...graph, nodes: graph.nodes.filter(node => node.id !== id), edges: graph.edges.filter(edge => edge.sourceNode !== id && edge.targetNode !== id) };
}
