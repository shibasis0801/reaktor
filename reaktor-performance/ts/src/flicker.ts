export interface FlickerCrop {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface FlickerInput {
  frames: string[];
  crop: FlickerCrop;
  width: number;
  background: [number, number, number];
  contentDelta?: number;
  globalDip?: number;
  cellDip?: number;
  columns?: number;
  rows?: number;
}

export interface FlickerFrame {
  index: number;
  coverage: number;
  change: number;
  dip: number;
  cells: number;
  flagged: boolean;
  reason: string;
}

export interface FlickerAnalysis {
  frames: FlickerFrame[];
  flagged: number[];
  flickerFrames: number;
  cellEvents: number;
  worstDip: number;
  coverageMin: number;
  coverageMax: number;
}

export async function analyzeFramesInPage(input: FlickerInput): Promise<FlickerAnalysis> {
  const contentDelta = input.contentDelta ?? 14;
  const globalDip = input.globalDip ?? 0.025;
  const cellDip = input.cellDip ?? 0.3;
  const columns = input.columns ?? 8;
  const rows = input.rows ?? 6;
  const scale = input.width / input.crop.width;
  const height = Math.max(1, Math.round(input.crop.height * scale));
  const canvas = new OffscreenCanvas(input.width, height);
  const context = canvas.getContext('2d', { willReadFrequently: true })!;
  const [br, bg, bb] = input.background;
  const backgroundLuma = 0.2126 * br + 0.7152 * bg + 0.0722 * bb;
  const coverage: number[] = [];
  const cellCoverage: Float32Array[] = [];
  const change: number[] = [];
  let previous: Float32Array | null = null;
  const cellOf = new Uint16Array(input.width * height);
  const cellSize = new Float32Array(columns * rows);
  for (let y = 0; y < height; y += 1) for (let x = 0; x < input.width; x += 1) {
    const cell = Math.min(rows - 1, Math.floor((y / height) * rows)) * columns + Math.min(columns - 1, Math.floor((x / input.width) * columns));
    cellOf[y * input.width + x] = cell;
    cellSize[cell] += 1;
  }
  for (const data of input.frames) {
    const blob = await (await fetch(`data:image/jpeg;base64,${data}`)).blob();
    const bitmap = await createImageBitmap(blob, input.crop.x, input.crop.y, input.crop.width, input.crop.height, { resizeWidth: input.width, resizeHeight: height, resizeQuality: 'medium' });
    context.clearRect(0, 0, input.width, height);
    context.drawImage(bitmap, 0, 0);
    bitmap.close();
    const pixels = context.getImageData(0, 0, input.width, height).data;
    const luma = new Float32Array(input.width * height);
    const cells = new Float32Array(columns * rows);
    let covered = 0;
    for (let index = 0; index < luma.length; index += 1) {
      const r = pixels[index * 4];
      const g = pixels[index * 4 + 1];
      const b = pixels[index * 4 + 2];
      luma[index] = 0.2126 * r + 0.7152 * g + 0.0722 * b;
      if (Math.abs(luma[index] - backgroundLuma) > contentDelta || Math.abs(r - br) + Math.abs(g - bg) + Math.abs(b - bb) > contentDelta * 2) {
        covered += 1;
        cells[cellOf[index]] += 1;
      }
    }
    for (let cell = 0; cell < cells.length; cell += 1) cells[cell] /= Math.max(1, cellSize[cell]);
    coverage.push(covered / luma.length);
    cellCoverage.push(cells);
    let sum = 0;
    if (previous) for (let index = 0; index < luma.length; index += 1) sum += Math.abs(luma[index] - previous[index]);
    change.push(previous ? sum / luma.length : 0);
    previous = luma;
  }
  const at = (values: ArrayLike<number>[], frame: number, cell: number) => values[Math.min(values.length - 1, Math.max(0, frame))][cell];
  const swing = (series: (frame: number) => number, frame: number, last: number) => {
    if (frame === 0 || frame === last) return 0;
    const before = [series(frame - 1), series(Math.max(0, frame - 2))];
    const after = [series(frame + 1), series(Math.min(last, frame + 2))];
    const value = series(frame);
    const dip = Math.min(Math.max(...before), Math.max(...after)) - value;
    const peak = value - Math.max(Math.min(...before), Math.min(...after));
    return Math.max(dip, peak, 0);
  };
  const last = coverage.length - 1;
  const frames: FlickerFrame[] = coverage.map((value, index) => {
    const dip = swing(frame => coverage[frame], index, last);
    let cells = 0;
    for (let cell = 0; cell < columns * rows; cell += 1) if (swing(frame => at(cellCoverage, frame, cell), index, last) > cellDip) cells += 1;
    const global = dip > globalDip;
    return {
      index,
      coverage: Math.round(value * 10000) / 10000,
      change: Math.round(change[index] * 100) / 100,
      dip: Math.round(dip * 10000) / 10000,
      cells,
      flagged: global || cells > 0,
      reason: global ? (cells > 0 ? 'global+cells' : 'global') : cells > 0 ? `cells:${cells}` : '',
    };
  });
  const flagged = frames.filter(frame => frame.flagged).map(frame => frame.index);
  return {
    frames,
    flagged,
    flickerFrames: flagged.length,
    cellEvents: frames.reduce((sum, frame) => sum + frame.cells, 0),
    worstDip: frames.reduce((most, frame) => Math.max(most, frame.dip), 0),
    coverageMin: frames.reduce((least, frame) => Math.min(least, frame.coverage), 1),
    coverageMax: frames.reduce((most, frame) => Math.max(most, frame.coverage), 0),
  };
}

export interface StripInput {
  frames: string[];
  labels: string[];
  crop: FlickerCrop;
  thumbWidth: number;
  columns: number;
  highlight?: number[];
}

export async function stripInPage(input: StripInput): Promise<string> {
  const scale = input.thumbWidth / input.crop.width;
  const thumbHeight = Math.max(1, Math.round(input.crop.height * scale));
  const rows = Math.ceil(input.frames.length / input.columns);
  const label = 16;
  const canvas = new OffscreenCanvas(input.columns * (input.thumbWidth + 4) + 4, rows * (thumbHeight + label + 4) + 4);
  const context = canvas.getContext('2d')!;
  context.fillStyle = '#1b1d22';
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.font = '11px ui-monospace, Menlo, monospace';
  const marked = new Set(input.highlight ?? []);
  for (let index = 0; index < input.frames.length; index += 1) {
    const blob = await (await fetch(`data:image/jpeg;base64,${input.frames[index]}`)).blob();
    const bitmap = await createImageBitmap(blob, input.crop.x, input.crop.y, input.crop.width, input.crop.height, { resizeWidth: input.thumbWidth, resizeHeight: thumbHeight, resizeQuality: 'high' });
    const x = 4 + (index % input.columns) * (input.thumbWidth + 4);
    const y = 4 + Math.floor(index / input.columns) * (thumbHeight + label + 4);
    context.drawImage(bitmap, x, y + label);
    bitmap.close();
    context.fillStyle = marked.has(index) ? '#ff5d6c' : '#c8ccd4';
    context.fillText(input.labels[index] ?? String(index), x + 2, y + 12);
    if (marked.has(index)) {
      context.strokeStyle = '#ff5d6c';
      context.lineWidth = 3;
      context.strokeRect(x + 1.5, y + label + 1.5, input.thumbWidth - 3, thumbHeight - 3);
    }
  }
  const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.82 });
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  for (let index = 0; index < bytes.length; index += 0x8000) binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  return btoa(binary);
}

