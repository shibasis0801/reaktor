/**
 * Deterministic layout for scoped graphs. No physics, no async: the same model
 * and view always produce the same canvas, so expand/collapse feels stable
 * (mirrors the deterministic layout used by reaktor-flow's desktop canvas).
 *
 * Strategy:
 * - Root-level scopes sit at their `anchor` (model coords, scaled); scopes
 *   without anchors fill a golden-angle ring around the anchored centroid.
 * - Inside a scope, children (item nodes and nested scope boxes) pack onto a
 *   phyllotaxis spiral ordered by weight, so heavy nodes gravitate to the
 *   center and boxes never overlap for uniform sizes.
 */

import type { ScopeDef, ScopeGraphModel, ScopeId, ScopeNodeDef, ScopeViewState } from './types';
import { ROOT_SCOPE_ID, isExpanded, parentScopeId } from './types';

export interface ScopeLayoutOptions {
    itemWidth: number;
    itemHeight: number;
    /** Phyllotaxis radial step; larger = sparser scopes. */
    spread: number;
    /** Padding inside scope group boxes (also reserves the header row). */
    groupPadding: number;
    /** Multiplier applied to root scope anchors (model coords → canvas px). */
    anchorScale: number;
    /** Size of a collapsed scope summary card. */
    summaryWidth: number;
    summaryHeight: number;
}

export const defaultScopeLayout: ScopeLayoutOptions = {
    itemWidth: 172,
    itemHeight: 56,
    spread: 66,
    groupPadding: 28,
    anchorScale: 2.1,
    summaryWidth: 208,
    summaryHeight: 88,
};

export interface LaidOutBox {
    /** Position relative to the parent scope's content origin (or canvas for root). */
    x: number;
    y: number;
    width: number;
    height: number;
}

export interface ScopeLayoutResult {
    /** node id → box (relative to parent scope). */
    nodes: Map<string, LaidOutBox>;
    /** scope id → box; expanded scopes sized to content, collapsed to summary card. */
    scopes: Map<ScopeId, LaidOutBox>;
}

const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

/**
 * Deterministic overlap removal for root-level boxes: iteratively push
 * overlapping pairs apart along their center delta. Anchors keep the map's
 * overall geography; this guarantees expanded domains never swallow each other.
 */
function separateRects(boxes: LaidOutBox[], gap = 56, maxIterations = 220): void {
    for (let iteration = 0; iteration < maxIterations; iteration++) {
        let moved = false;
        for (let i = 0; i < boxes.length; i++) {
            for (let j = i + 1; j < boxes.length; j++) {
                const a = boxes[i];
                const b = boxes[j];
                const overlapX = Math.min(a.x + a.width + gap, b.x + b.width + gap) - Math.max(a.x, b.x);
                const overlapY = Math.min(a.y + a.height + gap, b.y + b.height + gap) - Math.max(a.y, b.y);
                if (overlapX <= 0 || overlapY <= 0) continue;
                moved = true;
                const aCx = a.x + a.width / 2;
                const aCy = a.y + a.height / 2;
                const bCx = b.x + b.width / 2;
                const bCy = b.y + b.height / 2;
                // Push along the axis needing the smallest correction.
                if (overlapX < overlapY) {
                    const push = overlapX / 2 + 1;
                    const dir = aCx <= bCx ? -1 : 1;
                    a.x += dir * push;
                    b.x -= dir * push;
                } else {
                    const push = overlapY / 2 + 1;
                    const dir = aCy <= bCy ? -1 : 1;
                    a.y += dir * push;
                    b.y -= dir * push;
                }
            }
        }
        if (!moved) return;
    }
}

interface ChildBox {
    key: string;
    isScope: boolean;
    width: number;
    height: number;
    weight: number;
    x?: number;
    y?: number;
}

/** Pack boxes on a phyllotaxis spiral (deterministic, center-out by weight). */
function packSpiral(children: ChildBox[], spread: number): { width: number; height: number } {
    const sorted = [...children].sort((a, b) => b.weight - a.weight || a.key.localeCompare(b.key));
    let minX = 0;
    let minY = 0;
    let maxX = 0;
    let maxY = 0;
    const placed: ChildBox[] = [];

    const overlaps = (x: number, y: number, w: number, h: number) =>
        placed.some((p) => x < p.x! + p.width + 10 && x + w + 10 > p.x! && y < p.y! + p.height + 10 && y + h + 10 > p.y!);

    sorted.forEach((child, index) => {
        // Walk the spiral outward from the natural slot until collision-free —
        // uniform items land on their slot; oversized boxes (nested scopes) push out.
        let slot = index;
        for (let attempts = 0; attempts < 4096; attempts++, slot++) {
            const radius = spread * Math.sqrt(slot);
            const angle = slot * GOLDEN_ANGLE;
            const x = Math.round(radius * Math.cos(angle) - child.width / 2);
            const y = Math.round(radius * Math.sin(angle) * 0.72 - child.height / 2);
            if (!overlaps(x, y, child.width, child.height)) {
                child.x = x;
                child.y = y;
                break;
            }
        }
        placed.push(child);
        minX = Math.min(minX, child.x!);
        minY = Math.min(minY, child.y!);
        maxX = Math.max(maxX, child.x! + child.width);
        maxY = Math.max(maxY, child.y! + child.height);
    });

    // Normalize to a positive-origin content box.
    for (const child of placed) {
        child.x! -= minX;
        child.y! -= minY;
    }
    return { width: Math.max(maxX - minX, 1), height: Math.max(maxY - minY, 1) };
}

