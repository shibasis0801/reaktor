import { decodeLines, type RawSourceMap } from './sourcemap.ts';

export interface SourceShare {
  source: string;
  bytes: number;
}

export interface PackageShare {
  name: string;
  bytes: number;
  share: number;
  sources: number;
}

export function sourceBytes(code: string, map: RawSourceMap): SourceShare[] {
  if (map.sections) {
    const totals = new Map<string, number>();
    const lines = code.split('\n');
    map.sections.forEach((section, index) => {
      const next = map.sections![index + 1]?.offset ?? { line: lines.length, column: 0 };
      const slice = lines.slice(section.offset.line, next.line + 1);
      if (slice.length > 0) {
        slice[slice.length - 1] = slice[slice.length - 1].slice(0, next.line === section.offset.line ? next.column - section.offset.column : next.column);
        slice[0] = slice[0].slice(section.offset.column);
      }
      for (const item of sourceBytes(slice.join('\n'), section.map)) totals.set(item.source, (totals.get(item.source) ?? 0) + item.bytes);
    });
    return [...totals].map(([source, bytes]) => ({ source, bytes })).sort((a, b) => b.bytes - a.bytes);
  }
  const lines = code.split('\n');
  const decoded = decodeLines(map.mappings);
  const root = map.sourceRoot ? map.sourceRoot.replace(/\/?$/, '/') : '';
  const totals = new Map<string, number>();
  const add = (source: string, bytes: number) => { if (bytes > 0) totals.set(source, (totals.get(source) ?? 0) + bytes); };
  lines.forEach((line, index) => {
    const segments = decoded[index] ?? [];
    if (segments.length === 0) { add('(unmapped)', line.length + 1); return; }
    add('(unmapped)', segments[0].column);
    segments.forEach((segment, position) => {
      const end = position + 1 < segments.length ? segments[position + 1].column : line.length + 1;
      const source = segment.source >= 0 ? `${root}${map.sources[segment.source] ?? '(unknown)'}` : '(unmapped)';
      add(source, end - segment.column);
    });
  });
  return [...totals].map(([source, bytes]) => ({ source, bytes })).sort((a, b) => b.bytes - a.bytes);
}

export function packageOf(source: string): string {
  const clean = source.replace(/^webpack:\/\/\/?/, '').replace(/^(\.\.\/)+/, '').replace(/\\/g, '/');
  const modules = clean.lastIndexOf('node_modules/');
  if (modules >= 0) {
    const rest = clean.slice(modules + 'node_modules/'.length).split('/');
    const name = rest[0].startsWith('@') ? `${rest[0]}/${rest[1]}` : rest[0];
    return name;
  }
  const reaktor = /(?:^|\/)reaktor\/([^/]+)\/(?:ts\/)?(export|src)?/.exec(clean);
  if (reaktor) return reaktor[2] === 'export' ? `${reaktor[1]} (Kotlin/JS)` : reaktor[1];
  if (clean.startsWith('(')) return clean;
  const app = /(?:^|\/)src\/([^/]+)\//.exec(clean);
  if (app) return `src/${app[1]}`;
  const file = /(?:^|\/)src\/([^/]+)$/.exec(clean);
  if (file) return 'src (root files)';
  return clean.split('/').slice(0, 2).join('/');
}

export function packageShares(sources: readonly SourceShare[]): PackageShare[] {
  const total = sources.reduce((sum, item) => sum + item.bytes, 0) || 1;
  const grouped = new Map<string, { bytes: number; sources: number }>();
  for (const item of sources) {
    const name = packageOf(item.source);
    const entry = grouped.get(name) ?? { bytes: 0, sources: 0 };
    entry.bytes += item.bytes;
    entry.sources += 1;
    grouped.set(name, entry);
  }
  return [...grouped].map(([name, entry]) => ({ name, bytes: entry.bytes, share: Math.round((entry.bytes / total) * 1000) / 1000, sources: entry.sources }))
    .sort((a, b) => b.bytes - a.bytes);
}

export interface HtmlEntry {
  scripts: string[];
  preloads: string[];
  styles: string[];
}

export function htmlEntry(html: string): HtmlEntry {
  const scripts = [...html.matchAll(/<script[^>]*type="module"[^>]*src="([^"]+)"/g)].map(match => match[1]);
  const preloads = [...html.matchAll(/<link[^>]*rel="modulepreload"[^>]*href="([^"]+)"/g)].map(match => match[1]);
  const styles = [...html.matchAll(/<link[^>]*rel="stylesheet"[^>]*href="([^"]+)"/g)].map(match => match[1]);
  return { scripts, preloads, styles };
}

export function staticImports(code: string): string[] {
  const found = new Set<string>();
  for (const match of code.matchAll(/(?:^|[;}\n])\s*import\s*(?:[\w*{}\s,$]+from\s*)?["']([^"']+)["']/g)) found.add(match[1]);
  return [...found];
}

export function dynamicImports(code: string): string[] {
  const found = new Set<string>();
  for (const match of code.matchAll(/import\(\s*["']([^"']+)["']\s*\)/g)) found.add(match[1]);
  return [...found];
}
