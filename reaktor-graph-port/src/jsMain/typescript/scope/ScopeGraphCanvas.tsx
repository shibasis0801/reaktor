import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState, type CSSProperties } from 'react';
import { buildScopeCanvasScene, frameGraph, graphBounds, zoomGraph, type GraphCamera } from './canvasScene';
import type { ScopeEdgeStyles, ScopeGraphModel } from './types';

export interface ScopeCanvasHandle { fit(ids?: string[]): void; zoom(factor: number): void; back(): void; forward(): void }
export interface ScopeCanvasAppearance { canvas: string; card: string; text: string; muted: string; line: string; selected: string }
export interface ScopeGraphCanvasProps {
    model: ScopeGraphModel; selectedId?: string | null; onSelect: (id: string) => void;
    appearance: ScopeCanvasAppearance; edgeStyles?: ScopeEdgeStyles; hiddenEdgeKinds?: ReadonlySet<string>;
    highlightedIds?: ReadonlySet<string>; onCamera?: (camera: GraphCamera, back: boolean, forward: boolean) => void;
    style?: CSSProperties;
}

export const ScopeGraphCanvas = forwardRef<ScopeCanvasHandle, ScopeGraphCanvasProps>(function ScopeGraphCanvas({ model, selectedId, onSelect, appearance, edgeStyles = {}, hiddenEdgeKinds, highlightedIds, onCamera, style }, ref) {
    const host = useRef<HTMLDivElement>(null), canvas = useRef<HTMLCanvasElement>(null), mini = useRef<HTMLCanvasElement>(null);
    const scene = useMemo(() => buildScopeCanvasScene(model), [model]);
    const [size, setSize] = useState({ width: 1, height: 1 });
    const camera = useRef<GraphCamera>({ x: 0, y: 0, zoom: 1 });
    const history = useRef<GraphCamera[]>([]), cursor = useRef(-1);
    const [, refresh] = useState(0);
    const pointers = useRef(new Map<number, { x: number; y: number }>());
    const gesture = useRef<{ x: number; y: number; camera: GraphCamera; moved: boolean } | null>(null);
    const frame = useRef(0);
    const current = useRef({ selectedId, appearance, edgeStyles, hiddenEdgeKinds, highlightedIds, onCamera, size, scene, model });
    current.current = { selectedId, appearance, edgeStyles, hiddenEdgeKinds, highlightedIds, onCamera, size, scene, model };
    const paint = () => {
        const started = performance.now();
        const { appearance: a, scene: s, size: sz, model: m, selectedId: selected, hiddenEdgeKinds: hidden, edgeStyles: styles, highlightedIds: highlights } = current.current;
        const ctx = canvas.current?.getContext('2d'); if (!ctx || sz.width < 2) return;
        const ratio = Math.min(window.devicePixelRatio || 1, 2), c = camera.current;
        const el = canvas.current!;
        if (el.width !== Math.round(sz.width * ratio) || el.height !== Math.round(sz.height * ratio)) { el.width = Math.round(sz.width * ratio); el.height = Math.round(sz.height * ratio); }
        ctx.setTransform(ratio, 0, 0, ratio, 0, 0); ctx.fillStyle = a.canvas; ctx.fillRect(0, 0, sz.width, sz.height);
        const left = -c.x / c.zoom, top = -c.y / c.zoom, right = (sz.width - c.x) / c.zoom, bottom = (sz.height - c.y) / c.zoom;
        const visible = (box: { x: number; y: number; width: number; height: number }) => box.x <= right && box.y <= bottom && box.x + box.width >= left && box.y + box.height >= top;
        ctx.translate(c.x, c.y); ctx.scale(c.zoom, c.zoom);
        const font = getComputedStyle(host.current!).fontFamily;
        const text = (value: string, x: number, y: number, width: number, pixels: number, color: string, bold = false) => {
            ctx.font = `${bold ? '600 ' : ''}${pixels}px ${font}`; ctx.fillStyle = color;
            let label = value;
            if (ctx.measureText(label).width > width) { while (label.length > 1 && ctx.measureText(`${label}…`).width > width) label = label.slice(0, -1); label += '…'; }
            ctx.fillText(label, x, y);
        };
        for (const box of s.boxes) if (box.group && visible(box)) {
            ctx.globalAlpha = box.depth === 1 ? .08 : .035; ctx.fillStyle = box.color ?? a.selected; ctx.fillRect(box.x, box.y, box.width, box.height);
            ctx.globalAlpha = box.depth === 1 ? .6 : .25; ctx.strokeStyle = box.color ?? a.line; ctx.lineWidth = 1 / c.zoom; ctx.strokeRect(box.x, box.y, box.width, box.height);
        }
        const activeEdges = m.edges.filter(edge => !hidden?.has(edge.kind) && (edge.from === selected || edge.to === selected));
        const neighbors = new Set(activeEdges.flatMap(edge => [edge.from, edge.to]));
        const line = (edge: typeof m.edges[number], active: boolean) => {
            const from = s.byId.get(edge.from), to = s.byId.get(edge.to); if (!from || !to) return;
            const x1 = from.x + from.width / 2, y1 = from.y + from.height / 2, x2 = to.x + to.width / 2, y2 = to.y + to.height / 2;
            if (Math.max(x1, x2) < left || Math.min(x1, x2) > right || Math.max(y1, y2) < top || Math.min(y1, y2) > bottom) return;
            const st = styles[edge.kind]; ctx.globalAlpha = active ? .95 : selected ? .035 : edge.kind === 'chapter' ? .22 : .12;
            ctx.strokeStyle = st?.stroke ?? a.selected; ctx.lineWidth = (active ? 1.9 : .7) / c.zoom;
            ctx.setLineDash(st?.dash ? st.dash.split(/[ ,]+/).map(Number).map(n => n / c.zoom) : []);
            ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke(); ctx.setLineDash([]);
            if (active && c.zoom > .25) {
                const angle = Math.atan2(y2 - y1, x2 - x1), distance = Math.hypot(x2 - x1, y2 - y1);
                if (distance > 1) { const inset = Math.min(to.width / 2, to.height / 2) + 4 / c.zoom; const x = x2 - Math.cos(angle) * inset, y = y2 - Math.sin(angle) * inset;
                    ctx.fillStyle = st?.stroke ?? a.selected; ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x - Math.cos(angle - .45) * 9 / c.zoom, y - Math.sin(angle - .45) * 9 / c.zoom); ctx.lineTo(x - Math.cos(angle + .45) * 9 / c.zoom, y - Math.sin(angle + .45) * 9 / c.zoom); ctx.closePath(); ctx.fill(); }
            }
        };
        for (const edge of m.edges) if (!hidden?.has(edge.kind) && edge.from !== selected && edge.to !== selected) line(edge, false);
        for (const edge of activeEdges) line(edge, true);
        ctx.globalAlpha = 1;
        for (const box of s.boxes) if (!box.group && visible(box)) {
            const chosen = box.id === selected, highlighted = highlights?.has(box.id);
            ctx.globalAlpha = selected && !chosen && !neighbors.has(box.id) ? .4 : 1;
            const detailed = c.zoom >= .36;
            ctx.fillStyle = detailed ? chosen ? a.canvas : a.card : box.color ?? a.selected; ctx.fillRect(box.x, box.y, box.width, box.height);
            ctx.strokeStyle = chosen || highlighted ? a.selected : a.line; ctx.lineWidth = (chosen || highlighted ? 3 : 1) / c.zoom; ctx.strokeRect(box.x, box.y, box.width, box.height);
            if (detailed) { ctx.fillStyle = box.color ?? a.selected; ctx.fillRect(box.x, box.y, 4, box.height);
                text(box.label, box.x + 12, box.y + Math.max(26, 15 / c.zoom), box.width - 24, Math.max(13, 11 / c.zoom), a.text, true);
                if (c.zoom >= .6) text(box.sublabel ?? '', box.x + 12, box.y + 47, box.width - 24, Math.max(10, 9 / c.zoom), a.muted);
            }
        }
        ctx.globalAlpha = 1;
        ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
        for (const box of s.boxes) if (box.group && visible(box) && (box.depth === 1 || c.zoom > .36) && box.width * c.zoom > 80) {
            const pixels = box.depth === 1 ? 13 : 11, x = c.x + box.x * c.zoom + 6, y = c.y + box.y * c.zoom + 4;
            ctx.font = `600 ${pixels}px ${font}`;
            const width = Math.min(box.width * c.zoom - 12, ctx.measureText(box.label).width + 12);
            ctx.fillStyle = a.canvas; ctx.globalAlpha = .94; ctx.fillRect(x, y, width, pixels + 8); ctx.globalAlpha = 1;
            text(box.label, x + 4, y + pixels + 2, width - 8, pixels, box.color ?? a.text, true);
        }
        const mc = mini.current?.getContext('2d'); if (mc) {
            const mw = 180, mh = 118, b = s.bounds, z = Math.min((mw - 8) / b.width, (mh - 8) / b.height), ox = (mw - b.width * z) / 2 - b.x * z, oy = (mh - b.height * z) / 2 - b.y * z;
            mc.clearRect(0, 0, mw, mh); mc.fillStyle = a.canvas; mc.fillRect(0, 0, mw, mh);
            for (const box of s.boxes) if (box.depth === 1 || !box.group) { mc.globalAlpha = box.group ? .18 : .7; mc.fillStyle = box.color ?? a.selected; mc.fillRect(ox + box.x * z, oy + box.y * z, Math.max(1, box.width * z), Math.max(1, box.height * z)); }
            mc.globalAlpha = 1; mc.strokeStyle = a.selected; mc.lineWidth = 1.5; mc.strokeRect(ox + left * z, oy + top * z, sz.width / c.zoom * z, sz.height / c.zoom * z);
        }
        el.dataset.renderedNodeCount = String(m.nodes.length);
        performance.clearMeasures('reaktor.scopeCanvas.paint');
        performance.measure('reaktor.scopeCanvas.paint', { start: started, end: performance.now() });
    };
    const schedule = () => { cancelAnimationFrame(frame.current); frame.current = requestAnimationFrame(paint); };
    const notify = () => { current.current.onCamera?.(camera.current, cursor.current > 0, cursor.current < history.current.length - 1); refresh(n => n + 1); schedule(); };
    const commit = () => {
        const last = history.current[cursor.current], next = camera.current;
        if (!last || Math.abs(last.x - next.x) + Math.abs(last.y - next.y) + Math.abs(last.zoom - next.zoom) > .01) {
            history.current = [...history.current.slice(0, cursor.current + 1), { ...next }].slice(-80); cursor.current = history.current.length - 1;
        }
        notify();
    };
    const fit = (ids?: string[]) => {
        const boxes = ids?.map(id => scene.byId.get(id)).filter(box => !!box);
        const width = host.current?.clientWidth ?? size.width, height = host.current?.clientHeight ?? size.height;
        previousSize.current = { width, height };
        camera.current = frameGraph(boxes?.length ? graphBounds(boxes) : scene.bounds, width, height); commit();
    };
    useImperativeHandle(ref, () => ({ fit, zoom: factor => { camera.current = zoomGraph(camera.current, size.width / 2, size.height / 2, factor); commit(); },
        back: () => { if (cursor.current > 0) { camera.current = { ...history.current[--cursor.current] }; notify(); } },
        forward: () => { if (cursor.current < history.current.length - 1) { camera.current = { ...history.current[++cursor.current] }; notify(); } },
    }));
    useEffect(() => { const observer = new ResizeObserver(entries => { const rect = entries[0].contentRect; setSize({ width: rect.width, height: rect.height }); }); observer.observe(host.current!); return () => observer.disconnect(); }, []);
    const initialized = useRef<typeof scene | null>(null), previousSize = useRef(size);
    useEffect(() => {
        if (size.width < 2 || size.height < 2) return;
        if (initialized.current !== scene) { initialized.current = scene; history.current = []; cursor.current = -1; camera.current = frameGraph(scene.bounds, size.width, size.height); commit(); }
        else if (previousSize.current.width > 1) { camera.current = { ...camera.current, x: camera.current.x + (size.width - previousSize.current.width) / 2, y: camera.current.y + (size.height - previousSize.current.height) / 2 }; notify(); }
        previousSize.current = size;
    }, [scene, size]);
    useEffect(schedule, [selectedId, appearance, edgeStyles, hiddenEdgeKinds, highlightedIds]);
    useEffect(() => {
        const element = canvas.current!; let timer: ReturnType<typeof setTimeout>;
        const wheel = (event: WheelEvent) => { event.preventDefault(); const rect = element.getBoundingClientRect(); camera.current = zoomGraph(camera.current, event.clientX - rect.left, event.clientY - rect.top, Math.exp(-event.deltaY * .002)); schedule(); clearTimeout(timer); timer = setTimeout(commit, 160); };
        element.addEventListener('wheel', wheel, { passive: false });
        return () => { element.removeEventListener('wheel', wheel); clearTimeout(timer); cancelAnimationFrame(frame.current); };
    }, [scene]);
    const point = (event: React.PointerEvent) => { const rect = canvas.current!.getBoundingClientRect(); return { x: event.clientX - rect.left, y: event.clientY - rect.top }; };
    return <div ref={host} style={{ position: 'relative', width: '100%', height: '100%', ...style }}>
        <canvas ref={canvas} tabIndex={0} role="application" aria-label="Expanded graph canvas. Arrow keys pan, plus and minus zoom, zero shows the entire graph. Use search and the outline for individual nodes."
            data-node-count={model.nodes.length} data-edge-count={model.edges.length} data-zoom={camera.current.zoom.toFixed(4)}
            style={{ display: 'block', width: '100%', height: '100%', touchAction: 'none', cursor: 'grab' }}
            onPointerDown={event => { canvas.current!.focus(); canvas.current!.setPointerCapture(event.pointerId); const p = point(event); pointers.current.set(event.pointerId, p); if (pointers.current.size === 1) gesture.current = { ...p, camera: { ...camera.current }, moved: false }; else if (gesture.current) gesture.current.moved = true; }}
            onPointerMove={event => {
                if (!pointers.current.has(event.pointerId)) return;
                const before = [...pointers.current.values()], p = point(event); pointers.current.set(event.pointerId, p); const after = [...pointers.current.values()];
                if (before.length === 2) { const distance = (ps: typeof before) => Math.hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y); const old = { x: (before[0].x + before[1].x) / 2, y: (before[0].y + before[1].y) / 2 }, next = { x: (after[0].x + after[1].x) / 2, y: (after[0].y + after[1].y) / 2 };
                    camera.current = zoomGraph(camera.current, old.x, old.y, distance(after) / Math.max(1, distance(before))); camera.current.x += next.x - old.x; camera.current.y += next.y - old.y;
                } else if (gesture.current) { const dx = p.x - gesture.current.x, dy = p.y - gesture.current.y; if (Math.hypot(dx, dy) > 4) gesture.current.moved = true; camera.current = { ...gesture.current.camera, x: gesture.current.camera.x + dx, y: gesture.current.camera.y + dy }; }
                schedule();
            }}
            onPointerUp={event => {
                const p = point(event); pointers.current.delete(event.pointerId);
                if (!gesture.current?.moved && !pointers.current.size) { const c = camera.current, x = (p.x - c.x) / c.zoom, y = (p.y - c.y) / c.zoom;
                    const hit = [...scene.boxes].reverse().find(box => !box.group && x >= box.x && y >= box.y && x <= box.x + box.width && y <= box.y + box.height); if (hit) onSelect(hit.id);
                }
                if (!pointers.current.size) { gesture.current = null; commit(); } else { const remaining = [...pointers.current.values()][0]; gesture.current = { ...remaining, camera: { ...camera.current }, moved: true }; }
            }} onPointerCancel={() => { pointers.current.clear(); gesture.current = null; commit(); }}
            onDoubleClick={event => { const rect = canvas.current!.getBoundingClientRect(), c = camera.current, x = (event.clientX - rect.left - c.x) / c.zoom, y = (event.clientY - rect.top - c.y) / c.zoom;
                const hit = [...scene.boxes].reverse().find(box => x >= box.x && y >= box.y && x <= box.x + box.width && y <= box.y + box.height); if (hit) fit([hit.id]);
            }}
            onKeyDown={event => { if (['+', '=', '-', '0', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) { event.preventDefault();
                if (event.key === '0') fit(); else if (['+', '=', '-'].includes(event.key)) { camera.current = zoomGraph(camera.current, size.width / 2, size.height / 2, event.key === '-' ? .75 : 1.33); commit(); }
                else { camera.current = { ...camera.current, x: camera.current.x + (event.key === 'ArrowLeft' ? 80 : event.key === 'ArrowRight' ? -80 : 0), y: camera.current.y + (event.key === 'ArrowUp' ? 80 : event.key === 'ArrowDown' ? -80 : 0) }; commit(); }
            } }}/>
        <canvas ref={mini} width={180} height={118} role="button" tabIndex={0} aria-label="Graph overview. Click to move the viewport; Enter shows everything." style={{ position: 'absolute', right: 16, bottom: 16, width: 180, height: 118, border: `1px solid ${appearance.line}`, borderRadius: 8, cursor: 'crosshair', background: appearance.canvas }}
            onKeyDown={event => { if (event.key === 'Enter') fit(); }} onClick={event => { const rect = mini.current!.getBoundingClientRect(), b = scene.bounds, z = Math.min(172 / b.width, 110 / b.height), ox = (180 - b.width * z) / 2 - b.x * z, oy = (118 - b.height * z) / 2 - b.y * z;
                camera.current = { ...camera.current, x: size.width / 2 - ((event.clientX - rect.left) * 180 / rect.width - ox) / z * camera.current.zoom, y: size.height / 2 - ((event.clientY - rect.top) * 118 / rect.height - oy) / z * camera.current.zoom }; commit(); }}/>
    </div>;
});
