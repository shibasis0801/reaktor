import type { PortGraph, PortNode, Unique } from 'reaktor-reaktor-graph-port';
import type { PortFlowNode, PortFlowEdge, PortFlowGraph, PortData } from '../types';

interface LayoutOptions {
    nodeWidth: number;
    nodeHeight: number;
    horizontalGap: number;
    verticalGap: number;
}

const defaultLayoutOptions: LayoutOptions = {
    nodeWidth: 180,
    nodeHeight: 100,
    horizontalGap: 250,
    verticalGap: 150,
};

// Helper to get id from a PortCapability that might be a PortNode (which has Unique)
function getOwnerId(owner: unknown): string {
    if (owner && typeof owner === 'object' && 'id' in owner) {
        return String((owner as Unique).id);
    }
    return 'unknown';
}

/**
 * Convert a Kotlin PortGraph to React Flow format
 */
export function convertPortGraphToFlow<S extends PortGraph<S, N>, N extends PortNode<S>>(
    portGraph: PortGraph<S, N>,
    layoutOptions: Partial<LayoutOptions> = {}
): PortFlowGraph {
    const options = { ...defaultLayoutOptions, ...layoutOptions };
    const nodes: PortFlowNode[] = [];
    const edges: PortFlowEdge[] = [];

    // Get nodes from the port graph
    const kotlinNodes = portGraph.nodes.asJsReadonlyArrayView();

    // Create a map for node positions (simple grid layout)
    const nodeCount = kotlinNodes.length;
    const cols = Math.ceil(Math.sqrt(nodeCount));

    kotlinNodes.forEach((portNode, index) => {
        const row = Math.floor(index / cols);
        const col = index % cols;
        const nodeId = String(portNode.id);

        // Extract consumer ports
        const consumerPorts: PortData[] = [];
        const consumerPortsMap = portNode.consumerPorts.asJsMapView();
        consumerPortsMap.forEach((keyMap, type) => {
            const keyMapJs = keyMap.asJsMapView();
            keyMapJs.forEach((port, key) => {
                consumerPorts.push({
                    id: `${nodeId}-consumer-${type.type}-${key.key}`,
                    key: key.key,
                    type: type.type,
                    isConnected: port.isConnected(),
                });
            });
        });

        // Extract provider ports
        const providerPorts: PortData[] = [];
        const providerPortsMap = portNode.providerPorts.asJsMapView();
        providerPortsMap.forEach((keyMap, type) => {
            const keyMapJs = keyMap.asJsMapView();
            keyMapJs.forEach((port, key) => {
                providerPorts.push({
                    id: `${nodeId}-provider-${type.type}-${key.key}`,
                    key: key.key,
                    type: type.type,
                    isConnected: port.isConnected(),
                });

                // Extract edges from provider ports
                const edgesMap = port.edges.asJsMapView();
                edgesMap.forEach((edge, consumer) => {
                    const targetNodeId = getOwnerId(consumer.owner);
                    edges.push({
                        id: `edge-${edge.id}`,
                        source: nodeId,
                        target: targetNodeId,
                        sourceHandle: `provider-${nodeId}-provider-${type.type}-${key.key}`,
                        targetHandle: `consumer-${targetNodeId}-consumer-${consumer.type.type}-${consumer.key.key}`,
                        type: 'smoothstep',
                        data: {
                            sourcePortId: `${nodeId}-provider-${type.type}-${key.key}`,
                            targetPortId: `${targetNodeId}-consumer-${consumer.type.type}-${consumer.key.key}`,
                            portType: type.type,
                        },
                    });
                });
            });
        });

        nodes.push({
            id: nodeId,
            type: 'portNode',
            position: {
                x: col * options.horizontalGap,
                y: row * options.verticalGap,
            },
            data: {
                label: portNode.label || `Node ${index + 1}`,
                consumerPorts,
                providerPorts,
            },
        });
    });

    return { nodes, edges };
}

/**
 * Simple layout algorithm - arrange nodes in a grid
 */
export function gridLayout(nodes: PortFlowNode[], options: Partial<LayoutOptions> = {}): PortFlowNode[] {
    const opts = { ...defaultLayoutOptions, ...options };
    const cols = Math.ceil(Math.sqrt(nodes.length));

    return nodes.map((node, index) => ({
        ...node,
        position: {
            x: (index % cols) * opts.horizontalGap,
            y: Math.floor(index / cols) * opts.verticalGap,
        },
    }));
}

/**
 * Topological sort layout - arrange nodes by dependency order
 */
export function topologicalLayout(
    nodes: PortFlowNode[],
    edges: PortFlowEdge[],
    options: Partial<LayoutOptions> = {}
): PortFlowNode[] {
    const opts = { ...defaultLayoutOptions, ...options };

    // Build adjacency list
    const inDegree = new Map<string, number>();
    const outEdges = new Map<string, string[]>();

    nodes.forEach(node => {
        inDegree.set(node.id, 0);
        outEdges.set(node.id, []);
    });

    edges.forEach(edge => {
        inDegree.set(edge.target, (inDegree.get(edge.target) || 0) + 1);
        outEdges.get(edge.source)?.push(edge.target);
    });

    // Kahn's algorithm
    const queue: string[] = [];
    const levels = new Map<string, number>();

    inDegree.forEach((degree, nodeId) => {
        if (degree === 0) {
            queue.push(nodeId);
            levels.set(nodeId, 0);
        }
    });

    while (queue.length > 0) {
        const nodeId = queue.shift()!;
        const level = levels.get(nodeId) || 0;

        outEdges.get(nodeId)?.forEach(targetId => {
            const newDegree = (inDegree.get(targetId) || 1) - 1;
            inDegree.set(targetId, newDegree);

            const targetLevel = Math.max(levels.get(targetId) || 0, level + 1);
            levels.set(targetId, targetLevel);

            if (newDegree === 0) {
                queue.push(targetId);
            }
        });
    }

    // Group nodes by level
    const levelGroups = new Map<number, string[]>();
    levels.forEach((level, nodeId) => {
        if (!levelGroups.has(level)) {
            levelGroups.set(level, []);
        }
        levelGroups.get(level)!.push(nodeId);
    });

    // Position nodes
    const nodeMap = new Map(nodes.map(n => [n.id, n]));
    const result: PortFlowNode[] = [];

    levelGroups.forEach((nodeIds, level) => {
        nodeIds.forEach((nodeId, index) => {
            const node = nodeMap.get(nodeId);
            if (node) {
                result.push({
                    ...node,
                    position: {
                        x: level * opts.horizontalGap,
                        y: index * opts.verticalGap,
                    },
                });
            }
        });
    });

    // Add any nodes not in the sorted result (cycles or disconnected)
    nodes.forEach(node => {
        if (!result.find(n => n.id === node.id)) {
            result.push(node);
        }
    });

    return result;
}
