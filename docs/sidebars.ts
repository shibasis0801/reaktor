import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  tutorialSidebar: [
    {
      type: 'doc',
      id: 'intro',
      label: '00 // INTRODUCTION',
    },
    {
      type: 'doc',
      id: 'getting-started',
      label: '01 // GETTING STARTED',
    },
    {
      type: 'category',
      label: 'VOL I // THE ENGINE',
      collapsed: false,
      items: [
        'architecture/three-layers',
        'architecture/graph-runtime',
        'architecture/flexbuffers',
        'architecture/service-layer',
        'architecture/ffi',
      ],
    },
    {
      type: 'category',
      label: 'VOL II // PLATFORM FEATURES',
      collapsed: false,
      items: [
        'features/blueprint',
        'features/ai-intelligence',
        'features/five-runtimes',
        'features/mesh',
        'features/actors',
        'features/agents',
        'data/reaktor-db',
      ],
    },
    {
      type: 'category',
      label: 'VOL III // FRONTENDS',
      collapsed: true,
      items: [
        'frontend/react-native',
        'frontend/flutter',
        'frontend/navigation',
        'frontend/react-compose',
      ],
    },
    {
      type: 'category',
      label: 'VOL IV // STRATEGY & ROADMAP',
      collapsed: true,
      items: [
        'roadmap/priority-checklist',
        'roadmap/foundation',
        'strategy/philosophy',
        'strategy/positioning',
        'strategy/licensing',
      ],
    },
    {
      type: 'category',
      label: 'APPENDIX // RESOURCES',
      collapsed: true,
      items: [
        'resources/design-system',
        'resources/current-status',
        'resources/patterns',
        'resources/testing-shadow',
        'resources/case-study-simulation',
      ],
    },
  ],
};

export default sidebars;
