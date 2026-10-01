import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type MutableRefObject,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import { ReactFlow, ReactFlowProvider, useReactFlow, useStore, useStoreApi, type Node, type NodeProps, type NodeTypes } from '@xyflow/react';
import '@xyflow/react/dist/base.css';
import '../blueprint.css';
import { layoutBounds, pinY } from '../engine';
import type { Focus } from '../highlight';
import type { BlueprintLayout, Card, Frame, Link } from '../types';
import { BlueprintWires } from './BlueprintWires';
import { cameraMemory, centreOn, clamp, fitBounds, GestureZoom, place, ProgrammaticZoom, wheelFactor, zoomAround, type Bounds, type Insets, type Viewport } from './camera';
import { CameraContext, FramePartContext, HoverStore, SceneContext, useHoveredPin, useScene, type CameraControls, type MapScene, type WireState, type WireStyle } from './context';
import { DefaultThresholds, lookFor, useLook, type Look, type LookThresholds } from './looks';

export interface Reveal {
  id: string;
  row?: number | null;
  nonce: number;
}

export interface SelectDetail {
  row: number | null;
  source: 'pointer' | 'keyboard' | 'wire';
}

export interface BlueprintMapProps {
  layout: BlueprintLayout | null;
  ready?: boolean;
  fitKey: string;
  memoryKey: string;
  className?: string;
  insets?: Partial<Insets>;
  selected?: string | null;
  selectedRow?: number | null;
  highlight?: ReadonlySet<string>;
  focus?: Focus;
  litLinks?: ReadonlySet<string>;
  thresholds?: LookThresholds;
  corridors?: boolean;
  reveal?: Reveal | null;
  renderCard: (card: Card, look: Look) => ReactNode;
  renderFrame: (frame: Frame, look: Look) => ReactNode;
  wireStyle: (link: Link, state: WireState) => WireStyle | null;
  cardLabel?: (card: Card) => string;
  pinTooltip?: (card: Card, row: number) => ReactNode;
  announce?: (frame: Frame) => string;
  onSelect?: (id: string | null, detail: SelectDetail) => void;
  onCamera?: (viewport: Viewport) => void;
  onKeyDown?: (event: ReactKeyboardEvent<HTMLDivElement>) => void;
  cameraRef?: MutableRefObject<CameraControls | null>;
  children?: ReactNode;
}

const WiresId = '__blueprint_wires';
const LabelsId = '__blueprint_labels';
const empty = new Set<string>();

function CardNode({ id }: NodeProps) {
  const scene = useScene();
  const look = useLook(scene.thresholds);
  const card = scene.layout.cards[id];
  return card ? <>{scene.renderCard(card, look)}</> : null;
}

function FrameNode({ id }: NodeProps) {
  const scene = useScene();
  const look = useLook(scene.thresholds);
  const frame = scene.layout.frames.find(item => `frame:${item.key}` === id);
  return frame ? <>{scene.renderFrame(frame, look)}</> : null;
}

function WiresNode() {
  return <BlueprintWires/>;
}

function LabelsNode() {
  const scene = useScene();
  const look = useLook(scene.thresholds);
  if (look === 'chapter') return null;
  return <div className="bp-labels" data-part="frame-labels">
    <FramePartContext.Provider value="banner">
      {scene.layout.frames.map(frame => <div key={frame.key} className="bp-labels__slot" style={{ left: frame.x, top: frame.y, width: frame.width, height: frame.height }}>{scene.renderFrame(frame, look)}</div>)}
    </FramePartContext.Provider>
  </div>;
}

const nodeTypes: NodeTypes = { card: CardNode, frame: FrameNode, wires: WiresNode, labels: LabelsNode };

function readingOrder(layout: BlueprintLayout): Card[] {
  return layout.frames.flatMap(frame => frame.nodes.map(id => layout.cards[id]).filter(Boolean)
    .sort((a, b) => Math.round(a.x) - Math.round(b.x) || a.y - b.y));
}

