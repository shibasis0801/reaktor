export interface QueryTerm {
  key: string | null;
  value: string;
  negate: boolean;
  pattern: RegExp | null;
}

export type QueryFields<T> = (item: T, key: string | null) => readonly string[] | null;

function tokens(text: string): string[] {
  const found: string[] = [];
  const matcher = /(-?)([\w-]+:)?("([^"]*)"|\S+)/g;
  for (const match of text.matchAll(matcher)) {
    const value = match[4] ?? match[3];
    found.push(`${match[1]}${match[2] ?? ''}${value}`);
  }
  return found;
}

function compile(source: string): RegExp | null {
  try {
    return new RegExp(source, 'i');
  } catch {
    return null;
  }
}

export function parseQuery(text: string, keys: readonly string[]): QueryTerm[] {
  const known = new Set(keys.map(key => key.toLowerCase()));
  return tokens(text.trim()).flatMap(token => {
    const negate = token.startsWith('-') && token.length > 1;
    const body = negate ? token.slice(1) : token;
    const split = body.indexOf(':');
    const candidate = split > 0 ? body.slice(0, split).toLowerCase() : null;
    const key = candidate !== null && known.has(candidate) ? candidate : null;
    const raw = key === null ? body : body.slice(split + 1);
    if (!raw) return [];
    const regex = raw.startsWith('~') && raw.length > 1;
    return [{ key, value: (regex ? raw.slice(1) : raw).toLowerCase(), negate, pattern: regex ? compile(raw.slice(1)) : null }];
  });
}

function termMatches(values: readonly string[], term: QueryTerm): boolean {
  if (term.pattern) return values.some(value => term.pattern!.test(value));
  return values.some(value => value.toLowerCase().includes(term.value));
}

export function matchesQuery<T>(item: T, terms: readonly QueryTerm[], fields: QueryFields<T>): boolean {
  return terms.every(term => {
    const values = fields(item, term.key);
    if (values === null) return term.negate;
    return termMatches(values, term) !== term.negate;
  });
}
