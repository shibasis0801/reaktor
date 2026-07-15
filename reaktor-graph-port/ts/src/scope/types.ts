/**
 * Scoped (hierarchical) graph model for web — the TypeScript analog of
 * `reaktor-flow`'s `ReaktorFlowScopeView` + `buildReaktorFlowGraph` (see
 * ReaktorFlowScopeView.kt). Reaktor graphs are hierarchical; flattening every
 * level onto one canvas is the "flattened universe" problem. This module lets a
 * renderer project one scope plus its immediate children, where each collapsed
 * child scope renders as a **boundary summary node** and can be expanded inline.
 *
 * ## Scope ids
 * Scopes are identified by stable, path-encoded ids below the root scope
 * (`"root"`): `"root/m"`, `"root/m/spivak"`, ... Segment content is up to the
 * caller (indexes for reaktor graphs, semantic keys for domain models); the
 * view-state semantics only rely on the `/` path encoding — identical to the
 * Kotlin side's `ReaktorFlowScopeView.childScopeId`.
 */

export type ScopeId = string;

export const ROOT_SCOPE_ID: ScopeId = 'root';

/** Stable scope id for a child segment of `parentScopeId` (Kotlin: childScopeId). */
export function childScopeId(parentScopeId: ScopeId, segment: string | number): ScopeId {
    return `${parentScopeId}/${segment}`;
}

/** Depth (number of `/`-separated segments below root) of a scope id; root is 0. */
export function depthOf(scopeId: ScopeId): number {
    return scopeId.split('/').length - 1;
}

export function parentScopeId(scopeId: ScopeId): ScopeId | null {
    const i = scopeId.lastIndexOf('/');
    return i < 0 ? null : scopeId.slice(0, i);
}

export function ancestorsInclusive(scopeId: ScopeId): ScopeId[] {
    const out: ScopeId[] = [];
    let acc: string | null = null;
    for (const segment of scopeId.split('/')) {
        acc = acc === null ? segment : `${acc}/${segment}`;
        out.push(acc);
    }
    return out;
}

/**
 * Hierarchical view state — a faithful port of `ReaktorFlowScopeView`:
 * - the root scope's direct nodes are always laid out,
 * - each immediate child scope renders as a collapsed boundary summary,
 * - a child scope is expanded inline only when its id is in `expandedScopeIds`,
 * - expanding also expands ancestors; collapsing removes all descendants.
 */
export interface ScopeViewState {
    expandedScopeIds: ReadonlySet<ScopeId>;
}

export const emptyScopeView: ScopeViewState = { expandedScopeIds: new Set() };

export function isExpanded(view: ScopeViewState, scopeId: ScopeId): boolean {
    return scopeId === ROOT_SCOPE_ID || view.expandedScopeIds.has(scopeId);
}

/** Expand `scopeId` (and its ancestors, so the path to it is visible). */
export function expandScope(view: ScopeViewState, scopeId: ScopeId): ScopeViewState {
    const next = new Set(view.expandedScopeIds);
    for (const ancestor of ancestorsInclusive(scopeId)) {
        if (ancestor !== ROOT_SCOPE_ID) next.add(ancestor);
    }
    return { expandedScopeIds: next };
}

/** Collapse `scopeId` and everything nested beneath it. */
export function collapseScope(view: ScopeViewState, scopeId: ScopeId): ScopeViewState {
    const prefix = `${scopeId}/`;
    const next = new Set(
        [...view.expandedScopeIds].filter((id) => id !== scopeId && !id.startsWith(prefix)),
    );
    return { expandedScopeIds: next };
}

export function toggleScope(view: ScopeViewState, scopeId: ScopeId): ScopeViewState {
    return isExpanded(view, scopeId) && scopeId !== ROOT_SCOPE_ID
        ? collapseScope(view, scopeId)
        : expandScope(view, scopeId);
}

// ── R4 levels (C4 semantic zoom, reaktor-native) ────────────────────
//
// The level of a scope is its depth + 1: root children render at L2, their
// children at L3, and so on — mirroring C4's Context → Containers →
// Components → Code, with labels supplied per graph family (code graphs:
// App/Graphs/Nodes/Code; knowledge graphs: Workspace/Domains/Resources/
// Chapters). Levels are a lens over ScopeViewState, not new state.

export interface LevelSpec {
    levels: Array<{ n: number; label: string; unit: string }>;
}

