import React, { useCallback } from 'react';
import {
    ReactFlow,
    Background,
    Controls,
    MiniMap,
    useNodesState,
    useEdgesState,
    addEdge,
    type Connection,
    type Node,
    type Edge,
    MarkerType,
    BackgroundVariant,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import PortNodeComponent from './PortNodeComponent';
import type { PortFlowGraph, PortNodeData } from '../types';

const nodeTypes = {
    portNode: PortNodeComponent,
};

const defaultEdgeOptions = {
    type: 'smoothstep',
    markerEnd: {
        type: MarkerType.ArrowClosed,
        width: 15,
        height: 15,
    },
    style: {
        strokeWidth: 2,
    },
};

interface PortGraphFlowProps {
    graph: PortFlowGraph;
    onConnect?: (sourceNodeId: string, sourcePortId: string, targetNodeId: string, targetPortId: string) => void;
    onNodeClick?: (nodeId: string) => void;
    fitView?: boolean;
    showMiniMap?: boolean;
    showControls?: boolean;
    showBackground?: boolean;
    style?: React.CSSProperties;
}

export default function PortGraphFlow({
    graph,
    onConnect,
    onNodeClick,
    fitView = true,
    showMiniMap = true,
    showControls = true,
    showBackground = true,
    style,
}: PortGraphFlowProps) {
    const [nodes, setNodes, onNodesChange] = useNodesState(graph.nodes as Node[]);
    const [edges, setEdges, onEdgesChange] = useEdgesState(graph.edges as Edge[]);

    const handleConnect = useCallback(
        (connection: Connection) => {
            // Extract port IDs from handle IDs
            const sourcePortId = connection.sourceHandle?.replace('provider-', '') || '';
            const targetPortId = connection.targetHandle?.replace('consumer-', '') || '';

            setEdges((eds) =>
                addEdge(
                    {
                        ...connection,
                        id: `edge-${Date.now()}`,
                        type: 'smoothstep',
                        markerEnd: { type: MarkerType.ArrowClosed },
                        data: {
                            sourcePortId,
                            targetPortId,
                        },
                    },
                    eds
                )
            );

            onConnect?.(
                connection.source || '',
                sourcePortId,
                connection.target || '',
                targetPortId
            );
        },
        [setEdges, onConnect]
    );

    const handleNodeClick = useCallback(
        (_: React.MouseEvent, node: Node) => {
            onNodeClick?.(node.id);
        },
        [onNodeClick]
    );

    // Update nodes when graph prop changes
    React.useEffect(() => {
        setNodes(graph.nodes as Node[]);
        setEdges(graph.edges as Edge[]);
    }, [graph, setNodes, setEdges]);

    return (
        <div style={{ width: '100%', height: '100%', ...style }}>
            <ReactFlow
                nodes={nodes}
                edges={edges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={handleConnect}
                onNodeClick={handleNodeClick}
                nodeTypes={nodeTypes}
                defaultEdgeOptions={defaultEdgeOptions}
                fitView={fitView}
                fitViewOptions={{ padding: 0.2 }}
                attributionPosition="bottom-left"
            >
                {showBackground && <Background variant={BackgroundVariant.Dots} gap={16} size={1} />}
                {showControls && <Controls />}
                {showMiniMap && (
                    <MiniMap
                        nodeStrokeWidth={3}
                        zoomable
                        pannable
                    />
                )}
            </ReactFlow>
        </div>
    );
}
