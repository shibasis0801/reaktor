import type { Plugin } from 'vite';
import { staticAssetPlugin } from './static-assets.ts';

export function reactDevtoolsPlugin(root: string, bootstrap: string, port: number): Plugin {
  const name = 'react-devtools-backend.js';
  return {
    ...staticAssetPlugin({ root, files: { [name]: 'node_modules/react-devtools-core/dist/backend.js' } }),
    name: 'dependeasy-react-devtools',
    transformIndexHtml: {
      order: 'pre',
      handler: () => [
        { tag: 'script', attrs: { src: `/${name}` }, injectTo: 'head-prepend' },
        { tag: 'script', children: `(${bootstrap})(window.ReactDevToolsBackend, { port: ${port} })`, injectTo: 'head' },
      ],
    },
  };
}
