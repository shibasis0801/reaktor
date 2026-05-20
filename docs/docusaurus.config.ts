import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
  title: 'Reaktor',
  tagline: 'The Unreal Engine of App & Server Development',
  favicon: 'img/favicon.svg',

  // Future flags, see https://docusaurus.io/docs/api/docusaurus-config#future
  future: {
    v4: true, // Improve compatibility with the upcoming Docusaurus v4
  },

  // Set the production url of your site here
  url: 'https://docs.reaktor.build',
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/',

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'reaktor', // Usually your GitHub org/user name.
  projectName: 'reaktor-docs', // Usually your repo name.

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
  plugins: [
    [
      require.resolve("@cmfcmf/docusaurus-search-local"),
      {
        // Options here
      },
    ],
  ],

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          // Please change this to your repo.
          // Remove this to remove the "edit this page" links.
          editUrl:
            'https://github.com/reaktor/reaktor-docs/tree/main/',
        },
        blog: {
          showReadingTime: true,
          feedOptions: {
            type: ['rss', 'atom'],
            xslt: true,
          },
          // Please change this to your repo.
          // Remove this to remove the "edit this page" links.
          editUrl:
            'https://github.com/reaktor/reaktor-docs/tree/main/',
          // Useful options to enforce blogging best practices
          onInlineTags: 'warn',
          onInlineAuthors: 'warn',
          onUntruncatedBlogPosts: 'warn',
        },
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    // Replace with your project's social card
    image: 'img/logo.svg',
    metadata: [
      {name: 'keywords', content: 'reaktor, kotlin multiplatform, kmp, app engine, distributed systems, zero-copy, flexbuffers, blueprint editor, ai agents, software architecture'},
      {name: 'twitter:card', content: 'summary_large_image'},
      {name: 'twitter:title', content: 'Reaktor | The Unreal Engine of App Development'},
      {name: 'twitter:description', content: 'Directed graphs, zero-copy data, and AI-native intelligence for modern application development.'},
      {name: 'og:type', content: 'website'},
      {name: 'og:title', content: 'Reaktor | System Specification'},
      {name: 'og:description', content: 'A revolutionary Kotlin Multiplatform framework that models apps and services as directed graphs.'},
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
          type: 'docSidebar',
          sidebarId: 'tutorialSidebar',
          position: 'left',
          label: 'Documentation',
        },
        {to: '/blog', label: 'Blog', position: 'left'},
        {
          href: 'https://github.com/reaktor/reaktor',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {
              label: 'Introduction',
              to: '/docs/intro',
            },
            {
              label: 'Architecture',
              to: '/docs/architecture/three-layers',
            },
          ],
        },
        {
          title: 'Community',
          items: [
            {
              label: 'Discord',
              href: 'https://discord.gg/reaktor',
            },
            {
              label: 'X',
              href: 'https://x.com/reaktor',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'Blog',
              to: '/blog',
            },
            {
              label: 'GitHub',
              href: 'https://github.com/reaktor/reaktor',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Bestbuds Ventures. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'cpp', 'typescript', 'dart', 'swift'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
