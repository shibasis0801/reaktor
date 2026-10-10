/**
 * Scope-local projection (TS analog of the Kotlin `buildReaktorFlowGraph(graph,
 * style, scopeView)` adapter): computes the visible node set for a
 * `ScopeViewState`, renders collapsed child scopes as boundary summary nodes,
 * and re-routes edges whose endpoints are hidden onto the nearest visible
 * boundary — aggregating parallel re-routed edges with a count, so a collapsed
 * domain shows one weighted edge instead of dozens.
 */

import type { Edge, Node } from '@xyflow/react';
import { MarkerType } from '@xyflow/react';

import type {
    ScopeEdgeStyles,
    ScopeGraphModel,
    ScopeId,
    ScopeViewState,
} from './types';
import { ROOT_SCOPE_ID, ancestorsInclusive, defaultScopeEdgeStyles, isExpanded, parentScopeId } from './types';
import type { ScopeLayoutOptions } from './layout';
import { layoutScopeGraph } from './layout';

export interface ScopeProjectionOptions {
    layout?: Partial<ScopeLayoutOptions>;
    edgeStyles?: ScopeEdgeStyles;
    /** Edge kinds to hide entirely. */
    hiddenEdgeKinds?: ReadonlySet<string>;
    /** Node kinds to hide (their edges re-route like collapsed content). */
    hiddenNodeKinds?: ReadonlySet<string>;
}

/** Selection/dim state applied by [decorateProjection] — cheap, per-interaction. */
export interface ScopeDecoration {
    selectedNodeId?: string | null;
    /** Node under the pointer — its edges light up with labels. */
    hoveredNodeId?: string | null;
    /** When set, nodes not in the set render dimmed. */
    highlightedNodeIds?: ReadonlySet<string> | null;
}

/** Edges rest quiet but legible; attention (hover/select/highlight) brings them forward. */
const REST_EDGE_OPACITY = 0.5;
const FADED_EDGE_OPACITY = 0.08;
/** Floor so hairline strokes stay visible on hidpi displays at any zoom. */
const MIN_EDGE_WIDTH = 1.6;
const ACTIVE_EDGE_OPACITY = 0.95;

export interface ScopeProjection {
    flowNodes: Node[];
    flowEdges: Edge[];
    /** Visible item-node ids after scope + kind filtering. */
    visibleNodeIds: Set<string>;
    /** Visible adjacency (post re-routing) for focus/neighborhood queries. */
    adjacency: Map<string, Set<string>>;
}

const SUMMARY_PREFIX = 'scope-summary:';
const GROUP_PREFIX = 'scope-group:';

/** Handle ids rendered by the canvas's EdgeHandles; bound explicitly on every edge. */
export const SCOPE_SOURCE_HANDLE = 's';
export const SCOPE_TARGET_HANDLE = 't';

export const scopeSummaryNodeId = (scopeId: ScopeId) => `${SUMMARY_PREFIX}${scopeId}`;
export const scopeGroupNodeId = (scopeId: ScopeId) => `${GROUP_PREFIX}${scopeId}`;

/** A scope is open when it and every ancestor is expanded (root is always open). */
function scopeOpen(view: ScopeViewState, scopeId: ScopeId): boolean {
    return ancestorsInclusive(scopeId).every((id) => isExpanded(view, id));
}

