import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'WebSocket',
  tagline: 'Kotlin Multiplatform WebSocket client library with RFC 6455 compliance',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  // Ensure static files are served correctly
  staticDirectories: ['static'],

  url: 'https://ditchoom.github.io',
  baseUrl: '/websocket/',

  organizationName: 'DitchOoM',
  projectName: 'websocket',
  trailingSlash: false,

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  // Kotlin Playground for interactive examples
  scripts: [
    {
      src: 'https://unpkg.com/kotlin-playground@1',
      async: true,
    },
  ],

  themes: ['docusaurus-theme-github-codeblock'],

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/DitchOoM/websocket/tree/main/docs/',
          routeBasePath: '/', // Docs at root
        },
        blog: false, // Disable blog
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    // GitHub codeblock configuration
    codeblock: {
      showGithubLink: true,
      githubLinkLabel: 'View on GitHub',
    },
    navbar: {
      title: 'WebSocket',
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          href: 'pathname:///api/websocket/index.html',
          label: 'API Reference',
          position: 'left',
        },
        {
          to: '/autobahn-report',
          label: 'Compliance',
          position: 'left',
        },
        {
          href: 'https://github.com/DitchOoM/websocket',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Documentation',
          items: [
            {
              label: 'Getting Started',
              to: '/getting-started',
            },
          ],
        },
        {
          title: 'Resources',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/DitchOoM/websocket',
            },
            {
              label: 'Maven Central',
              href: 'https://search.maven.org/artifact/com.ditchoom/websocket',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'DitchOoM',
              href: 'https://github.com/DitchOoM',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} DitchOoM. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'groovy', 'java', 'bash'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
