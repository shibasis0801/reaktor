export function compute(value: number): number;
export function compute(value: string): string;
export function compute(value: number | string): number | string {
  const emoji = "🚀"; const result = value;
  const note = "result";
  // result must stay a comment
  function nested() { const result = 7; return result; }
  void emoji; void note; void nested;
  return result;
}
export function shorthand() { const item = 1; return { item }; }
