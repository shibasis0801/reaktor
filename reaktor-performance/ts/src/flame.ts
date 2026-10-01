export interface FlameInput {
  name: string;
  location?: string;
  totalMs: number;
  selfMs?: number;
  children: FlameInput[];
}

export interface FlameOptions {
  title: string;
  subtitle?: string;
  width?: number;
  rowHeight?: number;
  minimumWidth?: number;
}

const escape = (text: string): string => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function tone(node: FlameInput): string {
  const where = `${node.location ?? ''} ${node.name}`;
  if (/^\((garbage collector|program|idle)\)/.test(node.name)) return '#b8b8b8';
  if (/blueprint|reaktor-graph-port/.test(where)) return '#9a7fd1';
  if (/src\/manna|src\/App|src\/graph/.test(where)) return '#5fae84';
  if (/@xyflow|xyflow|d3-/.test(where)) return '#e39b55';
  if (/react-dom|scheduler|react\//.test(where)) return '#5b9bd5';
  if (/reaktor-reaktor|reaktor-graph\//.test(where)) return '#c7a24a';
  if (/elk/.test(where)) return '#d4746a';
  if (/native|\(anonymous\)/.test(where) || !node.location) return '#9fb4c7';
  return '#c9c56a';
}

export function flameSvg(root: FlameInput, options: FlameOptions): string {
  const width = options.width ?? 1600;
  const row = options.rowHeight ?? 17;
  const minimum = options.minimumWidth ?? 0.4;
  const total = root.totalMs || 1;
  const scale = (width - 20) / total;
  const boxes: string[] = [];
  let depth = 0;
  const visit = (node: FlameInput, x: number, level: number) => {
    const span = node.totalMs * scale;
    if (span < minimum) return;
    depth = Math.max(depth, level);
    const y = 46 + level * row;
    const label = node.location ? `${node.name} ${node.location}` : node.name;
    const tip = `${label}\n${node.totalMs.toFixed(1)} ms total${node.selfMs !== undefined ? `, ${node.selfMs.toFixed(1)} ms self` : ''} (${((node.totalMs / total) * 100).toFixed(1)}%)`;
    const text = span > 36 ? `<text x="${(x + 3).toFixed(1)}" y="${(y + row - 5).toFixed(1)}" clip-path="url(#c${boxes.length})">${escape(label)}</text>` : '';
    const clip = span > 36 ? `<clipPath id="c${boxes.length}"><rect x="${x.toFixed(1)}" y="${y}" width="${(span - 2).toFixed(1)}" height="${row}"/></clipPath>` : '';
    boxes.push(`<g><title>${escape(tip)}</title>${clip}<rect x="${x.toFixed(1)}" y="${y}" width="${Math.max(span - 0.5, 0.5).toFixed(1)}" height="${row - 1}" rx="2" fill="${tone(node)}"/>${text}</g>`);
    let cursor = x;
    for (const child of node.children) {
      visit(child, cursor, level + 1);
      cursor += child.totalMs * scale;
    }
  };
  visit(root, 10, 0);
  const height = 46 + (depth + 1) * row + 30;
  const legend = [['app (src/manna)', '#5fae84'], ['blueprint kit', '#9a7fd1'], ['xyflow / d3', '#e39b55'], ['react', '#5b9bd5'], ['kotlin/js graph', '#c7a24a'], ['elk', '#d4746a'], ['native / runtime', '#9fb4c7']]
    .map(([label, color], index) => `<rect x="${10 + index * 160}" y="${height - 20}" width="10" height="10" fill="${color}"/><text x="${24 + index * 160}" y="${height - 11}">${escape(label)}</text>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="ui-monospace, Menlo, monospace" font-size="11">
<rect width="100%" height="100%" fill="#ffffff"/>
<text x="10" y="18" font-size="14" font-weight="bold">${escape(options.title)}</text>
<text x="10" y="36" fill="#555">${escape(options.subtitle ?? `${total.toFixed(1)} ms sampled; width is total time; hover a box for details`)}</text>
${boxes.join('\n')}
${legend}
</svg>
`;
}
