import React, { useState } from 'react';

type BlueprintCallout = {
  x: string | number;
  y: string | number;
  label: string;
  description: string;
};

type BlueprintProps = {
  title?: string;
  children: React.ReactNode;
  callouts?: BlueprintCallout[];
};

export default function Blueprint({ title, children, callouts = [] }: BlueprintProps) {
  const [activeCallout, setActiveCallout] = useState<number | null>(null);

  return (
    <div className="blueprint-container">
      <div className="blueprint-badge">
        {title || 'Technical Diagram'}
      </div>
      
      <div style={{ position: 'relative', padding: '2rem' }}>
        <div style={{ 
          display: 'flex', 
          justifyContent: 'center', 
          alignItems: 'center',
          minHeight: '200px',
          position: 'relative'
        }}>
          {children}

          {/* Interactive Hotspots */}
          {callouts.map((c, i) => (
            <div
              key={i}
              onMouseEnter={() => setActiveCallout(i)}
              onMouseLeave={() => setActiveCallout(null)}
              style={{
                position: 'absolute',
                top: c.y,
                left: c.x,
                width: '24px',
                height: '24px',
                border: '2px solid var(--industrial-orange)',
                borderRadius: '50%',
                backgroundColor: activeCallout === i ? 'var(--industrial-orange)' : 'transparent',
                cursor: 'help',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '10px',
                fontFamily: 'Space Mono',
                color: activeCallout === i ? '#fff' : 'var(--industrial-orange)',
                fontWeight: 'bold',
                transition: 'all 0.2s ease',
                zIndex: 10
              }}
            >
              {i + 1}
            </div>
          ))}
        </div>

        {/* Callout Detail Panel */}
        {activeCallout !== null && (
          <div style={{
            marginTop: '1.5rem',
            padding: '1rem',
            border: '1px solid var(--industrial-orange)',
            backgroundColor: 'var(--blueprint-highlight)',
            fontFamily: 'Space Mono',
            fontSize: '0.8rem',
            animation: 'fadeIn 0.3s ease'
          }}>
            <strong style={{ color: 'var(--industrial-orange)' }}>
              [{String(activeCallout + 1).padStart(2, '0')}] {callouts[activeCallout].label}:
            </strong>
            <p style={{ margin: '0.5rem 0 0 0', color: 'var(--blueprint-ink)' }}>
              {callouts[activeCallout].description}
            </p>
          </div>
        )}
      </div>

      <div className="blueprint-footer">
        REAKTOR BLUEPRINT ENGINE v1.0 // SCALE: 1:1 // DEPLOYED: MARCH 2026
      </div>

      <style>{`
        @keyframes fadeIn {
          from { opacity: 0; transform: translateY(5px); }
          to { opacity: 1; transform: translateY(0); }
        }
      `}</style>
    </div>
  );
}