export function projectScopeGraph(
    model: ScopeGraphModel,
    view: ScopeViewState,
    options: ScopeProjectionOptions = {},
): ScopeProjection {
    const edgeStyles = { ...defaultScopeEdgeStyles, ...(options.edgeStyles ?? {}) };
    const hiddenEdgeKinds = options.hiddenEdgeKinds ?? new Set<string>();
    const hiddenNodeKinds = options.hiddenNodeKinds ?? new Set<string>();

    const scopeById = new Map(model.scopes.map((s) => [s.id, s]));
    const nodeById = new Map(model.nodes.map((n) => [n.id, n]));

    // Visible model = scopes always participate; nodes drop hidden kinds.
    const visibleModel: ScopeGraphModel = {
        scopes: model.scopes,
        nodes: model.nodes.filter((n) => !hiddenNodeKinds.has(n.kind)),
        edges: model.edges,
    };
    const layout = layoutScopeGraph(visibleModel, view, options.layout);

    // Recursive content counts for summary cards.
    const countInside = new Map<ScopeId, number>();
    for (const node of visibleModel.nodes) {
        for (const ancestor of ancestorsInclusive(node.scopeId)) {
            if (ancestor === ROOT_SCOPE_ID) continue;
            countInside.set(ancestor, (countInside.get(ancestor) ?? 0) + 1);
        }
    }

    /**
     * Resolve a model node to its visible representative: itself when its scope
     * chain is open, otherwise the summary node of its outermost collapsed
     * ancestor scope. Hidden-kind nodes resolve the same way (or vanish at root).
     */
    const resolveVisible = (nodeId: string): string | null => {
        const node = nodeById.get(nodeId);
        if (!node) return null;
        const chain = ancestorsInclusive(node.scopeId);
        for (const scopeId of chain) {
            if (scopeId === ROOT_SCOPE_ID) continue;
            if (!isExpanded(view, scopeId)) return scopeSummaryNodeId(scopeId);
        }
        if (hiddenNodeKinds.has(node.kind)) return null;
        return nodeId;
    };

    const flowNodes: Node[] = [];
    const visibleNodeIds = new Set<string>();

    // Scope nodes: expanded → group container; collapsed (but parent open) → summary card.
    for (const scope of model.scopes) {
        const parent = parentScopeId(scope.id) ?? ROOT_SCOPE_ID;
        if (!scopeOpen(view, parent)) continue;
        const box = layout.scopes.get(scope.id);
        if (!box) continue;
        const parentGroup = parent === ROOT_SCOPE_ID ? undefined : scopeGroupNodeId(parent);

        if (isExpanded(view, scope.id)) {
            flowNodes.push({
                id: scopeGroupNodeId(scope.id),
                type: 'scopeGroup',
                position: { x: box.x, y: box.y },
                data: { scopeId: scope.id, label: scope.label, color: scope.color },
                style: { width: box.width, height: box.height },
                parentId: parentGroup,
                draggable: false,
                selectable: false,
            } as Node);
        } else {
            flowNodes.push({
                id: scopeSummaryNodeId(scope.id),
                type: 'scopeSummary',
                position: { x: box.x, y: box.y },
                data: {
                    scopeId: scope.id,
                    label: scope.label,
                    color: scope.color,
                    count: countInside.get(scope.id) ?? 0,
                },
                style: { width: box.width, height: box.height },
                parentId: parentGroup,
                draggable: false,
            } as Node);
        }
    }

    // Item nodes inside open scopes.
    for (const node of visibleModel.nodes) {
        if (!scopeOpen(view, node.scopeId)) continue;
        const box = layout.nodes.get(node.id);
        if (!box) continue;
        visibleNodeIds.add(node.id);
        const scopeColor = scopeById.get(node.scopeId)?.color;
        flowNodes.push({
            id: node.id,
            type: 'scopeItem',
            position: { x: box.x, y: box.y },
            data: {
                label: node.label,
                sublabel: node.sublabel,
                kind: node.kind,
                color: node.color ?? scopeColor,
                weight: node.weight ?? 1,
                selected: false,
                dimmed: false,
                meta: node.meta,
            },
            style: { width: box.width, height: box.height },
            parentId: node.scopeId === ROOT_SCOPE_ID ? undefined : scopeGroupNodeId(node.scopeId),
            draggable: false,
        } as Node);
    }

    // Edges: map endpoints through the visibility resolver and aggregate duplicates.
    type Bucket = { from: string; to: string; kind: string; label?: string; count: number; ids: string[] };
    const buckets = new Map<string, Bucket>();
    const adjacency = new Map<string, Set<string>>();
    const link = (a: string, b: string) => {
        (adjacency.get(a) ?? adjacency.set(a, new Set()).get(a)!).add(b);
        (adjacency.get(b) ?? adjacency.set(b, new Set()).get(b)!).add(a);
    };

    for (const edge of model.edges) {
        if (hiddenEdgeKinds.has(edge.kind)) continue;
        const from = resolveVisible(edge.from);
        const to = resolveVisible(edge.to);
        if (!from || !to || from === to) continue;
        link(from, to);
        const key = `${from} ${to} ${edge.kind}`;
        const bucket = buckets.get(key);
        if (bucket) {
            bucket.count += 1;
            bucket.ids.push(edge.id);
        } else {
            buckets.set(key, { from, to, kind: edge.kind, label: edge.label, count: 1, ids: [edge.id] });
        }
    }

    const flowEdges: Edge[] = [...buckets.values()].map((bucket) => {
        const style = edgeStyles[bucket.kind] ?? edgeStyles.default;
        const width = Math.max(MIN_EDGE_WIDTH, (style.strokeWidth ?? 1.2) * (bucket.count > 1 ? Math.min(1 + bucket.count / 8, 2.4) : 1));
        return {
            id: `${bucket.kind}:${bucket.ids[0]}${bucket.count > 1 ? `+${bucket.count - 1}` : ''}`,
            source: bucket.from,
            target: bucket.to,
            type: 'default',
            label: bucket.count > 1 ? `×${bucket.count}` : undefined,
            animated: false,
            data: { kind: bucket.kind, hoverLabel: bucket.label, animatedWhenActive: style.animated ?? false, baseWidth: width },
            style: {
                stroke: style.stroke,
                strokeWidth: width,
                strokeDasharray: style.dash,
                opacity: REST_EDGE_OPACITY,
            },
            labelStyle: { fill: style.stroke, fontSize: 10 },
            labelBgStyle: { fill: 'transparent' },
            markerEnd:
                style.arrow === false
                    ? undefined
                    : { type: MarkerType.ArrowClosed, width: 12, height: 12, color: style.stroke },
        } as Edge;
    });

    return { flowNodes, flowEdges, visibleNodeIds, adjacency };
}

