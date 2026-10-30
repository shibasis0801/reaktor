"""Controlled class-only comparison; use a qualified producer overlay for final evidence."""
from pathlib import Path
import hashlib
import json
import os
import platform
import statistics
import subprocess
import time

REPORT = Path(__file__).parent.resolve()
SCRATCH = Path('/Users/ovd/dev/tmp/schema-fingerprint-benchmark')
JAVA = '/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home/bin/java'
runtime = json.loads((REPORT / 'schema-fingerprint-runtime.json').read_text())
producer = Path(os.environ.get('FINGERPRINT_OVERLAY', str(SCRATCH / 'streaming')))
output = os.environ.get('FINGERPRINT_CAMPAIGN', 'schema-fingerprint-provisional-comparison.json')
digest = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
before = {path: digest(path) for path in runtime}
libraries = [str(Path('/Users/ovd/.reaktor/libexec/lib') / name) for name in (
    'reaktor-service-jvm-2026.1.6-SNAPSHOT.jar', 'reaktor-tooling-jvm-2026.1.6-SNAPSHOT.jar',
    'reaktor-conductor-jvm-2026.1.6-SNAPSHOT.jar', 'kernel.jar')]
rows = []
for pair in range(6):
    order = ['baseline', 'streaming'] if pair % 2 == 0 else ['streaming', 'baseline']
    for label in order:
        overlay = REPORT / 'schema-fingerprint-baseline.jar' if label == 'baseline' else producer
        env = dict(os.environ, FINGERPRINT_REPEATS='2000',
                   FINGERPRINT_GOLDEN=str(REPORT / f'schema-fingerprint-{label}-golden.txt'))
        start = time.monotonic()
        load = os.getloadavg()
        command = [JAVA, '-Xms512m', '-Xmx512m', '-cp', ':'.join(
            [str(overlay), str(SCRATCH / 'fixtures'), str(SCRATCH / 'harness'), *runtime]),
            'SchemaFingerprintBenchmark', *libraries]
        result = subprocess.run(command, text=True, capture_output=True, env=env, check=True)
        row = dict(json.loads(result.stdout), pair=pair, variant=label,
                   jvmWallSeconds=time.monotonic()-start, hostLoadBefore=load,
                   hostLoadAfter=os.getloadavg())
        rows.append(row)
        (REPORT / (output + '.progress')).write_text(json.dumps(rows, indent=2)+'\n')
        print(json.dumps(row), flush=True)
after = {path: digest(path) for path in runtime}
assert before == after, 'Runtime changed during campaign'
assert len({row['fingerprintsSha256'] for row in rows}) == 1
assert len({row['sink'] for row in rows}) == 1
assert (REPORT/'schema-fingerprint-baseline-golden.txt').read_bytes() == (REPORT/'schema-fingerprint-streaming-golden.txt').read_bytes()
median = {variant: {field: statistics.median(row[field] for row in rows if row['variant'] == variant)
                   for field in ('nanos','allocatedBytes','jvmWallSeconds')}
          for variant in ('baseline','streaming')}
reduction = {field: (1-median['streaming'][field]/median['baseline'][field])*100
             for field in ('nanos','allocatedBytes','jvmWallSeconds')}
proof = dict(rows=rows, median=median, reductionPercent=reduction, runtimeSha256=before,
             platform=platform.platform(), logicalCpus=os.cpu_count(), overlay=str(producer),
             sourceSha256=digest(REPORT/'schema-fingerprint-streaming/Operation.kt'),
             scope='Fresh 512MiB JVMs, alternating order, same serializers and runtime; function time/allocation and JVM wall reported separately. No whole UI or build speed claim.')
(REPORT/output).write_text(json.dumps(proof,indent=2)+'\n')
print(json.dumps({'median':median,'reductionPercent':reduction}),flush=True)
