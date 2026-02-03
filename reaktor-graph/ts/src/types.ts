export type PortDirection = 'provider' | 'consumer';

export interface PortType {
  name: string;
  color?: string;
}

export interface Port {
  id: string;
  key: string;
  type: PortType;
  direction: PortDirection;
}

export interface ProviderPort extends Port {
  direction: 'provider';
}

export interface ConsumerPort extends Port {
  direction: 'consumer';
}

export interface PortGraphNode {
  id: string;
  label: string;
  type?: string;
  providers: ProviderPort[];
  consumers: ConsumerPort[];
  position: { x: number; y: number };
  data?: Record<string, unknown>;
}

export interface PortGraphEdge {
  id: string;
  sourceNodeId: string;
  sourcePortId: string;
  targetNodeId: string;
  targetPortId: string;
}

export interface PortGraph {
  id: string;
  name: string;
  nodes: PortGraphNode[];
  edges: PortGraphEdge[];
}

export interface PortTypeRegistry {
  types: Map<string, PortType>;
  register(name: string, color?: string): PortType;
  get(name: string): PortType | undefined;
  canConnect(provider: PortType, consumer: PortType): boolean;
}
