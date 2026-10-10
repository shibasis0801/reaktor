/** Authored graphs are definitions; editing or saving never executes their nodes. */
export interface GraphPort { key: string; type: string }
export interface GraphBoundary { key: string; direction: 'consumers' | 'providers'; node: string; port: string }
export interface GraphNode {
  id: string; label: string; type: string; description?: string; group?: string; pattern?: string;
  position: { x: number; y: number }; providers: GraphPort[]; consumers: GraphPort[];
  graph?: GraphDocument; boundary?: GraphBoundary[];
}
export type GraphEdgeKind = 'port' | 'navigation' | 'event' | 'transition' | 'relation';
export interface GraphEdge {
  id: string; type: GraphEdgeKind; sourceNode: string; targetNode: string;
  sourcePort?: string; targetPort?: string; portType?: string;
  label?: string; description?: string; event?: string; guard?: string;
  trigger?: 'event' | 'always' | 'after' | 'done' | 'error'; delayMs?: number; directed?: boolean;
}
export interface GraphDocument { schema: 1 | 2; id: string; label: string; nodes: GraphNode[]; edges: GraphEdge[] }
const row = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const id = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(value) && !value.startsWith('__blueprint_');
const text = (value: unknown, max = 200): value is string => typeof value === 'string' && !!value.trim() && value.length <= max;
const fields = (value: Record<string, unknown>, names: string[]) => Object.keys(value).every(key => names.includes(key));