function isTyping(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  return !!element && (element.tagName === 'INPUT' || element.tagName === 'TEXTAREA' || element.tagName === 'SELECT' || element.isContentEditable);
}

function PinTooltip({ host, render }: { host: HTMLDivElement | null; render?: (card: Card, row: number) => ReactNode }) {
  const scene = useScene();
  const hovered = useHoveredPin(scene.pins);
  const [shown, setShown] = useState<string | null>(null);
  const key = hovered ? `${hovered.card}:${hovered.row}` : null;
  useEffect(() => {
    if (!key) { setShown(null); return; }
    const timer = window.setTimeout(() => setShown(key), 350);
    return () => window.clearTimeout(timer);
  }, [key]);
  if (!render || !hovered || shown !== key || !host) return null;
  const card = scene.layout.cards[hovered.card];
  if (!card) return null;
  const content = render(card, hovered.row);
  if (!content) return null;
  const rect = host.getBoundingClientRect();
  const left = clamp(hovered.x - rect.left + 14, 8, Math.max(8, rect.width - 388));
  const top = clamp(hovered.y - rect.top + 14, 8, Math.max(8, rect.height - 120));
  return <div className="bp-tooltip" data-part="pin-tooltip" role="tooltip" style={{ left, top }}>{content}</div>;
}

