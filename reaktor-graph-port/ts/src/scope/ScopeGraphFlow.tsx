/**
 * ScopeGraphFlow — the web analog of reaktor-flow's `ReaktorGraphCanvas`: a
 * React Flow canvas over a `ScopeGraphModel` + `ScopeViewState`. Collapsed
 * scopes render as boundary summary cards (click to expand inline); expanded
 * scopes render as group containers with a collapse affordance — the C4-style
 * scope-local projection from `reaktor-graph-hierarchical-ui.md`, on the web.
 *
 * Controlled component: pass `view` and react to `onViewChange`; the projection
 * is a pure function of (model, view, filters), so state stays in the caller.
 */

import React, { memo, useMemo, useState } from 'react';
import {
    Background,
    BackgroundVariant,
    Controls,
    Handle,
    MiniMap,
    Position,
    ReactFlow,
    type Node,
    type NodeMouseHandler,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import type { ScopeGraphModel, ScopeId, ScopeViewState } from './types';
import { toggleScope } from './types';
import type { ScopeProjectionOptions } from './projection';
import type { ScopeDecoration, ScopeProjection } from './projection';
import { SCOPE_SOURCE_HANDLE, SCOPE_TARGET_HANDLE, decorateProjection, projectScopeGraph } from './projection';

// ── Node renderers ──────────────────────────────────────────────────

const kindDot: React.CSSProperties = {
    width: 8,
    height: 8,
    borderRadius: 4,
    flex: '0 0 auto',
};

const hiddenHandle: React.CSSProperties = {
    opacity: 0,
    width: 1,
    height: 1,
    minWidth: 1,
    minHeight: 1,
    border: 'none',
    pointerEvents: 'none',
};

/**
 * Invisible connection points so projected edges can attach to any node.
 * Ids match SCOPE_TARGET_HANDLE / SCOPE_SOURCE_HANDLE, which the projection
 * sets explicitly on every edge — explicit handle binding avoids React Flow's
 * default-handle lookup (which drops edges on late-mounted nodes).
 */
function EdgeHandles() {
    return (
        <>
            <Handle id={SCOPE_TARGET_HANDLE} type="target" position={Position.Top} style={hiddenHandle} isConnectable={false} />
            <Handle id={SCOPE_SOURCE_HANDLE} type="source" position={Position.Bottom} style={hiddenHandle} isConnectable={false} />
        </>
    );
}

function ScopeItemNode({ data }: { data: Record<string, unknown> }) {
    const color = (data.color as string) ?? '#7CA7FF';
    const dimmed = Boolean(data.dimmed);
    const selected = Boolean(data.selected);
    return (
        <div
            style={{
                width: '100%',
                height: '100%',
                boxSizing: 'border-box',
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '6px 10px',
                borderRadius: 10,
                background: selected ? 'rgba(124,167,255,0.16)' : 'rgba(20,22,30,0.92)',
                border: `1px solid ${selected ? '#7CA7FF' : 'rgba(255,255,255,0.13)'}`,
                boxShadow: selected ? '0 0 0 2px rgba(124,167,255,0.35)' : '0 1px 6px rgba(0,0,0,0.4)',
                color: '#E7EAF2',
                opacity: dimmed ? 0.18 : 1,
                transition: 'opacity 120ms ease, box-shadow 120ms ease',
                overflow: 'hidden',
            }}
        >
            <EdgeHandles />
            <span style={{ ...kindDot, background: color }} />
            <span style={{ minWidth: 0 }}>
                <span
                    style={{
                        display: 'block',
                        fontSize: 11.5,
                        fontWeight: 600,
                        lineHeight: 1.25,
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                    }}
                >
                    {String(data.label ?? '')}
                </span>
                {data.sublabel ? (
                    <span
                        style={{
                            display: 'block',
                            fontSize: 9.5,
                            color: '#9AA3B5',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                        }}
                    >
                        {String(data.sublabel)}
                    </span>
                ) : null}
            </span>
        </div>
    );
}

function ScopeSummaryNode({ data }: { data: Record<string, unknown> }) {
    const color = (data.color as string) ?? '#7CA7FF';
    return (
        <div
            style={{
                width: '100%',
                height: '100%',
                boxSizing: 'border-box',
                display: 'flex',
                flexDirection: 'column',
                justifyContent: 'center',
                gap: 4,
                padding: '10px 14px',
                borderRadius: 14,
                background: `linear-gradient(160deg, ${color}26, rgba(16,18,26,0.94))`,
                border: `1.5px solid ${color}`,
                color: '#E7EAF2',
                cursor: 'pointer',
                boxShadow: '0 2px 10px rgba(0,0,0,0.45)',
            }}
            title="Expand"
        >
            <EdgeHandles />
            <span style={{ fontSize: 13, fontWeight: 700, letterSpacing: 0.2 }}>{String(data.label ?? '')}</span>
            <span style={{ fontSize: 10.5, color: '#B9C1D2' }}>
                {String(data.count ?? 0)} nodes · click to expand
            </span>
        </div>
    );
}

function ScopeGroupNode({ data }: { data: Record<string, unknown> }) {
    const color = (data.color as string) ?? '#7CA7FF';
    return (
        <div
            style={{
                width: '100%',
                height: '100%',
                boxSizing: 'border-box',
                borderRadius: 18,
                border: `1.5px dashed ${color}88`,
                background: `${color}0d`,
            }}
        >
            <div
                data-scope-collapse="true"
                style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    right: 0,
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                    padding: '7px 14px',
                    cursor: 'pointer',
                    pointerEvents: 'all',
                }}
                title="Collapse"
            >
                <span style={{ ...kindDot, background: color }} />
                <span style={{ fontSize: 12.5, fontWeight: 700, color, letterSpacing: 0.4 }}>
                    {String(data.label ?? '')}
                </span>
                <span style={{ fontSize: 10, color: `${color}bb` }}>⊖</span>
            </div>
        </div>
    );
}

