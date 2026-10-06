import { useEffect, type CSSProperties, type PointerEvent, type ReactNode } from 'react';
import { RowHeight } from '../engine';
import type { Card, PinWiring } from '../types';
import { selectedCardOf, useScene, useSelection } from './context';

export interface PinTone {
  tone: string;
  wiring?: PinWiring;
  action?: () => void;
  label?: string;
  pressed?: boolean;
}

export interface BlueprintPinRowProps {
  card: Card;
  row: number;
  children: ReactNode;
  input?: PinTone | null;
  output?: PinTone | null;
  className?: string;
}

function Pin({ side, pin }: { side: 'in' | 'out'; pin: PinTone }) {
  if (pin.action) return <button type="button" className="bp-pin bp-pin--action nodrag nopan" data-part="pin" data-side={side} data-wiring={pin.wiring ?? 'unlinked'} style={{ '--bp-pin': pin.tone } as CSSProperties} aria-label={pin.label} title={pin.label} aria-pressed={pin.pressed} onPointerDown={event => event.stopPropagation()} onClick={event => { event.stopPropagation(); pin.action?.(); }}/>;
  return <span className="bp-pin" data-part="pin" data-side={side} data-wiring={pin.wiring ?? 'linked'} style={{ '--bp-pin': pin.tone } as CSSProperties}/>;
}

export function BlueprintPinRow({ card, row, children, input, output, className }: BlueprintPinRowProps) {
  const scene = useScene();
  const selected = useSelection(selection => selectedCardOf(selection) === card.id && selection.selectedRow === row);
  const enter = (event: PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === 'touch' || (!input && !output) || scene.rest.moving) return;
    scene.pins.set({ card: card.id, row, x: event.clientX, y: event.clientY, ready: false });
  };
  const leave = () => {
    const current = scene.pins.get();
    if (current && current.card === card.id && current.row === row) scene.pins.set(null);
  };
  useEffect(() => () => {
    const current = scene.pins.get();
    if (current && current.card === card.id && current.row === row) scene.pins.set(null);
  }, [scene.pins, card.id, row]);
  return <div
    className={['bp-row', className].filter(Boolean).join(' ')}
    style={{ top: row * RowHeight, height: RowHeight }}
    data-part="row"
    data-row={row}
    data-selected={selected || undefined}
    onPointerEnter={enter}
    onPointerLeave={leave}
  >
    {input && <Pin side="in" pin={input}/>}
    <span className="bp-row__label" data-part="row-label">{children}</span>
    {output && <Pin side="out" pin={output}/>}
  </div>;
}