/**
 * C4-poster mode: every scope of depth ≤ `depth` expanded, so their contents
 * (items + depth+1 boundaries as collapsed summaries) are visible.
 * `expandToDepth(0)` = top-level boundary map; `expandToDepth(1)` = contents
 * of the top-level scopes with second-level boundaries collapsed; etc.
 */
export function expandToDepth(model: { scopes: ScopeDef[] }, depth: number): ScopeViewState {
    const next = new Set<ScopeId>();
    for (const scope of model.scopes) {
        if (depthOf(scope.id) <= depth) next.add(scope.id);
    }
    return { expandedScopeIds: next };
}

/** R4 level a scope's contents render at (root contents = L2's parent view, etc.). */
export function levelOf(scopeId: ScopeId): number {
    return depthOf(scopeId) + 1;
}

/** Ancestor path of the deepest expanded scope — the "zoom in" breadcrumb. */
export function breadcrumbOf(view: ScopeViewState): ScopeId[] {
    let deepest: ScopeId | null = null;
    for (const id of view.expandedScopeIds) {
        if (deepest === null || depthOf(id) > depthOf(deepest)) deepest = id;
    }
    return deepest ? ancestorsInclusive(deepest) : [];
}

/** Deepest scope depth present in the model — reaktor graphs nest arbitrarily. */
export function maxDepthOf(model: { scopes: ScopeDef[] }): number {
    return model.scopes.reduce((max, scope) => Math.max(max, depthOf(scope.id)), 0);
}

/** The uniform level currently shown, or null when the view is a mixed focal drill. */
export function uniformLevelOf(model: { scopes: ScopeDef[] }, view: ScopeViewState): number | null {
    const deepest = maxDepthOf(model);
    for (let depth = 0; depth <= deepest; depth++) {
        const expected = expandToDepth(model, depth);
        if (
            expected.expandedScopeIds.size === view.expandedScopeIds.size &&
            [...expected.expandedScopeIds].every((id) => view.expandedScopeIds.has(id))
        ) {
            return depth + 1;
        }
    }
    return null;
}

/**
 * Level labels for a graph family. R4 names the first four altitudes (Context/
 * Containers/Components/Code analogs); deeper levels — reaktor graphs nest
 * without bound — fall back to `Level n` unless the spec names them.
 */
export function levelLabels(spec: LevelSpec | null, model: { scopes: ScopeDef[] }): Array<{ n: number; label: string }> {
    const count = maxDepthOf(model) + 1;
    return Array.from({ length: count }, (_, i) => {
        const named = spec?.levels.find((l) => l.n === i + 1);
        return { n: i + 1, label: named?.label ?? `Level ${i + 1}` };
    });
}

// ── Model ───────────────────────────────────────────────────────────

/** A collapsible boundary (Kotlin: a nested Graph / ContainerNode child graph). */
export interface ScopeDef {
    /** Path-encoded id below `root`, e.g. `root/m` or `root/m/spivak`. */
    id: ScopeId;
    label: string;
    color?: string;
    /** Optional anchor (model coordinates) for root-level scope placement. */
    anchor?: { x: number; y: number };
    meta?: Record<string, unknown>;
}

export interface ScopeNodeDef {
    id: string;
    /** Owning scope; `root` places the node on the top-level canvas. */
    scopeId: ScopeId;
    label: string;
    /** Domain-defined kind (e.g. book / course / task / goal / chapter). */
    kind: string;
    /** Relative visual weight (e.g. chapter count); 1 = default. */
    weight?: number;
    color?: string;
    sublabel?: string;
    meta?: Record<string, unknown>;
}

export interface ScopeEdgeDef {
    id: string;
    from: string;
    to: string;
    /** Domain-defined kind, styled via ScopeEdgeStyles. */
    kind: string;
    label?: string;
}

export interface ScopeGraphModel {
    scopes: ScopeDef[];
    nodes: ScopeNodeDef[];
    edges: ScopeEdgeDef[];
}

// ── Styling ─────────────────────────────────────────────────────────

export interface ScopeEdgeStyle {
    stroke: string;
    strokeWidth?: number;
    dash?: string;
    animated?: boolean;
    /** Show an arrowhead (default true). */
    arrow?: boolean;
    label?: string;
}

export type ScopeEdgeStyles = Record<string, ScopeEdgeStyle>;

export const defaultScopeEdgeStyles: ScopeEdgeStyles = {
    default: { stroke: '#8a8f9d', strokeWidth: 1.2 },
};
