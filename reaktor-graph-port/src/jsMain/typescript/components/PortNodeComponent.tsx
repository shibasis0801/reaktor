import React from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { PortNodeData, PortData } from '../types';
import { getPortColor } from '../types';

const portHandleStyle = (color: string, index: number, total: number) => ({
    background: color,
    width: 10,
    height: 10,
    border: '2px solid white',
    top: `${((index + 1) / (total + 1)) * 100}%`,
});

interface PortLabelProps {
    port: PortData;
    side: 'left' | 'right';
}

function PortLabel({ port, side }: PortLabelProps) {
    const style: React.CSSProperties = {
        position: 'absolute',
        [side]: side === 'left' ? 15 : 15,
        fontSize: 9,
        color: '#666',
        whiteSpace: 'nowrap',
        pointerEvents: 'none',
    };

    return (
        <span style={style}>
            {port.key || port.type}
        </span>
    );
}

export default function PortNodeComponent({ id, data, selected }: NodeProps) {
    const nodeData = data as PortNodeData;
    const { label, consumerPorts = [], providerPorts = [], nodeType } = nodeData;

    return (
        <div
            style={{
                padding: '12px 16px',
                borderRadius: 8,
                background: selected ? '#f0f9ff' : 'white',
                border: `2px solid ${selected ? '#3b82f6' : '#e2e8f0'}`,
                boxShadow: selected
                    ? '0 4px 12px rgba(59, 130, 246, 0.15)'
                    : '0 2px 4px rgba(0,0,0,0.05)',
                minWidth: 140,
                minHeight: 60,
                position: 'relative',
            }}
        >
            {/* Consumer Ports (inputs) on the left */}
            {consumerPorts.map((port, index) => (
                <React.Fragment key={`consumer-${port.id}`}>
                    <Handle
                        type="target"
                        position={Position.Left}
                        id={`consumer-${port.id}`}
                        style={{
                            ...portHandleStyle(
                                getPortColor(port.type, 'consumer'),
                                index,
                                consumerPorts.length
                            ),
                            opacity: port.isConnected ? 1 : 0.6,
                        }}
                    />
                </React.Fragment>
            ))}

            {/* Provider Ports (outputs) on the right */}
            {providerPorts.map((port, index) => (
                <React.Fragment key={`provider-${port.id}`}>
                    <Handle
                        type="source"
                        position={Position.Right}
                        id={`provider-${port.id}`}
                        style={{
                            ...portHandleStyle(
                                getPortColor(port.type, 'provider'),
                                index,
                                providerPorts.length
                            ),
                            opacity: port.isConnected ? 1 : 0.6,
                        }}
                    />
                </React.Fragment>
            ))}

            {/* Node Header */}
            {nodeType && (
                <div style={{
                    fontSize: 9,
                    color: '#94a3b8',
                    textTransform: 'uppercase',
                    letterSpacing: '0.5px',
                    marginBottom: 4,
                }}>
                    {nodeType}
                </div>
            )}

            {/* Node Label */}
            <div style={{
                fontWeight: 600,
                fontSize: 13,
                color: '#1e293b',
            }}>
                {label}
            </div>

            {/* Port Info */}
            {(consumerPorts.length > 0 || providerPorts.length > 0) && (
                <div style={{
                    marginTop: 8,
                    display: 'flex',
                    gap: 12,
                    fontSize: 10,
                    color: '#64748b',
                }}>
                    {consumerPorts.length > 0 && (
                        <span>⬅ {consumerPorts.length} in</span>
                    )}
                    {providerPorts.length > 0 && (
                        <span>{providerPorts.length} out ➡</span>
                    )}
                </div>
            )}
        </div>
    );
}
