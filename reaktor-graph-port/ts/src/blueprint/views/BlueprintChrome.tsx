import type { CSSProperties, ReactNode } from 'react';
import { useCameraControls } from './context';
import { useZoomPercent } from './looks';

export function BlueprintLegend({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={['bp-legend', className].filter(Boolean).join(' ')} data-part="legend" role="note">{children}</div>;
}

export function LegendDot({ tone, children }: { tone: string; children: ReactNode }) {
  return <span className="bp-legend__entry" data-part="legend-dot"><span className="bp-legend__dot" style={{ '--bp-entry': tone } as CSSProperties}/>{children}</span>;
}

export function LegendLine({ tone, dash, children }: { tone: string; dash?: string; children: ReactNode }) {
  return <span className="bp-legend__entry" data-part="legend-line">
    <svg className="bp-legend__line" width="18" height="6" aria-hidden="true"><path d="M1 3H17" style={{ stroke: tone, strokeDasharray: dash }}/></svg>
    {children}
  </span>;
}

export function LegendNote({ children }: { children: ReactNode }) {
  return <span className="bp-legend__entry bp-legend__note" data-part="legend-note">{children}</span>;
}

function FitIcon() {
  return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="M2 6V2h4M10 2h4v4M14 10v4h-4M6 14H2v-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/><rect x="5.5" y="5.5" width="5" height="5" rx="1" fill="currentColor"/></svg>;
}

function CentreIcon() {
  return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="5.2" fill="none" stroke="currentColor" strokeWidth="1.6"/><circle cx="8" cy="8" r="1.8" fill="currentColor"/><path d="M8 0.8v2.4M8 12.8v2.4M0.8 8h2.4M12.8 8h2.4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"/></svg>;
}

export function BlueprintCamera({ className, children }: { className?: string; children?: ReactNode }) {
  const camera = useCameraControls();
  const percent = useZoomPercent();
  return <div className={['bp-camera', className].filter(Boolean).join(' ')} data-part="camera" role="toolbar" aria-label="Map camera">
    <button type="button" className="bp-camera__zoom" data-testid="map-zoom-reset" aria-label={`Zoom ${percent} percent, back to 100 percent`} onClick={() => camera.zoomTo(1)}>{percent}%</button>
    <button type="button" className="bp-camera__button" data-testid="map-fit" aria-label="Frame everything" title="Frame everything" disabled={!camera.ready} onClick={camera.fit}><FitIcon/></button>
    <button type="button" className="bp-camera__button" data-testid="map-frame-selection" aria-label="Centre the selection" title="Centre the selection" disabled={!camera.ready || !camera.canCentre} onClick={camera.centre}><CentreIcon/></button>
    {children}
  </div>;
}

export function BlueprintWatermark({ className, title, line }: { className?: string; title: ReactNode; line?: ReactNode }) {
  return <div className={['bp-watermark', className].filter(Boolean).join(' ')} data-part="watermark" aria-hidden="true">
    <div className="bp-watermark__title">{title}</div>
    {line && <div className="bp-watermark__line">{line}</div>}
  </div>;
}
