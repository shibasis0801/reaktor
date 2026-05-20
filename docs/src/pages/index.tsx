import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

type SpecRowProps = {
  label: string;
  value: string;
};

function SpecRow({ label, value }: SpecRowProps) {
  return (
    <tr>
      <td style={{ fontWeight: 'bold', color: 'var(--industrial-orange)' }}>{label}</td>
      <td>{value}</td>
    </tr>
  );
}

export default function Home() {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout
      title={`System Specification`}
      description="Reaktor: The Unreal Engine of App Development. A high-performance Kotlin Multiplatform framework modeled as directed graphs, powered by zero-copy data, and governed by AI agents.">
      
      <div className="home-container">
        <div className="side-banner">
          <span>SYSTEM SPECIFICATION</span>
        </div>
        
        <main className="main-content">
          <div className="industrial-grid">
            
            {/* --- Hero Section --- */}
            <div className="grid-item span-8 hero-box">
              <span className="hero-tagline">// P1 ARCHITECTURE ENGINE</span>
              <h1 className="hero-title">A Digital <br/>Industrial <br/>Revival</h1>
              <p style={{ fontSize: '1.2rem', maxWidth: '600px', marginBottom: '2.5rem' }}>
                Reaktor is an adaptive application framework engineered for automated service orchestration, 
                high-throughput data flows, and cross-platform integrity. Built for speed, precision, and uptime.
              </p>
              <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
                <Link className="button button--primary button--lg" style={{ borderRadius: 0 }} to="/docs/intro">
                  Initialize Documentation
                </Link>
                <Link className="button button--outline button--secondary button--lg" style={{ borderRadius: 0 }} to="https://github.com/reaktor/reaktor">
                  Source Control
                </Link>
              </div>
            </div>

            <div className="grid-item span-4" style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
              <div className="label-box">SERIAL NO. 001 // MARCH 2026</div>
              <table className="spec-table">
                <thead>
                  <tr>
                    <th colSpan={2}>SYSTEM SPECS</th>
                  </tr>
                </thead>
                <tbody>
                  <SpecRow label="CORE" value="KOTLIN MULTIPLATFORM" />
                  <SpecRow label="RUNTIME" value="GRAPH-BASED NODES" />
                  <SpecRow label="DATA" value="ZERO-COPY FLEXBUFFERS" />
                  <SpecRow label="LATENCY" value="< 10ms (EDGE)" />
                  <SpecRow label="GOVERNANCE" value="AI AGENTIC OPS" />
                  <SpecRow label="TARGETS" value="MOBILE / WEB / CLOUD" />
                </tbody>
              </table>
            </div>

            {/* --- Features Grid --- */}
            <div className="grid-item span-6">
              <div className="label-box">PRECISION ENGINEERED</div>
              <h2 style={{ fontSize: '2rem', marginBottom: '1rem' }}>Motion</h2>
              <p>
                A compact application engine built for precise, high-throughput service composition. 
                Move data across languages and runtimes without the friction of traditional serialization.
              </p>
            </div>

            <div className="grid-item span-6">
              <div className="label-box">FACTORY READY</div>
              <h2 style={{ fontSize: '2rem', marginBottom: '1rem' }}>Control</h2>
              <p>
                Fits into current enterprise workflows with minimal setup. Improve delivery speed 
                and efficiency through graph-based introspection and automatic dependency resolution.
              </p>
            </div>

            {/* --- Diagram Section --- */}
            <div className="grid-item span-12" style={{ textAlign: 'center' }}>
              <div className="label-box">SCHEMATIC DIAGRAM // V1.0</div>
              <div style={{ padding: 'clamp(1rem, 5vw, 4rem)', border: '1px solid var(--blueprint-border)', background: 'var(--blueprint-paper-white)' }}>
                <img src="/img/graph-blueprint.svg" style={{ width: '100%', maxWidth: '500px', margin: '0 auto' }} />
                <div style={{ 
                  display: 'grid', 
                  gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', 
                  gap: '1rem',
                  marginTop: '2rem', 
                  fontFamily: 'Space Mono', 
                  fontSize: '0.7rem',
                  textTransform: 'uppercase'
                }}>
                  <div>[01] ConsumerPort</div>
                  <div>[02] NodeLifecycle</div>
                  <div>[03] ProviderPort</div>
                </div>
              </div>
            </div>

            {/* --- Deep Dive Sections --- */}
            <div className="grid-item span-4">
              <h3 style={{ borderBottom: '2px solid var(--industrial-orange)', paddingBottom: '0.5rem', marginBottom: '1rem' }}>Distributed Mesh</h3>
              <p style={{ fontSize: '0.9rem' }}>
                A universal connectivity model where phones, servers, and edge workers are all symmetric peers. 
                Connectivity that never fails, even behind corporate firewalls.
              </p>
            </div>

            <div className="grid-item span-4">
              <h3 style={{ borderBottom: '2px solid var(--industrial-orange)', paddingBottom: '0.5rem', marginBottom: '1rem' }}>Agentic Nodes</h3>
              <p style={{ fontSize: '0.9rem' }}>
                Autonomous agents as first-class citizens. Agents that can query, mutate, and deploy changes 
                within the graph, governed by granular RBAC permissions.
              </p>
            </div>

            <div className="grid-item span-4">
              <h3 style={{ borderBottom: '2px solid var(--industrial-orange)', paddingBottom: '0.5rem', marginBottom: '1rem' }}>Blueprint Editor</h3>
              <p style={{ fontSize: '0.9rem' }}>
                A live runtime control plane. Visualize your entire system, inspect state in real-time, 
                and perform hot-updates without app store review.
              </p>
            </div>

          </div>
        </main>
      </div>
    </Layout>
  );
}
