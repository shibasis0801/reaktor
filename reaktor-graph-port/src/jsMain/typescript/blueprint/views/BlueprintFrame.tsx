import { useContext, type CSSProperties, type ReactNode } from 'react';
import type { Frame } from '../types';
import { FramePartContext, useRowsOpen, useSelection } from './context';
import type { Look } from './looks';

export interface BlueprintFrameProps {
  frame: Frame;
  look: Look;
  tone: string;
  title: ReactNode;
  className?: string;
  icon?: ReactNode;
  count?: ReactNode;
  detail?: ReactNode;
  badges?: ReactNode;
}

export function BlueprintFrame({ frame, look, tone, title, className, icon, count, detail, badges }: BlueprintFrameProps) {
  const part = useContext(FramePartContext);
  const owner = frame.owner;
  const selected = useSelection(selection => owner !== undefined && selection.selected === owner);
  const dim = useSelection(selection => owner !== undefined && selection.highlight.size > 0 && !selection.highlight.has(owner));
  const nested = frame.parent !== undefined;
  const near = useRowsOpen(`frame:${frame.key}`);
  const style = { '--bp-tone': tone } as CSSProperties;
  const classes = ['bp-frame', `bp-frame--${look}`, nested && 'bp-frame--nested', className].filter(Boolean).join(' ');
  const state = { 'data-muted': frame.muted || undefined, 'data-loose': frame.loose || undefined, 'data-selected': selected || undefined, 'data-dim': dim || undefined };
  if (part === 'banner') return <div className={['bp-frame__banner', `bp-frame__banner--${look}`, className].filter(Boolean).join(' ')} style={style} data-part="banner">
    <span className="bp-frame__name" data-part="title">{title}</span>
    {count && <span className="bp-frame__tally" data-part="count">{count}</span>}
  </div>;
  if (look !== 'chapter' || (nested && !near && !selected)) return <div className={classes} style={style} data-part="frame" {...state}/>;
  return <div className={classes} style={style} data-part="frame" {...state}>
    <div className="bp-frame__header" data-part="header">
      {icon && <span className="bp-frame__icon" data-part="icon" aria-hidden="true">{icon}</span>}
      <span className="bp-frame__title" data-part="title">{title}</span>
      {count && <span className="bp-frame__count" data-part="count">{count}</span>}
      {detail && <span className="bp-frame__detail" data-part="detail">{detail}</span>}
      {badges && <span className="bp-frame__badges" data-part="badges">{badges}</span>}
    </div>
  </div>;
}
