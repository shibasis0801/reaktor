#!/usr/bin/env node
import { gzipSync } from 'node:zlib';
import { readFileSync, statSync, writeFileSync } from 'node:fs';

function usage() {
  console.error(
    'usage: node tools/size-report.mjs --target <name> --artifact <type:name:path[:budgetBytes[:compressedBudgetBytes]]> [--out report.json]',
  );
  process.exit(2);
}

const args = process.argv.slice(2);
let target = null;
let out = null;
const artifactSpecs = [];

for (let index = 0; index < args.length; index += 1) {
  const arg = args[index];
  if (arg === '--target') {
    target = args[++index];
  } else if (arg === '--artifact') {
    artifactSpecs.push(args[++index]);
  } else if (arg === '--out') {
    out = args[++index];
  } else {
    usage();
  }
}

if (!target || artifactSpecs.length === 0) usage();

const artifacts = artifactSpecs.map((spec) => {
  const [type, name, path, budgetBytes, compressedBudgetBytes] = spec.split(':');
  if (!type || !name || !path) usage();
  const bytes = statSync(path).size;
  const compressedBytes = gzipSync(readFileSync(path)).byteLength;
  return {
    name,
    type,
    bytes,
    compressedBytes,
    budgetBytes: budgetBytes ? Number(budgetBytes) : null,
    compressedBudgetBytes: compressedBudgetBytes ? Number(compressedBudgetBytes) : null,
    path,
    scope: {
      artifactPath: path,
    },
  };
});

const report = {
  target,
  generatedAt: new Date().toISOString(),
  marks: [],
  samples: [],
  metrics: [],
  buildArtifacts: artifacts,
  buildTimings: [],
  flamegraph: [],
  profiles: [],
  toolRuns: [],
  budgets: [],
};

const payload = `${JSON.stringify(report, null, 2)}\n`;
if (out) {
  writeFileSync(out, payload);
} else {
  process.stdout.write(payload);
}
