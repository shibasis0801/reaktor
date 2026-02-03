import type { Node, Edge } from '@xyflow/react';

// Port representation for React Flow
export interface PortData {
    id: string;
    key: string;
    type: string;
    isConnected: boolean;
}

// Node data for React Flow nodes - with index signature for React Flow compatibility
export interface PortNodeData {
    label: string;
    consumerPorts: PortData[];
    providerPorts: PortData[];
    nodeType?: string;
    onPortClick?: (nodeId: string, portId: string, portKind: 'consumer' | 'provider') => void;
    [key: string]: unknown;
}

// React Flow node type
export type PortFlowNode = Node<PortNodeData>;

// React Flow edge type (connects provider -> consumer)
export type PortFlowEdge = Edge<{
    sourcePortId: string;
    targetPortId: string;
    portType: string;
}>;

// Graph representation for React Flow
export interface PortFlowGraph {
    nodes: PortFlowNode[];
    edges: PortFlowEdge[];
}

// Color scheme for different port types
export interface PortTypeColors {
    [typeName: string]: {
        provider: string;
        consumer: string;
    };
}

export const defaultPortColors: PortTypeColors = {
    default: {
        provider: '#3b82f6', // blue
        consumer: '#fb923c', // orange
    },
    String: {
        provider: '#22c55e', // green
        consumer: '#86efac', // light green
    },
    Number: {
        provider: '#8b5cf6', // purple
        consumer: '#c4b5fd', // light purple
    },
    Boolean: {
        provider: '#ef4444', // red
        consumer: '#fca5a5', // light red
    },
};

export function getPortColor(typeName: string, kind: 'provider' | 'consumer'): string {
    const colors = defaultPortColors[typeName] || defaultPortColors.default;
    return colors[kind];
}