export function graphProblem(value: unknown): string | null {
  const scopes = new Set<string>(); let nodeCount = 0, edgeCount = 0;
  const visit = (value: unknown, depth: number): string | null => {
    if (depth > 12) return 'Subgraphs support up to 12 nested levels.';
    if (!row(value) || (value.schema !== 1 && value.schema !== 2) || !id(value.id) || !text(value.label) || !Array.isArray(value.nodes) || !Array.isArray(value.edges)) return 'Send a Reaktor graph with an id, name, nodes and edges.';
    if (!fields(value, ['schema', 'id', 'label', 'nodes', 'edges'])) return 'This graph contains unsupported fields.';
    if (scopes.has(value.id)) return 'Every subgraph needs a unique graph id.';
    scopes.add(value.id); nodeCount += value.nodes.length; edgeCount += value.edges.length;
    if (nodeCount > 250 || edgeCount > 500) return 'A graph supports up to 250 nodes and 500 connections across all subgraphs.';
    const nodes = new Map<string, GraphNode>(), edges = new Set<string>(), consumers = new Set<string>(), routes = new Set<string>();
    for (const node of value.nodes) {
      if (!row(node) || !id(node.id) || nodes.has(node.id) || !text(node.label) || !text(node.type) || !row(node.position) || ![node.position.x, node.position.y].every(n => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 100000)) return 'Nodes need unique ids, names, types and finite positions.';
      if (!fields(node, ['id', 'label', 'type', 'description', 'group', 'pattern', 'position', 'providers', 'consumers', 'graph', 'boundary']) || !fields(node.position, ['x', 'y'])) return 'This node contains unsupported fields.';
      if ((node.description !== undefined && (typeof node.description !== 'string' || node.description.length > 4000)) || (node.group !== undefined && (typeof node.group !== 'string' || node.group.length > 100)) || (node.pattern !== undefined && (typeof node.pattern !== 'string' || node.pattern.length > 200))) return 'Keep node descriptions, groups and routes within their limits.';
      for (const side of ['providers', 'consumers']) {
        const ports = node[side]; if (!Array.isArray(ports) || ports.length > 32) return 'Nodes support up to 32 ports on each side.';
        const keys = new Set<string>();
        for (const port of ports) { if (!row(port) || !fields(port, ['key', 'type']) || !text(port.key, 100) || !text(port.type, 100) || keys.has(port.key)) return 'Ports need a contract type and a unique key on each side.'; keys.add(port.key); }
      }
      if (node.graph !== undefined) {
        if (value.schema !== 2 || node.type !== 'ContainerNode') return 'Only schema-2 container nodes can own subgraphs.';
        const problem = visit(node.graph, depth + 1); if (problem) return problem;
      }
      if (node.boundary !== undefined) {
        if (!node.graph || !Array.isArray(node.boundary) || node.boundary.length > 64) return 'Boundary mappings need a child graph and at most 64 exposed ports.';
        const mapped = new Set<string>(), childConsumers = new Set<string>();
        for (const binding of node.boundary) {
          if (!row(binding) || !fields(binding, ['key', 'direction', 'node', 'port']) || !text(binding.key, 100) || !text(binding.port, 100) || !id(binding.node) || !['consumers', 'providers'].includes(String(binding.direction))) return 'Boundary ports need a direction and an internal endpoint.';
          const direction = binding.direction as 'consumers' | 'providers', child = (node.graph as GraphDocument).nodes.find(item => item.id === binding.node);
          const outside = (node[direction] as GraphPort[]).find(port => port.key === binding.key), inside = child?.[direction].find(port => port.key === binding.port);
          if (!outside || !inside || outside.type !== inside.type || mapped.has(JSON.stringify([direction, binding.key]))) return 'Map each exposed port once to a child port with the same contract type.';
          mapped.add(JSON.stringify([direction, binding.key]));
          if (direction === 'consumers') {
            const key = JSON.stringify([binding.node, binding.port]);
            if (childConsumers.has(key) || (node.graph as GraphDocument).edges.some(edge => edge.type === 'port' && edge.sourceNode === binding.node && edge.sourcePort === binding.port)) return 'A consumer cannot have both an internal and boundary provider.';
            childConsumers.add(key);
          }
        }
      }
      nodes.set(node.id, node as unknown as GraphNode);
    }
    for (const edge of value.edges) {
      if (!row(edge) || !id(edge.id) || edges.has(edge.id) || !id(edge.sourceNode) || !id(edge.targetNode) || !nodes.has(edge.sourceNode) || !nodes.has(edge.targetNode)) return 'Connections need unique ids and endpoints inside this graph.';
      if (!fields(edge, ['id', 'type', 'sourceNode', 'targetNode', 'sourcePort', 'targetPort', 'portType', 'label', 'description', 'event', 'guard', 'trigger', 'delayMs', 'directed'])) return 'This connection contains unsupported fields.';
      if (['sourcePort', 'targetPort', 'portType'].some(key => edge[key] !== undefined && !text(edge[key], 100))) return 'Connection ports and contracts must be text.';
      if (['label', 'event'].some(key => edge[key] !== undefined && (typeof edge[key] !== 'string' || (edge[key] as string).length > 200)) || (edge.description !== undefined && (typeof edge.description !== 'string' || edge.description.length > 4000)) || (edge.guard !== undefined && (typeof edge.guard !== 'string' || edge.guard.length > 1000))) return 'Keep connection details within their limits.';
      if (edge.directed !== undefined && typeof edge.directed !== 'boolean') return 'Choose whether this relation is directed.';
      if (edge.delayMs !== undefined && (!Number.isInteger(edge.delayMs) || Number(edge.delayMs) < 0 || Number(edge.delayMs) > 86400000)) return 'Use a delay between zero and one day.';
      edges.add(edge.id); const source = nodes.get(edge.sourceNode)!, target = nodes.get(edge.targetNode)!;
      if (edge.type === 'navigation') {
        if (source.type !== 'RouteNode' || target.type !== 'RouteNode') return 'Navigation connects two route nodes.';
        const key = JSON.stringify([edge.sourceNode, edge.targetNode]); if (routes.has(key)) return 'These routes are already connected.'; routes.add(key);
      } else if (edge.type === 'port') {
        const consumer = source.consumers.find(port => port.key === edge.sourcePort), provider = target.providers.find(port => port.key === edge.targetPort);
        if (!consumer || !provider || consumer.type !== provider.type || edge.portType !== consumer.type) return 'Connect a provider to a consumer with the same contract type.';
        const key = JSON.stringify([edge.sourceNode, edge.sourcePort]); if (consumers.has(key)) return 'A consumer can connect to only one provider.'; consumers.add(key);
      } else if (value.schema !== 2) return 'Use schema 2 for event, transition and relation connections.';
      else if (edge.type === 'event') { if (!text(edge.event)) return 'An event connection needs an event name.'; }
      else if (edge.type === 'relation') { if (!text(edge.label)) return 'A relation needs a label.'; }
      else if (edge.type === 'transition') {
        if (!['event', 'always', 'after', 'done', 'error'].includes(String(edge.trigger))) return 'Choose an event, always, delayed, done or error transition.';
        if (edge.trigger === 'event' && !text(edge.event)) return 'An event transition needs an event name.';
        if (edge.trigger === 'after' && edge.delayMs === undefined) return 'A delayed transition needs its delay.';
      } else return 'Choose a port, navigation, event, transition or relation connection.';
    }
    return null;
  };
  const problem = visit(value, 0); if (problem) return problem;
  if (new TextEncoder().encode(JSON.stringify(value)).length > 96 * 1024) return 'A graph supports up to 96 KiB of content.';
  return null;
}
export function graphScopes(graph: GraphDocument): GraphDocument[] { return [graph, ...graph.nodes.flatMap(node => node.graph ? graphScopes(node.graph) : [])]; }
export function graphTotals(graph: GraphDocument) { return graphScopes(graph).reduce((count, scope) => ({ nodes: count.nodes + scope.nodes.length, edges: count.edges + scope.edges.length }), { nodes: 0, edges: 0 }); }
export function graphScope(graph: GraphDocument, scopeId: string): GraphDocument | undefined { return graphScopes(graph).find(scope => scope.id === scopeId); }
export function graphPath(graph: GraphDocument, scopeId: string): GraphDocument[] {
  if (graph.id === scopeId) return [graph];
  for (const node of graph.nodes) if (node.graph) { const path = graphPath(node.graph, scopeId); if (path.length) return [graph, ...path]; }
  return [];
}
export function updateGraphScope(graph: GraphDocument, scopeId: string, change: (scope: GraphDocument) => GraphDocument): GraphDocument {
  return graph.id === scopeId ? change(graph) : { ...graph, nodes: graph.nodes.map(node => node.graph ? { ...node, graph: updateGraphScope(node.graph, scopeId, change) } : node) };
}
export function removeGraphNode(graph: GraphDocument, id: string): GraphDocument { return { ...graph, nodes: graph.nodes.filter(node => node.id !== id), edges: graph.edges.filter(edge => edge.sourceNode !== id && edge.targetNode !== id) }; }
const checked = (graph: GraphDocument) => { const problem = graphProblem(graph); if (problem) throw new Error(problem); return graph; };
const ownerOf = (root: GraphDocument, scopeId: string) => graphScopes(root).flatMap(scope => scope.nodes.map(node => ({ scope, node }))).find(item => item.node.graph?.id === scopeId);