// Memoized: with hundreds of nodes, unmemoized custom components dominate
// interaction cost (React re-renders every card on each canvas update).
const nodeTypes = {
    scopeItem: memo(ScopeItemNode),
    scopeSummary: memo(ScopeSummaryNode),
    scopeGroup: memo(ScopeGroupNode),
};

// ── Component ───────────────────────────────────────────────────────

/**
 * Structural projection as a hook — memoized on (model, view, filters) only.
 * Callers that need the adjacency (focus/neighborhood queries) use this once
 * and pass the result to [ScopeGraphFlow] via `projection`, so the expensive
 * layout never runs twice and never reruns on selection changes.
 */
export function useScopeProjection(
    model: ScopeGraphModel,
    view: ScopeViewState,
    options: ScopeProjectionOptions = {},
): ScopeProjection {
    return useMemo(
        () => projectScopeGraph(model, view, options),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [model, view, options.layout, options.edgeStyles, options.hiddenEdgeKinds, options.hiddenNodeKinds],
    );
}

export interface ScopeGraphFlowProps extends ScopeProjectionOptions, ScopeDecoration {
    model: ScopeGraphModel;
    view: ScopeViewState;
    /** Precomputed structural projection (from [useScopeProjection]) to share. */
    projection?: ScopeProjection;
    onViewChange?: (view: ScopeViewState) => void;
    onSelectNode?: (nodeId: string | null) => void;
    showMiniMap?: boolean;
    showControls?: boolean;
    style?: React.CSSProperties;
    /** Bump to re-fit the viewport (e.g. after expand-all). */
    fitViewKey?: string | number;
    /** Rendered inside the ReactFlow provider (camera rigs, overlays). */
    children?: React.ReactNode;
}

export default function ScopeGraphFlow({
    model,
    view,
    projection,
    onViewChange,
    onSelectNode,
    showMiniMap = true,
    showControls = true,
    style,
    fitViewKey,
    children,
    selectedNodeId,
    highlightedNodeIds,
    ...options
}: ScopeGraphFlowProps) {
    const structural = useScopeProjection(model, view, projection ? {} : options);
    const projected = projection ?? structural;
    const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);

    // Selection/dim are O(n) map passes over stable structures — interactions
    // never pay for layout or edge bucketing.
    const decorated = useMemo(
        () => decorateProjection(projected, { selectedNodeId, highlightedNodeIds, hoveredNodeId }),
        [projected, selectedNodeId, highlightedNodeIds, hoveredNodeId],
    );

    const handleNodeClick: NodeMouseHandler = (event, node: Node) => {
        if (node.type === 'scopeSummary' || node.type === 'scopeGroup') {
            const scopeId = (node.data as { scopeId?: ScopeId }).scopeId;
            if (scopeId && onViewChange) {
                // Group containers only toggle from their header strip.
                if (node.type === 'scopeGroup') {
                    const target = event.target as HTMLElement;
                    if (!target.closest('[data-scope-collapse]')) return;
                }
                onViewChange(toggleScope(view, scopeId));
            }
            return;
        }
        onSelectNode?.(node.id);
    };

    return (
        <div style={{ width: '100%', height: '100%', ...style }}>
            <ReactFlow
                key={fitViewKey}
                nodes={decorated.flowNodes}
                edges={decorated.flowEdges}
                nodeTypes={nodeTypes}
                onNodeClick={handleNodeClick}
                onNodeMouseEnter={(_, node) => { if (node.type === 'scopeItem') setHoveredNodeId(node.id); }}
                onNodeMouseLeave={() => setHoveredNodeId(null)}
                onPaneClick={() => onSelectNode?.(null)}
                fitView
                fitViewOptions={{ padding: 0.12, maxZoom: 1.1 }}
                minZoom={0.04}
                proOptions={{ hideAttribution: true }}
                nodesConnectable={false}
                colorMode="dark"
                // Perf: cull offscreen elements (the big lever at 300+ nodes /
                // 1k+ edges) and drop focus/keyboard machinery this canvas
                // doesn't use — fewer DOM nodes, cheaper interaction frames.
                onlyRenderVisibleElements
                // Trackpad-native: two-finger scroll pans the canvas, pinch zooms
                // (scroll-to-zoom stays available via pinch/cmd-scroll).
                panOnScroll
                zoomOnScroll={false}
                zoomOnPinch
                elevateNodesOnSelect={false}
                nodesFocusable={false}
                edgesFocusable={false}
                disableKeyboardA11y
            >
                <Background variant={BackgroundVariant.Dots} gap={26} size={1.1} color="#232635" />
                {showControls && <Controls showInteractive={false} />}
                {children}
                {showMiniMap && (
                    <MiniMap
                        pannable
                        zoomable
                        nodeColor={(node) => ((node.data as { color?: string })?.color as string) ?? '#3a4160'}
                        maskColor="rgba(8,8,12,0.72)"
                        style={{ background: '#101218' }}
                    />
                )}
            </ReactFlow>
        </div>
    );
}