export interface SharpnessInput {
  images: string[];
  crop: FlickerCrop;
  type?: 'png' | 'jpeg';
}

export async function sharpnessInPage(input: SharpnessInput): Promise<{ energy: number[]; difference: number[] }> {
  const width = input.crop.width;
  const height = input.crop.height;
  const canvas = new OffscreenCanvas(width, height);
  const context = canvas.getContext('2d', { willReadFrequently: true })!;
  const lumas: Float32Array[] = [];
  for (const data of input.images) {
    const blob = await (await fetch(`data:image/${input.type ?? 'png'};base64,${data}`)).blob();
    const bitmap = await createImageBitmap(blob, input.crop.x, input.crop.y, width, height);
    context.clearRect(0, 0, width, height);
    context.drawImage(bitmap, 0, 0);
    bitmap.close();
    const pixels = context.getImageData(0, 0, width, height).data;
    const luma = new Float32Array(width * height);
    for (let index = 0; index < luma.length; index += 1) luma[index] = 0.2126 * pixels[index * 4] + 0.7152 * pixels[index * 4 + 1] + 0.0722 * pixels[index * 4 + 2];
    lumas.push(luma);
  }
  const energy = lumas.map(luma => {
    let sum = 0;
    for (let y = 0; y < height - 1; y += 1) for (let x = 0; x < width - 1; x += 1) {
      const at = y * width + x;
      sum += Math.abs(luma[at + 1] - luma[at]) + Math.abs(luma[at + width] - luma[at]);
    }
    return Math.round((sum / (width * height)) * 1000) / 1000;
  });
  const difference = lumas.map(luma => {
    let sum = 0;
    for (let index = 0; index < luma.length; index += 1) sum += Math.abs(luma[index] - lumas[lumas.length - 1][index]);
    return Math.round((sum / luma.length) * 1000) / 1000;
  });
  return { energy, difference };
}
