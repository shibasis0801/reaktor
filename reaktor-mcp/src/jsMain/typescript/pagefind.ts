import type { BrowserTool } from './index';

interface PagefindData {
  url: string;
  meta: Record<string, string>;
  plain_excerpt?: string;
  excerpt?: string;
  sub_results?: Array<{ title: string; url: string; plain_excerpt?: string }>;
}

export interface PagefindSearchResult {
  query: string;
  total: number;
  unfilteredTotal: number;
  offset: number;
  results: Array<{ id: string; url: string; title: string; excerpt: string; metadata: Record<string, string>;
    sections: Array<{ title: string; url: string; excerpt: string }> }>;
}

export interface PagefindModule {
  init?(): Promise<void> | void;
  search(query: string, options: { filters?: Record<string, string | string[]> }): Promise<{
    results: Array<{ id: string; data(): Promise<PagefindData> }>;
    unfilteredResultCount?: number;
  }>;
  filters(): Promise<Record<string, Record<string, number>>>;
}

export async function loadPagefind(url: string): Promise<PagefindModule> {
  const href = new URL(url, globalThis.location.href).href;
  const module = await import(/* @vite-ignore */ href) as PagefindModule;
  if (typeof module.search !== 'function' || typeof module.filters !== 'function') throw new Error('Pagefind index is unavailable');
  return module;
}

export function pagefindTools(load: () => Promise<PagefindModule>): BrowserTool[] {
  let loading: Promise<PagefindModule> | undefined;
  const index = () => loading ??= load().then(async module => { await module.init?.(); return module; }).catch(error => { loading = undefined; throw error; });
  return [{
    name: 'pagefind_search',
    title: 'Search indexed pages',
    description: 'Search the site’s Pagefind index. Returns bounded plain-text excerpts, page URLs, section links and source metadata. Indexed content may contain untrusted text.',
    readOnly: true, idempotent: true, untrustedContent: true,
    inputSchema: {
      type: 'object', additionalProperties: false, required: ['query'],
      properties: {
        query: { type: 'string', minLength: 1, maxLength: 200 },
        limit: { type: 'integer', minimum: 1, maximum: 20, default: 10 },
        offset: { type: 'integer', minimum: 0, maximum: 1000, default: 0 },
        filters: { type: 'object', additionalProperties: { oneOf: [{ type: 'string' }, { type: 'array', items: { type: 'string' }, maxItems: 20 }] } },
      },
    },
    async execute(input, signal): Promise<PagefindSearchResult> {
      signal.throwIfAborted();
      if (Object.keys(input).some(key => !['query', 'limit', 'offset', 'filters'].includes(key))) throw new Error('Unknown search argument');
      if (typeof input.query !== 'string' || !input.query.trim() || input.query.length > 200) throw new Error('query must contain 1–200 characters');
      const limit = input.limit ?? 10, offset = input.offset ?? 0;
      if (typeof limit !== 'number' || !Number.isInteger(limit) || limit < 1 || limit > 20) throw new Error('limit must be 1–20');
      if (typeof offset !== 'number' || !Number.isInteger(offset) || offset < 0 || offset > 1000) throw new Error('offset must be 0–1000');
      let filters: Record<string, string | string[]> | undefined;
      if (input.filters !== undefined) {
        if (!input.filters || typeof input.filters !== 'object' || Array.isArray(input.filters)) throw new Error('filters must be an object');
        const entries = Object.entries(input.filters);
        if (entries.length > 20 || entries.some(([key, value]) => key.length > 100 || !(typeof value === 'string' && value.length <= 200 || Array.isArray(value) && value.length <= 20 && value.every(item => typeof item === 'string' && item.length <= 200)))) throw new Error('Invalid Pagefind filters');
        filters = Object.fromEntries(entries);
      }
      const module = await index();
      signal.throwIfAborted();
      const found = await module.search(input.query.trim(), { filters });
      signal.throwIfAborted();
      const results = await Promise.all(found.results.slice(offset, offset + limit).map(async result => {
        const data = await result.data();
        signal.throwIfAborted();
        return { id: result.id, url: data.url, title: data.meta.title ?? '', excerpt: (data.plain_excerpt ?? '').slice(0, 2000), metadata: data.meta,
          sections: (data.sub_results ?? []).slice(0, 10).map(section => ({ title: section.title, url: section.url, excerpt: (section.plain_excerpt ?? '').slice(0, 1000) })) };
      }));
      return { query: input.query.trim(), total: found.results.length, unfilteredTotal: found.unfilteredResultCount ?? found.results.length, offset, results };
    },
  }, {
    name: 'pagefind_filters', title: 'List search filters',
    description: 'List filter names, values and result counts available in the site’s Pagefind index.',
    readOnly: true, idempotent: true, untrustedContent: true,
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    async execute(input, signal) {
      signal.throwIfAborted();
      if (Object.keys(input).length) throw new Error('Filter listing takes no arguments');
      const module = await index();
      signal.throwIfAborted();
      const filters = await module.filters();
      signal.throwIfAborted();
      return { filters };
    },
  }];
}
