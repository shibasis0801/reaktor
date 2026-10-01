import type { CSSProperties, ReactNode } from 'react';
import { RowHeight } from '../engine';
import type { Card } from '../types';
import { useScene } from './context';
import type { Look } from './looks';

export interface BlueprintCardProps {
  card: Card;
  look: Look;
  tone: string;
  title: ReactNode;
  className?: string;
  icon?: ReactNode;
  subtitle?: ReactNode;
  badges?: ReactNode;
  folded?: ReactNode;
  row?: (row: number) => ReactNode;
}

export function BlueprintCard({ card, look, tone, title, className, icon, subtitle, badges, folded, row }: BlueprintCardProps) {
  const scene = useScene();
  const selected = scene.selected === card.id;
  const dim = scene.highlight.size > 0 && !scene.highlight.has(card.id);
  const style = { '--bp-tone': tone } as CSSProperties;
  const classes = ['bp-card', `bp-card--${look}`, className].filter(Boolean).join(' ');
  const state = { 'data-selected': selected || undefined, 'data-dim': dim || undefined, 'data-look': look };
  if (look === 'domain') return <div className={classes} style={style} data-part="card" {...state}/>;
  if (look === 'resource') return <div className={classes} style={style} data-part="card" {...state}><div className="bp-card__far" data-part="title">{title}</div></div>;
  return <div className={classes} style={style} data-part="card" {...state}>
    <div className="bp-card__header" data-part="header">
      {icon && <span className="bp-card__icon" data-part="icon" aria-hidden="true">{icon}</span>}
      <span className="bp-card__title" data-part="title">{title}</span>
      {badges && <span className="bp-card__badges" data-part="badges">{badges}</span>}
    </div>
    {(subtitle || folded) && <div className="bp-card__meta">
      <span className="bp-card__subtitle" data-part="subtitle">{subtitle}</span>
      {folded && <span className="bp-card__folded" data-part="folded">{folded}</span>}
    </div>}
    {row && card.rows > 0 && <div className="bp-card__rows" data-part="rows" style={{ height: card.rows * RowHeight }}>
      {Array.from({ length: card.rows }, (_, index) => row(index))}
    </div>}
  </div>;
}