/** BFS neighborhood over the projected adjacency (for focus mode). */
export function neighborhood(
    adjacency: Map<string, Set<string>>,
    startId: string,
    depth: number,
): Set<string> {
    const seen = new Set<string>([startId]);
    let frontier = [startId];
    for (let d = 0; d < depth && frontier.length; d++) {
        const next: string[] = [];
        for (const id of frontier) {
            for (const neighbor of adjacency.get(id) ?? []) {
                if (!seen.has(neighbor)) {
                    seen.add(neighbor);
                    next.push(neighbor);
                }
            }
        }
        frontier = next;
    }
    return seen;
}

/**
 * Apply selection + highlight to a structural projection — the cheap,
 * per-interaction half of the pipeline. Layout and edge bucketing (the
 * expensive half) only recompute when the model or view changes.
 */
export function decorateProjection(
    projection: ScopeProjection,
    decoration: ScopeDecoration,
): { flowNodes: Node[]; flowEdges: Edge[] } {
    const highlighted = decoration.highlightedNodeIds ?? null;
    const selectedId = decoration.selectedNodeId ?? null;
    const hoveredId = decoration.hoveredNodeId ?? null;
    if (!highlighted && !selectedId && !hoveredId) {
        return { flowNodes: projection.flowNodes, flowEdges: projection.flowEdges };
    }

    const flowNodes = projection.flowNodes.map((node) => {
        if (node.type !== 'scopeItem') return node;
        const selected = node.id === selectedId;
        const dimmed = highlighted ? !highlighted.has(node.id) : false;
        const data = node.data as { selected?: boolean; dimmed?: boolean };
        if (data.selected === selected && data.dimmed === dimmed) return node;
        return { ...node, data: { ...node.data, selected, dimmed } };
    });

    const attentionIds = new Set([selectedId, hoveredId].filter(Boolean) as string[]);
    const flowEdges = projection.flowEdges.map((edge) => {
        const data = edge.data as { hoverLabel?: string; animatedWhenActive?: boolean; baseWidth?: number };
        const touchesAttention = attentionIds.size > 0 && (attentionIds.has(edge.source) || attentionIds.has(edge.target));
        const inHighlight = highlighted ? highlighted.has(edge.source) && highlighted.has(edge.target) : false;

        if (touchesAttention) {
            return {
                ...edge,
                label: data.hoverLabel || edge.label,
                animated: data.animatedWhenActive ?? false,
                style: { ...edge.style, opacity: ACTIVE_EDGE_OPACITY, strokeWidth: (data.baseWidth ?? 1.2) + 0.8 },
            };
        }
        const opacity = highlighted
            ? (inHighlight ? 0.85 : FADED_EDGE_OPACITY)
            : attentionIds.size > 0
              ? FADED_EDGE_OPACITY
              : REST_EDGE_OPACITY;
        if ((edge.style as { opacity?: number })?.opacity === opacity && edge.animated === false) return edge;
        return { ...edge, animated: false, style: { ...edge.style, opacity } };
    });

    return { flowNodes, flowEdges };
}
