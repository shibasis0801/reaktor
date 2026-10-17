/** Fail on values JSON would silently drop, round or replace. */
export function encode(value: unknown): string {
  const result = JSON.stringify(value, (_, current: unknown) => {
    if (typeof current === 'number' && (!Number.isFinite(current) || (Number.isInteger(current) && !Number.isSafeInteger(current))))
      throw new TypeError('Interop numbers must be finite and integers safe; encode large integers as strings');
    if (current === undefined || typeof current === 'function' || typeof current === 'symbol' || typeof current === 'bigint')
      throw new TypeError('Interop values must be JSON data');
    return current;
  });
  if (result === undefined) throw new TypeError('Interop values must be JSON data');
  return result;
}

export function decode(source: string): unknown {
  const value: unknown = JSON.parse(source);
  encode(value);
  return value;
}
