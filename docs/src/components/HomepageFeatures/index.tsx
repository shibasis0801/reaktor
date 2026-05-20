import type {ReactNode} from 'react';
import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

type FeatureItem = {
  title: string;
  Svg: React.ComponentType<React.ComponentProps<'svg'>>;
  description: ReactNode;
};

const FeatureList: FeatureItem[] = [
  {
    title: 'GRAPH-BASED RUNTIME',
    Svg: require('@site/static/img/graph-blueprint.svg').default,
    description: (
      <>
        A revolutionary model where applications are defined as directed graphs. 
        Every node represents a state or service, and every edge a typed data flow.
      </>
    ),
  },
  {
    title: 'ZERO-COPY FLEXBUFFERS',
    Svg: require('@site/static/img/data-blueprint.svg').default,
    description: (
      <>
        High-performance binary serialization. Access data fields directly in memory 
        without the overhead of parsing or deserialization.
      </>
    ),
  },
  {
    title: 'AGENTIC OPERATIONS',
    Svg: require('@site/static/img/ai-blueprint.svg').default,
    description: (
      <>
        AI agents as first-class citizens. Autonomous nodes that can traverse, 
        mutate, and monitor the graph with full auth governance.
      </>
    ),
  },
];

function Feature({title, Svg, description}: FeatureItem) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <Svg className={styles.featureSvg} role="img" />
      </div>
      <div className="text--center padding-horiz--md">
        <Heading as="h3" style={{fontFamily: 'Space Mono', color: '#1e3a8a'}}>{title}</Heading>
        <p style={{fontFamily: 'Lora'}}>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures(): ReactNode {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