export function extractSubgraph(root: GraphDocument, scopeId: string, selected: ReadonlySet<string>, containerId: string, childId: string, label: string): GraphDocument {
  return checked(extractNodes(root, scopeId, selected, containerId, childId, label));
}
function extractNodes(root: GraphDocument, scopeId: string, selected: ReadonlySet<string>, containerId: string, childId: string, label: string): GraphDocument {
  const scope = graphScope(root, scopeId); if (!scope || !selected.size || [...selected].some(id => !scope.nodes.some(node => node.id === id))) throw new Error('Select nodes in the current graph.');
  if (scope.nodes.some(node => node.id === containerId) || graphScope(root, childId)) throw new Error('The subgraph needs fresh identities.');
  const chosen = scope.nodes.filter(node => selected.has(node.id)), left = Math.min(...chosen.map(node => node.position.x)), top = Math.min(...chosen.map(node => node.position.y));
  const providers: GraphPort[] = [], consumers: GraphPort[] = [], boundary: GraphBoundary[] = [];
  const expose = (node: string, port: string, type: string, direction: GraphBoundary['direction']) => {
    const found = boundary.find(binding => binding.node === node && binding.port === port && binding.direction === direction); if (found) return found.key;
    const ports = direction === 'providers' ? providers : consumers, key = `${direction === 'providers' ? 'output' : 'input'}${ports.length + 1}`;
    ports.push({ key, type }); boundary.push({ key, direction, node, port }); return key;
  };
  const inner = scope.edges.filter(edge => selected.has(edge.sourceNode) && selected.has(edge.targetNode));
  const outer = scope.edges.filter(edge => !(selected.has(edge.sourceNode) && selected.has(edge.targetNode))).map(edge => {
    const source = selected.has(edge.sourceNode), target = selected.has(edge.targetNode); if (!source && !target) return edge;
    if (edge.type !== 'port') throw new Error(`Disconnect cross-boundary ${edge.type} edges before extraction. Their semantics cannot be changed into port bindings.`);
    return source ? { ...edge, sourceNode: containerId, sourcePort: expose(edge.sourceNode, edge.sourcePort!, edge.portType!, 'consumers') } : { ...edge, targetNode: containerId, targetPort: expose(edge.targetNode, edge.targetPort!, edge.portType!, 'providers') };
  });
  const owner = ownerOf(root, scopeId);
  const parentBoundary = owner?.node.boundary?.map(binding => {
    if (!selected.has(binding.node)) return binding;
    const node = chosen.find(node => node.id === binding.node)!, port = node[binding.direction].find(port => port.key === binding.port)!;
    return { ...binding, node: containerId, port: expose(binding.node, binding.port, port.type, binding.direction) };
  });
  const child: GraphDocument = { schema: 2, id: childId, label, nodes: chosen.map(node => ({ ...node, position: { x: node.position.x - left + 40, y: node.position.y - top + 64 } })), edges: inner };
  const container: GraphNode = { id: containerId, label, type: 'ContainerNode', position: { x: left, y: top }, providers, consumers, graph: child, boundary };
  let next = updateGraphScope({ ...root, schema: 2 }, scopeId, scope => ({ ...scope, schema: 2, nodes: [...scope.nodes.filter(node => !selected.has(node.id)), container], edges: outer }));
  if (owner && parentBoundary) next = updateGraphScope(next, owner.scope.id, scope => ({ ...scope, nodes: scope.nodes.map(node => node.id === owner.node.id ? { ...node, boundary: parentBoundary } : node) }));
  return next;
}
export function moveIntoSubgraph(root: GraphDocument, scopeId: string, selected: ReadonlySet<string>, destination: string): GraphDocument {
  const scope = graphScope(root, scopeId), target = scope?.nodes.find(node => node.id === destination);
  if (!scope || !target?.graph || selected.has(destination)) throw new Error('Choose a different graph in the same scope.');
  const containerId = crypto.randomUUID(), childId = crypto.randomUUID();
  let next = extractNodes(root, scopeId, selected, containerId, childId, target.label);
  const moved = graphScope(next, scopeId)!.nodes.find(node => node.id === containerId)!;
  const child = moved.graph!, existing = target.graph;
  if (child.nodes.some(node => existing.nodes.some(item => item.id === node.id)) || child.edges.some(edge => existing.edges.some(item => item.id === edge.id))) throw new Error('This graph already contains one of these identities. Duplicate the selection before moving it.');
  const internal: GraphEdge[] = [], internalConsumers = new Set<string>();
  const outer = graphScope(next, scopeId)!.edges.filter(edge => {
    if (edge.type !== 'port') return true;
    const providerMoves = edge.sourceNode === destination && edge.targetNode === containerId;
    const consumerMoves = edge.sourceNode === containerId && edge.targetNode === destination;
    if (!providerMoves && !consumerMoves) return true;
    const consumer = providerMoves ? target.boundary?.find(binding => binding.direction === 'consumers' && binding.key === edge.sourcePort) : moved.boundary?.find(binding => binding.direction === 'consumers' && binding.key === edge.sourcePort);
    const provider = providerMoves ? moved.boundary?.find(binding => binding.direction === 'providers' && binding.key === edge.targetPort) : target.boundary?.find(binding => binding.direction === 'providers' && binding.key === edge.targetPort);
    if (!consumer || !provider) return true;
    internal.push({ ...edge, sourceNode: consumer.node, sourcePort: consumer.port, targetNode: provider.node, targetPort: provider.port });
    if (providerMoves) internalConsumers.add(consumer.key);
    return false;
  });
  if (internal.some(edge => existing.edges.some(item => item.id === edge.id))) throw new Error('This graph already contains one of these connection identities.');
  const needed = new Set<string>();
  for (const edge of outer) {
    if (edge.sourceNode === containerId) needed.add(JSON.stringify(['consumers', edge.sourcePort]));
    if (edge.targetNode === containerId) needed.add(JSON.stringify(['providers', edge.targetPort]));
  }
  for (const binding of ownerOf(next, scopeId)?.node.boundary ?? []) if (binding.node === containerId) needed.add(JSON.stringify([binding.direction, binding.port]));
  const keys = new Map<string, string>();
  const ports = (direction: GraphBoundary['direction']) => {
    const result = target[direction].filter(port => direction !== 'consumers' || !internalConsumers.has(port.key));
    for (const port of moved[direction].filter(port => needed.has(JSON.stringify([direction, port.key])))) {
      let key = port.key, index = 1;
      while (result.some(item => item.key === key)) key = `${port.key}_${index++}`;
      keys.set(JSON.stringify([direction, port.key]), key); result.push({ ...port, key });
    }
    return result;
  };
  const consumers = ports('consumers'), providers = ports('providers');
  const key = (direction: GraphBoundary['direction'], port: string) => keys.get(JSON.stringify([direction, port]))!;
  const shift = existing.nodes.length ? Math.max(...existing.nodes.map(node => node.position.x)) + 40 : 0;
  next = updateGraphScope(next, scopeId, scope => ({ ...scope,
    nodes: scope.nodes.filter(node => node.id !== containerId).map(node => node.id === destination ? { ...node, consumers, providers,
      graph: { ...existing, schema: 2, nodes: [...existing.nodes, ...child.nodes.map(node => ({ ...node, position: { x: node.position.x + shift, y: node.position.y } }))], edges: [...existing.edges, ...child.edges, ...internal] },
      boundary: [...(node.boundary ?? []).filter(binding => binding.direction !== 'consumers' || !internalConsumers.has(binding.key)), ...(moved.boundary ?? []).filter(binding => needed.has(JSON.stringify([binding.direction, binding.key]))).map(binding => ({ ...binding, key: key(binding.direction, binding.key) }))],
    } : node),
    edges: outer.map(edge => ({ ...edge, ...(edge.sourceNode === containerId ? { sourceNode: destination, sourcePort: key('consumers', edge.sourcePort!) } : {}), ...(edge.targetNode === containerId ? { targetNode: destination, targetPort: key('providers', edge.targetPort!) } : {}) })),
  }));
  const owner = ownerOf(next, scopeId);
  if (owner?.node.boundary) next = updateGraphScope(next, owner.scope.id, scope => ({ ...scope, nodes: scope.nodes.map(node => node.id === owner.node.id ? { ...node, boundary: node.boundary!.map(binding => binding.node === containerId ? { ...binding, node: destination, port: key(binding.direction, binding.port) } : binding) } : node) }));
  return checked(next);
}
export function inlineSubgraph(root: GraphDocument, scopeId: string, containerId: string): GraphDocument {
  const scope = graphScope(root, scopeId), container = scope?.nodes.find(node => node.id === containerId), child = container?.graph;
  if (!scope || !container || !child) throw new Error('Select a subgraph to inline.');
  if (child.nodes.some(node => scope.nodes.some(item => item.id !== containerId && item.id === node.id)) || child.edges.some(edge => scope.edges.some(item => item.id === edge.id))) throw new Error('This scope has duplicate identities; duplicate the subgraph before inlining it.');
  const binding = (key: string | undefined, direction: GraphBoundary['direction']) => { const found = container.boundary?.find(item => item.key === key && item.direction === direction); if (!found) throw new Error('Map connected boundary ports before inlining.'); return found; };
  const outer = scope.edges.map(edge => {
    if (edge.sourceNode !== containerId && edge.targetNode !== containerId) return edge;
    if (edge.type !== 'port') throw new Error(`Disconnect ${edge.type} connections from the container before inlining.`);
    const source = edge.sourceNode === containerId ? binding(edge.sourcePort, 'consumers') : null, target = edge.targetNode === containerId ? binding(edge.targetPort, 'providers') : null;
    return { ...edge, ...(source ? { sourceNode: source.node, sourcePort: source.port } : {}), ...(target ? { targetNode: target.node, targetPort: target.port } : {}) };
  });
  const nodes = child.nodes.map(node => ({ ...node, position: { x: node.position.x + container.position.x - 40, y: node.position.y + container.position.y - 64 } }));
  let next = updateGraphScope(root, scopeId, scope => ({ ...scope, nodes: [...scope.nodes.filter(node => node.id !== containerId), ...nodes], edges: [...outer, ...child.edges] }));
  const owner = ownerOf(root, scopeId);
  if (owner?.node.boundary) next = updateGraphScope(next, owner.scope.id, scope => ({ ...scope, nodes: scope.nodes.map(node => node.id === owner.node.id ? { ...node, boundary: node.boundary!.map(item => item.node === containerId ? { ...item, ...binding(item.port, item.direction), key: item.key } : item) } : node) }));
  return checked(next);
}
export function duplicateNodes(scope: GraphDocument, selected: ReadonlySet<string>, newId: () => string): GraphDocument {
  const remap = new Map(scope.nodes.filter(node => selected.has(node.id)).map(node => [node.id, newId()]));
  const clone = (graph: GraphDocument): GraphDocument => {
    const ids = new Map(graph.nodes.map(node => [node.id, newId()]));
    return { ...graph, id: newId(), nodes: graph.nodes.map(node => {
      const child = node.graph ? clone(node.graph) : undefined;
      return { ...node, id: ids.get(node.id)!, ...(child ? { graph: child, boundary: node.boundary?.map(binding => ({ ...binding, node: child.nodes[node.graph!.nodes.findIndex(item => item.id === binding.node)].id })) } : {}) };
    }), edges: graph.edges.map(edge => ({ ...edge, id: newId(), sourceNode: ids.get(edge.sourceNode)!, targetNode: ids.get(edge.targetNode)! })) };
  };
  const copyNode = (node: GraphNode, id: string): GraphNode => {
    if (!node.graph) return { ...node, id, position: { x: node.position.x + 40, y: node.position.y + 40 } };
    const graph = clone(node.graph);
    return { ...node, id, position: { x: node.position.x + 40, y: node.position.y + 40 }, graph, boundary: node.boundary?.map(binding => ({ ...binding, node: graph.nodes[node.graph!.nodes.findIndex(item => item.id === binding.node)].id })) };
  };
  return checked({ ...scope, nodes: [...scope.nodes, ...scope.nodes.filter(node => selected.has(node.id)).map(node => copyNode(node, remap.get(node.id)!))], edges: [...scope.edges, ...scope.edges.filter(edge => selected.has(edge.sourceNode) && selected.has(edge.targetNode)).map(edge => ({ ...edge, id: newId(), sourceNode: remap.get(edge.sourceNode)!, targetNode: remap.get(edge.targetNode)! }))] });
}
