import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { createRequire } from 'node:module';

import type { Plugin } from 'vite';

/** Copied Kotlin exports retain the generated package's dependency context under pnpm. */
export function linkedKotlinDependencies(): Plugin {
  return {
    name: 'dependeasy-linked-kotlin-dependencies',
    async resolveId(id, importer) {
      if (!importer?.includes('/export/') || /^(\.|\/|\0|node:)/.test(id)) return null;
      const manifest = resolve(dirname(importer), '../package.json');
      if (!existsSync(manifest)) return null;
      this.addWatchFile(manifest);
      const metadata: { dependencies?: Record<string, string> } = JSON.parse(readFileSync(manifest, 'utf8'));
      const require = createRequire(manifest);
      const dependency = id.startsWith('@') ? id.split('/').slice(0, 2).join('/') : id.split('/')[0];
      for (const [name, version] of Object.entries(metadata.dependencies ?? {})) {
        if (!version.startsWith('workspace:')) continue;
        const generatedManifest = require.resolve(`${name}/package.json`);
        this.addWatchFile(generatedManifest);
        const generated = JSON.parse(readFileSync(generatedManifest, 'utf8'));
        if (!generated.dependencies?.[dependency]) continue;
        return this.resolve(id, resolve(dirname(generatedManifest), generated.main), { skipSelf: true });
      }
      return null;
    },
  };
}
