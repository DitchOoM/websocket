import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'intro',
    'getting-started',
    {
      type: 'category',
      label: 'Core Concepts',
      items: [
        'core-concepts/connection-lifecycle',
        'core-concepts/error-handling',
        'core-concepts/compression',
        'core-concepts/reconnection',
      ],
    },
    {
      type: 'category',
      label: 'Guides',
      items: [
        'guides/mqtt-over-websocket',
      ],
    },
    {
      type: 'category',
      label: 'Platforms',
      items: [
        'platforms/jvm',
        'platforms/apple',
        'platforms/linux',
        'platforms/javascript',
      ],
    },
  ],
};

export default sidebars;
