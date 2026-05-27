import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
  title: 'Reaktor',
  tagline: 'Reaktor documentation map',
  favicon: 'img/favicon.svg',

  // Set the production url of your site here
  url: 'https://docs.reaktor.build',
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/',

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'reaktor',
  projectName: 'reaktor-docs',

  onBrokenLinks: 'throw',

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  markdown: {
    mermaid: true,
  },
  themes: ['@docusaurus/theme-mermaid'],
  plugins: [],

  presets: [
    [
      'classic',
      {
        docs: false,
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/logo.svg',
    metadata: [
      {name: 'keywords', content: 'reaktor, documentation, roadmap, architecture, implementation, workbench, graph kernel'},
      {name: 'twitter:card', content: 'summary_large_image'},
      {name: 'twitter:title', content: 'Reaktor Docs'},
      {name: 'twitter:description', content: 'Find the right Reaktor document.'},
      {name: 'og:type', content: 'website'},
      {name: 'og:title', content: 'Reaktor Docs'},
      {name: 'og:description', content: 'The docs are grouped by decision type so you can start with the roadmap, pressure-test the idea, dive into architecture, or execute a specific subsystem.'},
    ],
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Reaktor',
      logo: {
        alt: 'Reaktor Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          to: '/',
          position: 'left',
          label: 'Documentation',
        },
        {
          href: 'https://github.com/reaktor/reaktor',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [],
      copyright: `Copyright © ${new Date().getFullYear()} Bestbuds Ventures.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'cpp', 'typescript', 'dart', 'swift'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
