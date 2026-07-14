// Components
export { default as PortGraphFlow } from './components/PortGraphFlow';
export { default as PortNodeComponent } from './components/PortNodeComponent';

// Types
export * from './types';

// Utilities
export * from './utils/converter';

// Examples
export * from './examples/simpleGraph';

// Scoped (hierarchical) graph view — web analog of reaktor-flow's
// ReaktorFlowScopeView + ReaktorGraphCanvas (C4-style scope-local projection).
export { default as ScopeGraphFlow, useScopeProjection } from './scope/ScopeGraphFlow';
export type { ScopeGraphFlowProps } from './scope/ScopeGraphFlow';
export * from './scope/types';
export * from './scope/projection';
export { layoutScopeGraph, defaultScopeLayout } from './scope/layout';
export type { ScopeLayoutOptions, ScopeLayoutResult } from './scope/layout';
