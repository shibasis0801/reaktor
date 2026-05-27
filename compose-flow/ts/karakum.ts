import {
  addEdge as addEdgeImpl,
  applyEdgeChanges as applyEdgeChangesImpl,
  applyNodeChanges as applyNodeChangesImpl,
  useEdgesState as useEdgesStateImpl,
  useNodesState as useNodesStateImpl,
  useReactFlow as useReactFlowImpl,
  BackgroundVariant as BackgroundVariantValue,
  MarkerType as MarkerTypeValue,
  Position as PositionValue,
} from '@xyflow/react';

export const Position = PositionValue;
export const MarkerType = MarkerTypeValue;
export const BackgroundVariant = BackgroundVariantValue;

export interface XYPosition {
  x: number;
  y: number;
}

export interface Dimensions {
  width: number;
  height: number;
}

export interface Viewport extends XYPosition {
  zoom: number;
}

export interface FitViewOptions {
  padding?: number;
  minZoom?: number;
  maxZoom?: number;
  duration?: number;
  includeHiddenNodes?: boolean;
}

export interface Node<Data = unknown> {
  id: string;
  position: XYPosition;
  data?: Data;
  type?: string | null;
  width?: number;
  height?: number;
  measured?: Dimensions;
  parentId?: string | null;
  showDefaultHandles?: boolean;
  selected?: boolean;
  dragging?: boolean;
  hidden?: boolean;
  zIndex?: number;
  sourcePosition?: string;
  targetPosition?: string;
  draggable?: boolean;
  selectable?: boolean;
  connectable?: boolean;
  deletable?: boolean;
}

export interface EdgeMarker {
  type?: string;
  color?: string | null;
  width?: number;
  height?: number;
}

export interface Edge<Data = unknown> {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
  type?: string | null;
  data?: Data;
  label?: string | null;
  selected?: boolean;
  hidden?: boolean;
  animated?: boolean;
  markerStart?: EdgeMarker;
  markerEnd?: EdgeMarker;
  zIndex?: number;
  selectable?: boolean;
  deletable?: boolean;
  reconnectable?: boolean;
  interactionWidth?: number;
}

export interface Connection {
  source?: string | null;
  target?: string | null;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}

export interface NodeChange<Data = unknown> {
  id: string;
  type: string;
  item?: Node<Data>;
  index?: number;
  position?: XYPosition;
  dimensions?: Dimensions;
  selected?: boolean;
  dragging?: boolean;
}

export interface EdgeChange<Data = unknown> {
  id: string;
  type: string;
  item?: Edge<Data>;
  index?: number;
  selected?: boolean;
}

export interface ReactFlowInstance {
  screenToFlowPosition(clientPosition: XYPosition): XYPosition;
  getViewport(): Viewport;
  setViewport(viewport: Viewport, options?: FitViewOptions): Promise<boolean>;
  fitView(options?: FitViewOptions): Promise<boolean>;
  zoomIn(options?: FitViewOptions): Promise<boolean>;
  zoomOut(options?: FitViewOptions): Promise<boolean>;
  setCenter(x: number, y: number, options?: FitViewOptions): Promise<boolean>;
}

export interface NodesState<Data = unknown> {
  nodes: Array<Node<Data>>;
  setNodes(next: Array<Node<Data>> | ((nodes: Array<Node<Data>>) => Array<Node<Data>>)): void;
  onNodesChange(changes: Array<NodeChange<Data>>): void;
}

export interface EdgesState<Data = unknown> {
  edges: Array<Edge<Data>>;
  setEdges(next: Array<Edge<Data>> | ((edges: Array<Edge<Data>>) => Array<Edge<Data>>)): void;
  onEdgesChange(changes: Array<EdgeChange<Data>>): void;
}

export function addEdge<Data = unknown>(connection: Connection, edges: Array<Edge<Data>>): Array<Edge<Data>> {
  return addEdgeImpl(connection as never, edges as never) as Array<Edge<Data>>;
}

export function applyNodeChanges<Data = unknown>(changes: Array<NodeChange<Data>>, nodes: Array<Node<Data>>): Array<Node<Data>> {
  return applyNodeChangesImpl(changes as never, nodes as never) as Array<Node<Data>>;
}

export function applyEdgeChanges<Data = unknown>(changes: Array<EdgeChange<Data>>, edges: Array<Edge<Data>>): Array<Edge<Data>> {
  return applyEdgeChangesImpl(changes as never, edges as never) as Array<Edge<Data>>;
}

export function useNodesState<Data = unknown>(initialNodes: Array<Node<Data>> = []): NodesState<Data> {
  const [nodes, setNodes, onNodesChange] = useNodesStateImpl(initialNodes as never);
  return {
    nodes: nodes as Array<Node<Data>>,
    setNodes: setNodes as NodesState<Data>["setNodes"],
    onNodesChange: onNodesChange as NodesState<Data>["onNodesChange"],
  };
}

export function useEdgesState<Data = unknown>(initialEdges: Array<Edge<Data>> = []): EdgesState<Data> {
  const [edges, setEdges, onEdgesChange] = useEdgesStateImpl(initialEdges as never);
  return {
    edges: edges as Array<Edge<Data>>,
    setEdges: setEdges as EdgesState<Data>["setEdges"],
    onEdgesChange: onEdgesChange as EdgesState<Data>["onEdgesChange"],
  };
}

export function useReactFlow(): ReactFlowInstance {
  return useReactFlowImpl() as ReactFlowInstance;
}
