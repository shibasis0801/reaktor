import {useMemo} from 'react';
import type {GraphDefinition} from '../../definition';
import type {BlueprintLayout} from '../types';
import {definitionReferences, type DefinitionPresentation} from '../definition';
import {ReferenceMap} from './ReferenceMap';

export function DefinitionMap({graph,layout,selected,onSelect,presentation}: {graph:GraphDefinition; layout:BlueprintLayout; selected:string|null; onSelect(id:string|null):void; presentation?:DefinitionPresentation}) {
  const reference=useMemo(()=>definitionReferences(graph,presentation),[graph,presentation]);
  const relations=useMemo(()=>new Set(graph.relations.map(relation=>relation.kind)),[graph]);
  return <ReferenceMap layout={layout} nodes={reference.nodes} relations={relations} selected={selected} onSelect={onSelect}
    onOpen={id=>onSelect(id)} memoryKey={`reaktor-example:${graph.id}`} fitKey={reference.revision} noun="nodes"
    label="Reaktor system graph. Cards are nodes; junction cards preserve hyperedge roles. Select a card or port to inspect it."/>;
}