function MapHost(props: BlueprintMapProps) {
  const { layout, fitKey, memoryKey, selected = null, selectedRow = null, focus = 'all', reveal } = props;
  const ready = !!layout && (props.ready ?? true);
  const flow = useReactFlow();
  const store = useStoreApi();
  const hostRef = useRef<HTMLDivElement>(null);
  const width = useStore(state => state.width);
  const height = useStore(state => state.height);
  const memory = cameraMemory(memoryKey);
  const insets: Insets = { top: props.insets?.top ?? 48, start: props.insets?.start ?? 0, bottom: props.insets?.bottom ?? 0 };
  const insetsRef = useRef(insets);
  insetsRef.current = insets;
  const latest = useRef(props);
  latest.current = props;
  const [placed, setPlaced] = useState(() => memory.fitted === fitKey && memory.viewport !== null);
  const [announcement, setAnnouncement] = useState('');
  const [revealed, setRevealed] = useState(memory.revealed);
  const pins = useMemo(() => new HoverStore(), []);
  const thresholds = props.thresholds ?? DefaultThresholds;

  const frameOf = useMemo(() => new Map(layout?.frames.flatMap(frame => frame.nodes.map(id => [id, frame.key] as const)) ?? []), [layout]);
  const rowOfPort = useMemo(() => new Map(Object.values(layout?.cards ?? {}).flatMap(card => card.pins.map(pin => [`${card.id}${pin.provides ? '>' : '<'}${pin.key}`, pin.row] as const))), [layout]);

  const cache = useRef<{ layout: BlueprintLayout | null; nodes: Map<string, Node> }>({ layout: null, nodes: new Map() });
  const cardLabel = props.cardLabel;
  const nodes = useMemo<Node[]>(() => {
    if (!layout) return [];
    const remembered = cache.current;
    if (remembered.layout !== layout) cache.current = { layout, nodes: new Map() };
    const known = cache.current.nodes;
    const frames: Node[] = layout.frames.map(frame => {
      const id = `frame:${frame.key}`;
      const existing = known.get(id);
      if (existing) return existing;
      const node: Node = {
        id, type: 'frame', position: { x: frame.x, y: frame.y }, width: frame.width, height: frame.height, zIndex: 1, data: {},
        draggable: false, selectable: false, connectable: false, deletable: false, focusable: false,
        domAttributes: { 'data-testid': `map-frame-${frame.key}` } as Node['domAttributes'],
      };
      known.set(id, node);
      return node;
    });
    const wires: Node = known.get(WiresId) ?? {
      id: WiresId, type: 'wires', position: { x: 0, y: 0 }, width: Math.max(layout.width, 1), height: Math.max(layout.height, 1), zIndex: 2, data: {},
      draggable: false, selectable: false, connectable: false, deletable: false, focusable: false, style: { pointerEvents: 'none' },
      domAttributes: { 'aria-hidden': true, 'data-testid': 'map-wires' } as Node['domAttributes'],
    };
    known.set(WiresId, wires);
    const labels: Node = known.get(LabelsId) ?? {
      id: LabelsId, type: 'labels', position: { x: 0, y: 0 }, width: Math.max(layout.width, 1), height: Math.max(layout.height, 1), zIndex: 4, data: {},
      draggable: false, selectable: false, connectable: false, deletable: false, focusable: false, style: { pointerEvents: 'none' },
      domAttributes: { 'aria-hidden': true } as Node['domAttributes'],
    };
    known.set(LabelsId, labels);
    const cards: Node[] = readingOrder(layout).map(card => {
      const pressed = selected === card.id;
      const label = cardLabel?.(card) ?? card.id;
      const existing = known.get(card.id);
      if (existing && existing.ariaLabel === label && (existing.domAttributes as Record<string, unknown>)['aria-pressed'] === pressed) return existing;
      const node: Node = {
        id: card.id, type: 'card', position: { x: card.x, y: card.y }, width: card.width, height: card.height, zIndex: 3, data: {},
        draggable: false, selectable: false, connectable: false, deletable: false, focusable: true, ariaRole: 'button', ariaLabel: label,
        domAttributes: { 'data-testid': `map-card-${card.id}`, 'aria-pressed': pressed } as Node['domAttributes'],
      };
      known.set(card.id, node);
      return node;
    });
    return [...frames, wires, ...cards, labels];
  }, [layout, selected, cardLabel]);

  const measure = useCallback(() => {
    const flowElement = hostRef.current?.querySelector('.react-flow');
    const rect = flowElement?.getBoundingClientRect();
    return rect && rect.width > 0 ? { width: rect.width, height: rect.height } : { width: store.getState().width, height: store.getState().height };
  }, [store]);

  const remember = useCallback(() => {
    const current = flow.getViewport();
    memory.history.push(current);
    if (memory.history.length > 50) memory.history.shift();
    memory.future = [];
  }, [flow, memory]);

  const apply = useCallback((viewport: Viewport) => {
    void flow.setViewport(viewport);
  }, [flow]);

  useLayoutEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let last: [number, number, number] | null = null;
    let settle = 0;
    let rest = 0;
    let look: Look | null = null;
    let scaled = 0;
    const write = (transform: [number, number, number]) => {
      const [x, y, zoom] = transform;
      if (scaled === 0 || Math.abs(Math.log(zoom / scaled)) > 0.03) {
        scaled = zoom;
        host.style.setProperty('--bp-zoom', zoom.toFixed(4));
        host.style.setProperty('--bp-inv', (1 / zoom).toFixed(4));
        host.style.setProperty('--bp-frame-scale', Math.min(1 / clamp(zoom, 0.35, 1), 1.8).toFixed(4));
      }
      const minor = 16 * zoom;
      const major = 128 * zoom;
      host.style.setProperty('--bp-grid-minor', `${minor.toFixed(3)}px`);
      host.style.setProperty('--bp-grid-major', `${major.toFixed(3)}px`);
      host.style.setProperty('--bp-grid-x', `${x.toFixed(2)}px`);
      host.style.setProperty('--bp-grid-y', `${y.toFixed(2)}px`);
      host.dataset.minor = minor >= 7 ? 'on' : 'off';
      host.dataset.major = major >= 7 ? 'on' : 'off';
      host.dataset.zoom = zoom.toFixed(4);
      const next = lookFor(zoom, latest.current.thresholds ?? DefaultThresholds);
      if (next !== look) { look = next; host.dataset.look = next; }
    };
    const settled = () => {
      const viewport = flow.getViewport();
      latest.current.onCamera?.(viewport);
      const current = latest.current.layout;
      const announce = latest.current.announce;
      if (!current || !announce) return;
      const centreX = (store.getState().width / 2 - viewport.x) / viewport.zoom;
      const centreY = (store.getState().height / 2 - viewport.y) / viewport.zoom;
      const frame = current.frames.find(item => centreX >= item.x && centreX <= item.x + item.width && centreY >= item.y && centreY <= item.y + item.height);
      if (frame) setAnnouncement(announce(frame));
    };
    write(store.getState().transform);
    return store.subscribe(state => {
      const transform = state.transform;
      if (last && transform[0] === last[0] && transform[1] === last[1] && transform[2] === last[2]) return;
      last = [transform[0], transform[1], transform[2]];
      write(last);
      memory.viewport = { x: transform[0], y: transform[1], zoom: transform[2] };
      if (host.dataset.moving !== 'true') host.dataset.moving = 'true';
      window.clearTimeout(rest);
      rest = window.setTimeout(() => {
        delete host.dataset.moving;
        const exact = store.getState().transform[2];
        if (exact !== scaled) { scaled = 0; write([...store.getState().transform] as [number, number, number]); }
      }, 160);
      window.clearTimeout(settle);
      settle = window.setTimeout(settled, 300);
    });
  }, [store, flow, memory]);

  useLayoutEffect(() => {
    if (!layout || !ready || width <= 0 || height <= 0) return;
    if (memory.fitted === fitKey && memory.viewport) {
      if (!placed) setPlaced(true);
      return;
    }
    const size = measure();
    const viewport = fitBounds(layoutBounds(layout), size.width, size.height, insetsRef.current);
    if (!viewport) return;
    apply(viewport);
    memory.fitted = fitKey;
    memory.fitZoom = viewport.zoom;
    memory.viewport = viewport;
    setPlaced(true);
  }, [layout, ready, width, height, fitKey, memory, apply, placed, measure]);

  useEffect(() => {
    if (placed && ready) performance.mark(`blueprint-placed:${memoryKey}`);
  }, [placed, ready, memoryKey]);

  useLayoutEffect(() => {
    if (!reveal || !layout || !ready || width <= 0 || height <= 0) return;
    if (reveal.nonce <= memory.revealed) return;
    const card = layout.cards[reveal.id];
    if (!card) return;
    remember();
    const size = measure();
    const current = flow.getViewport().zoom;
    const zoom = current >= 0.45 ? current : 0.9;
    const visible = size.height - insetsRef.current.top - insetsRef.current.bottom;
    const y = reveal.row !== null && reveal.row !== undefined ? card.y + pinY(reveal.row) : card.y + Math.min(card.height / 2, Math.max(visible / 2 - 16, 0) / zoom);
    apply(place(card.x + card.width / 2, y, zoom, size.width, size.height, insetsRef.current));
    memory.revealed = reveal.nonce;
    setRevealed(reveal.nonce);
  }, [reveal, layout, ready, width, height, memory, flow, remember, apply, measure]);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const wheel = (event: WheelEvent) => {
      event.preventDefault();
      const viewport = flow.getViewport();
      const rect = host.getBoundingClientRect();
      if (event.ctrlKey || event.metaKey) {
        apply(zoomAround(viewport, wheelFactor(event.deltaY, event.deltaMode), event.clientX - rect.left, event.clientY - rect.top, Math.min(GestureZoom.min, memory.fitZoom), GestureZoom.max));
        return;
      }
      const unit = event.deltaMode === 1 ? 20 : event.deltaMode === 2 ? rect.height : 1;
      let dx = event.deltaX * unit;
      let dy = event.deltaY * unit;
      if (event.shiftKey && dx === 0) { dx = dy; dy = 0; }
      apply({ x: viewport.x - dx, y: viewport.y - dy, zoom: viewport.zoom });
    };
    host.addEventListener('wheel', wheel, { passive: false });
    return () => host.removeEventListener('wheel', wheel);
  }, [flow, apply, memory]);

  const controls = useMemo<CameraControls>(() => {
    const size = measure;
    const frameBounds = (bounds: Bounds, maxZoom = 1) => {
      const { width: w, height: h } = size();
      const viewport = fitBounds(bounds, w, h, insetsRef.current, 0.03, maxZoom);
      if (!viewport) return;
      remember();
      apply(viewport);
    };
    return {
      ready,
      canCentre: !!selected && !!layout?.cards[selected],
      fit: () => { const current = latest.current.layout; if (current) frameBounds(layoutBounds(current)); },
      centre: () => {
        const current = latest.current.layout;
        const id = latest.current.selected;
        const card = id ? current?.cards[id] : undefined;
        if (!card) return;
        const { width: w, height: h } = size();
        remember();
        const row = latest.current.selectedRow;
        apply(centreOn(card.x + card.width / 2, row !== null && row !== undefined ? card.y + pinY(row) : card.y + card.height / 2, flow.getViewport().zoom, w, h, insetsRef.current));
      },
      zoomTo: zoom => {
        const { width: w, height: h } = size();
        const viewport = flow.getViewport();
        apply(zoomAround(viewport, clamp(zoom, ProgrammaticZoom.min, ProgrammaticZoom.max) / viewport.zoom, w / 2, h / 2, ProgrammaticZoom.min, ProgrammaticZoom.max));
      },
      zoomBy: factor => {
        const { width: w, height: h } = size();
        apply(zoomAround(flow.getViewport(), factor, w / 2, h / 2, Math.min(GestureZoom.min, memory.fitZoom), GestureZoom.max));
      },
      panBy: (dx, dy) => {
        const viewport = flow.getViewport();
        apply({ x: viewport.x + dx, y: viewport.y + dy, zoom: viewport.zoom });
      },
      frameBounds,
      back: () => {
        const previous = memory.history.pop();
        if (!previous) return;
        memory.future.push(flow.getViewport());
        apply(previous);
      },
      forward: () => {
        const next = memory.future.pop();
        if (!next) return;
        memory.history.push(flow.getViewport());
        apply(next);
      },
      viewport: () => flow.getViewport(),
      setViewport: (viewport, keep = true) => { if (keep) remember(); apply(viewport); },
    };
  }, [ready, selected, layout, flow, memory, remember, apply, measure]);

  const cameraRef = props.cameraRef;
  useEffect(() => {
    if (!cameraRef) return;
    cameraRef.current = controls;
    return () => { if (cameraRef.current === controls) cameraRef.current = null; };
  }, [cameraRef, controls]);

  const scene = useMemo<MapScene | null>(() => layout ? {
    layout,
    thresholds,
    selected,
    selectedRow,
    highlight: props.highlight ?? empty,
    focus,
    litLinks: props.litLinks ?? empty,
    corridors: props.corridors ?? true,
    frameOf,
    rowOfPort,
    renderCard: props.renderCard,
    renderFrame: props.renderFrame,
    wireStyle: props.wireStyle,
    pins,
  } : null, [layout, thresholds, selected, selectedRow, props.highlight, focus, props.litLinks, props.corridors, frameOf, rowOfPort, props.renderCard, props.renderFrame, props.wireStyle, pins]);

  const select = (id: string | null, detail: SelectDetail) => latest.current.onSelect?.(id, detail);
  const onNodeClick = (event: ReactMouseEvent, node: Node) => {
    if (node.type === 'card') {
      const row = (event.target as HTMLElement).closest('[data-row]')?.getAttribute('data-row');
      select(node.id, { row: row === null || row === undefined ? null : Number(row), source: 'pointer' });
      return;
    }
    if (node.type === 'wires') {
      const id = (event.target as Element).closest('[data-link]')?.getAttribute('data-link');
      const link = id ? latest.current.layout?.links.find(item => item.id === id) : undefined;
      if (link) {
        const consumer = latest.current.layout?.cards[link.to] ? link.to : link.from;
        const port = consumer === link.to ? link.toPort : link.fromPort;
        const row = port === undefined ? null : rowOfPort.get(`${consumer}${consumer === link.to ? '<' : '>'}${port}`) ?? null;
        select(consumer, { row, source: 'wire' });
        return;
      }
    }
    select(null, { row: null, source: 'pointer' });
  };
  const onNodeDoubleClick = (_: ReactMouseEvent, node: Node) => {
    const current = latest.current.layout;
    if (!current) return;
    if (node.type === 'card') {
      const card = current.cards[node.id];
      if (card) controls.frameBounds({ x: card.x - 24, y: card.y - 24, width: card.width + 48, height: card.height + 48 }, 1.2);
    } else if (node.type === 'frame') {
      const frame = current.frames.find(item => `frame:${item.key}` === node.id);
      if (frame) controls.frameBounds(frame);
    }
  };
  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    latest.current.onKeyDown?.(event);
    if (event.defaultPrevented || isTyping(event.target)) return;
    const command = event.metaKey || event.ctrlKey;
    const key = event.key;
    if (command && key === '[') controls.back();
    else if (command && key === ']') controls.forward();
    else if (command || event.altKey) return;
    else if (key === '0') controls.fit();
    else if (key === 'f' || key === 'F') controls.centre();
    else if (key === '+' || key === '=') controls.zoomBy(1.2);
    else if (key === '-' || key === '_' || key === '−') controls.zoomBy(1 / 1.2);
    else if (key === 'ArrowLeft') controls.panBy(80, 0);
    else if (key === 'ArrowRight') controls.panBy(-80, 0);
    else if (key === 'ArrowUp') controls.panBy(0, 80);
    else if (key === 'ArrowDown') controls.panBy(0, -80);
    else if (key === 'Enter' || key === ' ') {
      const id = (event.target as HTMLElement).closest('.react-flow__node')?.getAttribute('data-id');
      if (!id || !latest.current.layout?.cards[id]) return;
      select(id, { row: null, source: 'keyboard' });
    } else return;
    event.preventDefault();
  };
  const focusHost = (event: ReactPointerEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (target.closest('button, input, select, textarea, a, [tabindex="0"]')) return;
    hostRef.current?.focus({ preventScroll: true });
  };

  return <div
    ref={hostRef}
    className={['bp-map', props.className].filter(Boolean).join(' ')}
    data-testid="blueprint-map"
    data-ready={ready || undefined}
    data-camera={placed ? 'placed' : undefined}
    data-layout-key={layout?.key}
    data-highlight={props.highlight?.size ?? 0}
    data-revealed={revealed}
    tabIndex={-1}
    role="region"
    aria-roledescription="map"
    aria-label="Knowledge map"
    onKeyDown={onKeyDown}
    onPointerDown={focusHost}
    style={{ touchAction: 'none' }}
  >
    <div className="bp-grid" aria-hidden="true"/>
    <CameraContext.Provider value={controls}>
      {scene && <SceneContext.Provider value={scene}>
        <ReactFlow
          className="bp-flow nowheel"
          nodes={nodes}
          edges={[]}
          nodeTypes={nodeTypes}
          defaultViewport={memory.viewport ?? { x: 0, y: 0, zoom: 0.1 }}
          minZoom={ProgrammaticZoom.min}
          maxZoom={ProgrammaticZoom.max}
          onlyRenderVisibleElements
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          edgesFocusable={false}
          nodesFocusable
          selectNodesOnDrag={false}
          zoomOnScroll={false}
          panOnScroll={false}
          zoomOnPinch
          zoomOnDoubleClick={false}
          panOnDrag
          preventScrolling
          deleteKeyCode={null}
          selectionKeyCode={null}
          multiSelectionKeyCode={null}
          panActivationKeyCode={null}
          zoomActivationKeyCode={null}
          elevateNodesOnSelect={false}
          proOptions={{ hideAttribution: true }}
          onNodeClick={onNodeClick}
          onNodeDoubleClick={onNodeDoubleClick}
          onPaneClick={() => select(null, { row: null, source: 'pointer' })}
        />
        <PinTooltip host={hostRef.current} render={props.pinTooltip}/>
      </SceneContext.Provider>}
      {props.children}
    </CameraContext.Provider>
    <div className="bp-sr" aria-live="polite">{announcement}</div>
  </div>;
}

export function BlueprintMap(props: BlueprintMapProps) {
  return <ReactFlowProvider><MapHost {...props}/></ReactFlowProvider>;
}
