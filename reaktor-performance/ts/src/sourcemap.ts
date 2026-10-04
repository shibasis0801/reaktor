export interface RawSourceMap {
  version: number;
  sources: string[];
  names?: string[];
  mappings: string;
  sourceRoot?: string;
  sections?: Array<{ offset: { line: number; column: number }; map: RawSourceMap }>;
}

export interface OriginalPosition {
  source: string | null;
  line: number | null;
  column: number | null;
  name: string | null;
}

export interface Segment {
  column: number;
  source: number;
  line: number;
  sourceColumn: number;
  name: number;
}

export interface SourceMapIndex {
  lookup(line: number, column: number): OriginalPosition;
}

const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const decode = new Int8Array(128).fill(-1);
for (let index = 0; index < alphabet.length; index += 1) decode[alphabet.charCodeAt(index)] = index;

export function decodeLines(mappings: string): Segment[][] {
  const lines: Segment[][] = [];
  let line: Segment[] = [];
  let source = 0;
  let sourceLine = 0;
  let sourceColumn = 0;
  let name = 0;
  let cursor = 0;
  const length = mappings.length;
  while (cursor <= length) {
    const char = cursor < length ? mappings.charCodeAt(cursor) : 59;
    if (char === 59) {
      lines.push(line);
      line = [];
      cursor += 1;
      if (cursor > length) break;
      continue;
    }
    if (char === 44) { cursor += 1; continue; }
    const fields: number[] = [];
    let column = 0;
    while (cursor < length) {
      const code = mappings.charCodeAt(cursor);
      if (code === 44 || code === 59) break;
      let value = 0;
      let shift = 0;
      let digit = 0;
      do {
        digit = decode[mappings.charCodeAt(cursor)];
        cursor += 1;
        value += (digit & 31) << shift;
        shift += 5;
      } while (digit & 32);
      fields.push(value & 1 ? -(value >>> 1) : value >>> 1);
    }
    column = fields[0] ?? 0;
    const previous = line.length > 0 ? line[line.length - 1].column : 0;
    if (fields.length >= 4) {
      source += fields[1];
      sourceLine += fields[2];
      sourceColumn += fields[3];
      if (fields.length >= 5) name += fields[4];
      line.push({ column: previous + column, source, line: sourceLine, sourceColumn, name: fields.length >= 5 ? name : -1 });
    } else line.push({ column: previous + column, source: -1, line: -1, sourceColumn: -1, name: -1 });
  }
  return lines;
}

export function sourceMapIndex(map: RawSourceMap): SourceMapIndex {
  if (map.sections) {
    const sections = map.sections.map(section => ({ offset: section.offset, index: sourceMapIndex(section.map) }));
    return {
      lookup(line, column) {
        let chosen = sections[0];
        for (const section of sections) {
          if (section.offset.line < line || (section.offset.line === line && section.offset.column <= column)) chosen = section;
        }
        if (!chosen) return { source: null, line: null, column: null, name: null };
        const relativeColumn = line === chosen.offset.line ? column - chosen.offset.column : column;
        return chosen.index.lookup(line - chosen.offset.line, relativeColumn);
      },
    };
  }
  const lines = decodeLines(map.mappings);
  const root = map.sourceRoot ? map.sourceRoot.replace(/\/?$/, '/') : '';
  return {
    lookup(line, column) {
      const segments = lines[line];
      if (!segments || segments.length === 0) return { source: null, line: null, column: null, name: null };
      let low = 0;
      let high = segments.length - 1;
      let found = -1;
      while (low <= high) {
        const middle = (low + high) >> 1;
        if (segments[middle].column <= column) { found = middle; low = middle + 1; } else high = middle - 1;
      }
      const segment = found >= 0 ? segments[found] : segments[0];
      if (segment.source < 0) return { source: null, line: null, column: null, name: null };
      return {
        source: `${root}${map.sources[segment.source] ?? ''}`,
        line: segment.line + 1,
        column: segment.sourceColumn,
        name: segment.name >= 0 ? map.names?.[segment.name] ?? null : null,
      };
    },
  };
}

export interface MappedFrame {
  functionName: string;
  url: string;
  lineNumber: number;
  columnNumber: number;
}

export function symbolicator(load: (url: string) => RawSourceMap | null): (frame: MappedFrame) => { name: string; source: string | null; line: number | null } | null {
  const indexes = new Map<string, SourceMapIndex | null>();
  const cache = new Map<string, { name: string; source: string | null; line: number | null } | null>();
  return frame => {
    if (!frame.url || frame.lineNumber < 0) return null;
    const key = `${frame.url}:${frame.lineNumber}:${frame.columnNumber}`;
    if (cache.has(key)) return cache.get(key)!;
    let index = indexes.get(frame.url);
    if (index === undefined) {
      const map = load(frame.url);
      index = map ? sourceMapIndex(map) : null;
      indexes.set(frame.url, index);
    }
    const found = index ? index.lookup(frame.lineNumber, frame.columnNumber) : null;
    const mapped = found && found.source ? { name: found.name ?? frame.functionName, source: found.source.replace(/^(\.\.\/)+/, ''), line: found.line } : null;
    cache.set(key, mapped);
    return mapped;
  };
}