/**
 * Compute the full layout bottom-up: nested expanded scopes are sized first so
 * their bounding boxes participate in the parent's packing as oversized items.
 */
export function layoutScopeGraph(
    model: ScopeGraphModel,
    view: ScopeViewState,
    options: Partial<ScopeLayoutOptions> = {},
): ScopeLayoutResult {
    const opts = { ...defaultScopeLayout, ...options };
    const nodesByScope = new Map<ScopeId, ScopeNodeDef[]>();
    for (const node of model.nodes) {
        const list = nodesByScope.get(node.scopeId);
        if (list) list.push(node);
        else nodesByScope.set(node.scopeId, [node]);
    }
    const scopesByParent = new Map<ScopeId, ScopeDef[]>();
    for (const scope of model.scopes) {
        const parent = parentScopeId(scope.id) ?? ROOT_SCOPE_ID;
        const list = scopesByParent.get(parent);
        if (list) list.push(scope);
        else scopesByParent.set(parent, [scope]);
    }

    const nodeBoxes = new Map<string, LaidOutBox>();
    const scopeBoxes = new Map<ScopeId, LaidOutBox>();

    const itemWeight = (node: ScopeNodeDef) => Math.max(1, node.weight ?? 1);

    /** Layout a scope's content; returns its outer box size. Positions children relatively. */
    const layoutScopeContent = (scopeId: ScopeId): { width: number; height: number } => {
        const children: ChildBox[] = [];
        for (const node of nodesByScope.get(scopeId) ?? []) {
            children.push({
                key: `n:${node.id}`,
                isScope: false,
                width: opts.itemWidth,
                height: opts.itemHeight,
                weight: itemWeight(node),
            });
        }
        for (const scope of scopesByParent.get(scopeId) ?? []) {
            const size = isExpanded(view, scope.id)
                ? layoutScopeContent(scope.id)
                : { width: opts.summaryWidth, height: opts.summaryHeight };
            const box: ChildBox = {
                key: `s:${scope.id}`,
                isScope: true,
                width: size.width,
                height: size.height,
                // Boxes are heavy so they claim central, stable positions.
                weight: 1000 + size.width,
            };
            children.push(box);
        }

        const content = packSpiral(children, opts.spread);
        const pad = opts.groupPadding;
        const headerH = 34;

        for (const child of children) {
            const box: LaidOutBox = {
                x: child.x! + pad,
                y: child.y! + pad + headerH,
                width: child.width,
                height: child.height,
            };
            if (child.isScope) scopeBoxes.set(child.key.slice(2), box);
            else nodeBoxes.set(child.key.slice(2), box);
        }
        return { width: content.width + pad * 2, height: content.height + pad * 2 + headerH };
    };

    // Root level: anchored placement (no packing) so the domain map stays familiar.
    const rootScopes = scopesByParent.get(ROOT_SCOPE_ID) ?? [];
    const anchored = rootScopes.filter((s) => s.anchor);
    const centroid = anchored.length
        ? {
              x: anchored.reduce((a, s) => a + s.anchor!.x, 0) / anchored.length,
              y: anchored.reduce((a, s) => a + s.anchor!.y, 0) / anchored.length,
          }
        : { x: 0, y: 0 };

    const rootBoxes: { id: ScopeId; box: LaidOutBox }[] = rootScopes.map((scope, index) => {
        const size = isExpanded(view, scope.id)
            ? layoutScopeContent(scope.id)
            : { width: opts.summaryWidth, height: opts.summaryHeight };
        const anchor = scope.anchor ?? {
            x: centroid.x + 620 * Math.cos(index * GOLDEN_ANGLE),
            y: centroid.y + 620 * Math.sin(index * GOLDEN_ANGLE),
        };
        return {
            id: scope.id,
            box: {
                x: anchor.x * opts.anchorScale - size.width / 2,
                y: anchor.y * opts.anchorScale - size.height / 2,
                width: size.width,
                height: size.height,
            },
        };
    });
    separateRects(rootBoxes.map((entry) => entry.box));
    for (const { id, box } of rootBoxes) {
        scopeBoxes.set(id, { ...box, x: Math.round(box.x), y: Math.round(box.y) });
    }

    // Root-level loose nodes (rare): ring below the centroid.
    (nodesByScope.get(ROOT_SCOPE_ID) ?? []).forEach((node, index) => {
        nodeBoxes.set(node.id, {
            x: Math.round(centroid.x * opts.anchorScale + (index % 8) * (opts.itemWidth + 24)),
            y: Math.round(centroid.y * opts.anchorScale + 480 + Math.floor(index / 8) * (opts.itemHeight + 18)),
            width: opts.itemWidth,
            height: opts.itemHeight,
        });
    });

    return { nodes: nodeBoxes, scopes: scopeBoxes };
}
