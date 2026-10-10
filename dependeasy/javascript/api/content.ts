import { prepareClient, withServerRenderer, renderPages, copyStaticDirectories } from '../kernel/content/static-site.ts';
import type { StaticSiteOptions } from '../kernel/content/static-site.ts';
export type { StaticSiteOptions } from '../kernel/content/static-site.ts';

/** The orchestration reads in execution order; Vite and file operations stay in the kernel. */
export async function buildStaticSite(options: StaticSiteOptions): Promise<void> {
  const client = await prepareClient(options);
  await withServerRenderer(options, render => renderPages(options, client, render));
  await copyStaticDirectories(options);
}
