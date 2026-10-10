import { layoutScopeGraph, type LaidOutBox } from './layout';
import { parentScopeId, ROOT_SCOPE_ID, type ScopeGraphModel } from './types';

export interface GraphCamera { x: number; y: number; zoom: number }
export interface CanvasBox extends LaidOutBox { id: string; label: string; color?: string; sublabel?: string; group: boolean; depth: number }
export interface ScopeCanvasScene { boxes: CanvasBox[]; byId: Map<string, CanvasBox>; bounds: LaidOutBox }

export function graphBounds(boxes: LaidOutBox[]): LaidOutBox {
    if (!boxes.length) return { x: 0, y: 0, width: 1, height: 1 };
    const x = Math.min(...boxes.map(box => box.x)), y = Math.min(...boxes.map(box => box.y));
    return { x, y, width: Math.max(...boxes.map(box => box.x + box.width)) - x, height: Math.max(...boxes.map(box => box.y + box.height)) - y };
}

export function frameGraph(bounds: LaidOutBox, width: number, height: number, maxZoom = 1.4): GraphCamera {
    const zoom = Math.max(.002, Math.min(maxZoom, Math.max(1, width - 80) / Math.max(1, bounds.width), Math.max(1, height - 80) / Math.max(1, bounds.height)));
    return { x: width / 2 - (bounds.x + bounds.width / 2) * zoom, y: height / 2 - (bounds.y + bounds.height / 2) * zoom, zoom };
}

export function zoomGraph(camera: GraphCamera, x: number, y: number, factor: number): GraphCamera {
    const zoom = Math.max(.002, Math.min(3, camera.zoom * factor));
    return { x: x - (x - camera.x) * zoom / camera.zoom, y: y - (y - camera.y) * zoom / camera.zoom, zoom };
}

export function buildScopeCanvasScene(model: ScopeGraphModel): ScopeCanvasScene {
    const layout = layoutScopeGraph(model, { expandedScopeIds: new Set(model.scopes.map(scope => scope.id)) }, {
        itemWidth: 224, itemHeight: 64, groupPadding: 32, rootPlacement: 'shelves', childPlacement: 'shelves',
    });
    const byId = new Map<string, CanvasBox>();
    const absolute = (id: string): CanvasBox => {
        const cached = byId.get(id); if (cached) return cached;
        const scope = model.scopes.find(scope => scope.id === id)!;
        const box = layout.scopes.get(id)!;
        const parent = parentScopeId(id);
        const origin = parent && parent !== ROOT_SCOPE_ID ? absolute(parent) : { x: 0, y: 0, depth: 0 };
        const result = { ...box, x: box.x + origin.x, y: box.y + origin.y, id, label: scope.label, color: scope.color, group: true, depth: origin.depth + 1 };
        byId.set(id, result); return result;
    };
    const groups = model.scopes.map(scope => absolute(scope.id));
    const items = model.nodes.map(node => {
        const box = layout.nodes.get(node.id)!;
        const origin = node.scopeId === ROOT_SCOPE_ID ? { x: 0, y: 0, depth: 0 } : absolute(node.scopeId);
        const item = { ...box, x: box.x + origin.x, y: box.y + origin.y, id: node.id, label: node.label, sublabel: node.sublabel, color: node.color, group: false, depth: origin.depth + 1 };
        byId.set(item.id, item); return item;
    });
    const boxes = [...groups.sort((a, b) => a.depth - b.depth), ...items];
    return { boxes, byId, bounds: graphBounds(boxes) };
}
