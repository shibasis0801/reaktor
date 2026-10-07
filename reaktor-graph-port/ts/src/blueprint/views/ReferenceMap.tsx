import {useMemo, useState} from 'react';
import type {BlueprintLayout} from '../types';
import type {ReferenceNode, ReferencePort} from '../referenceGraph';
import {BlueprintMap} from './BlueprintMap';
import {BlueprintCard} from './BlueprintCard';
import {BlueprintPinRow} from './BlueprintPinRow';
import {BlueprintFrame} from './BlueprintFrame';
import {BlueprintCamera} from './BlueprintChrome';
import {useCameraControls} from './context';
import '../atlasTheme.css';

export interface ReferenceMapProps {
  layout: BlueprintLayout;
  nodes: readonly ReferenceNode[];
  memoryKey: string;
  fitKey: string;
  selected: string | null;
  relations: ReadonlySet<string>;
  tone?: string;
  label?: string;
  noun?: string;
  onSelect(id: string | null): void;
  onOpen(id: string, row?: number, port?: ReferencePort): void;
}

function Zoom() {
  const camera = useCameraControls();
  return <><button type="button" className="bp-camera__button" aria-label="Zoom out" onClick={() => camera.zoomBy(0.8)}>−</button><button type="button" className="bp-camera__button" aria-label="Zoom in" onClick={() => camera.zoomBy(1.25)}>+</button></>;
}

function DocumentIcon() {
  return <svg width="13" height="13" viewBox="0 0 16 16"><path d="M8 3.6C6.4 2.6 4.3 2.3 1.8 2.6v10c2.5-.3 4.6 0 6.2 1 1.6-1 3.7-1.3 6.2-1v-10c-2.5-.3-4.6 0-6.2 1zM8 3.6v10" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/></svg>;
}

export function ReferenceMap({layout, nodes, memoryKey, fitKey, selected, relations, tone = 'var(--bp-accent)', label = 'Documentation port graph. Use arrows to move, Enter to inspect and plus or minus to zoom.', noun = 'documents', onSelect, onOpen}: ReferenceMapProps) {
  const byId = useMemo(() => new Map(nodes.map(node => [node.id, node])), [nodes]);
  const [focused,setFocused]=useState<{card:string|null;row:number|null}>({card:null,row:null});
  const owner=(id:string)=>byId.get(id)?.owner??id;
  const focusedHere=focused.card && owner(focused.card)===selected;
  const selectedCard=focusedHere?focused.card:nodes.find(node=>owner(node.id)===selected)?.id??null;
  return <BlueprintMap className="bp-atlas-theme" layout={layout} ready fitKey={fitKey} memoryKey={memoryKey} selected={selected} selectedCard={selectedCard} selectedRow={focusedHere?focused.row:null}
    label={label}
    cardLabel={card => byId.get(card.id)?.title ?? card.id}
    onSelect={(id,detail)=>{setFocused({card:detail.card??null,row:detail.row});onSelect(id&&(byId.has(id)||nodes.some(node=>node.owner===id))?owner(id):null);}}
    pinTooltip={(card,row) => {const node=byId.get(card.id),port=node?.ports?.[row]; return port ? <div><strong>{node!.rows[row]}</strong><div>{node!.title}</div><div>{port.id} · {port.profile}{port.contract?` · ${port.contract} · ${port.polarity}`:''}</div>{layout.links.filter(link=>link.from===card.id&&link.fromPort===port.id||link.to===card.id&&link.toPort===port.id).map(link=><div key={link.id}>{link.family} · {byId.get(link.from===card.id?link.to:link.from)?.title}</div>)}</div> : null;}}
    renderCard={(card, look) => {
      const node = byId.get(card.id);
      return node ? <BlueprintCard card={card} look={look} tone={tone} icon={node.glyph?<span>{node.glyph}</span>:<DocumentIcon/>} title={look==='resource' && node.sourceTitle && nodes.find(value=>value.owner===node.owner)?.id===node.id?node.sourceTitle:node.title} subtitle={node.subtitle}
        folded={node.folded ? `+${node.folded} sections` : undefined}
        row={row => {
          const port=node.ports?.[row];
          const input=port && card.pins.find(pin=>pin.key===port.id&&!pin.provides);
          const output=port && card.pins.find(pin=>pin.key===port.id&&pin.provides);
          return <BlueprintPinRow key={row} card={card} row={row} input={input?{tone,wiring:input.wiring}:null} output={output?{tone,wiring:output.wiring}:null}>
            <span onDoubleClick={event=>{event.stopPropagation();onOpen(node.owner??node.id,row,port);}} title="Double-click to open this port">{node.rows[row]}</span>
          </BlueprintPinRow>;
        }}/> : null;
    }}
    renderFrame={(frame, look) => <BlueprintFrame frame={frame} look={look} tone={tone} icon={<span className="bp-atlas-frame-dot"/>} title={frame.label} count={frame.owner?`${Object.values(layout.cards).filter(card=>card.owner===frame.owner).reduce((n,card)=>n+card.rows,0)} ports`:`${new Set(Object.values(layout.cards).filter(card=>{const node=byId.get(card.id);if(node?.relation)return false;const group=node?.group;let at=group?`reference-region:${group}`:undefined;while(at){if(at===frame.key)return true;at=layout.frames.find(value=>value.key===at)?.parent;}return false;}).map(card=>owner(card.id))).size} ${noun}`}/>}
    wireStyle={(link, state) => {
      if (!relations.has(link.family ?? 'references')) return null;
      const strong = state.lit || state.hovered || owner(link.from) === selected || owner(link.to) === selected;
      if (!strong && (state.look !== 'chapter' || link.cross || !state.nearby)) return null;
      return {tone, width: strong ? 2.6 : 1.4, alpha: strong ? 1 : state.highlighting ? 0.05 : 0.24, glow:strong, marching:strong&&state.focus!=='all', batch:true, label:strong&&link.members.length>1?`×${link.members.length}`:undefined, title: `${link.family} · ${byId.get(link.from)?.title} → ${byId.get(link.to)?.title}`};
    }}
  ><BlueprintCamera><Zoom/></BlueprintCamera></BlueprintMap>;
}
