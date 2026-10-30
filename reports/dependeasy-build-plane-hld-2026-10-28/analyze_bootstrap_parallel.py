#!/usr/bin/env python3
"""Check the complete private compiler experiment without changing its samples."""
import hashlib
import json
import statistics
from pathlib import Path

here = Path(__file__).resolve().parent
rows = json.loads((here / 'bootstrap-parallel-results.json').read_text())
assert len(rows) == 18
assert all(row['code'] == 0 and row['workerInputsUnchanged'] for row in rows)
snapshots = json.loads((here / 'bootstrap-parallel-snapshots.json').read_text())['variants']
manifests = {}
for variant, states in snapshots.items():
    for phase, identity in states.items():
        manifest = Path('/Users/ovd/dev/.remote-dev/snapshots') / identity / 'manifest.json'
        manifests[variant, phase] = json.loads(manifest.read_text())['files']

def changes(left, right):
    return sorted(key for key in left.keys() | right.keys() if left.get(key) != right.get(key))

treatment = changes(manifests['before', 'base'], manifests['after', 'base'])
assert treatment == ['reaktor/dependeasy/build.gradle.kts', 'reaktor/dependeasy/buildSrc/build.gradle.kts']
edit = 'reaktor/reaktor-core/src/commonMain/kotlin/dev/shibasis/reaktor/core/utils/time.kt'
assert all(changes(manifests[v, 'base'], manifests[v, 'incremental']) == [edit] for v in snapshots)
assert len({(r['variant'], r['phase'], r['rep']) for r in rows}) == 18
by = {(r['variant'], r['phase'], r['rep']): r for r in rows}
pairs, edits = [], []
for rep in range(101, 104):
    for phase in ('cold', 'warm', 'incremental'):
        a, b = (by[v, phase, rep] for v in ('before', 'after'))
        assert [j['sha256'] for j in a['jars']] == [j['sha256'] for j in b['jars']]
        pairs.append({'rep': rep, 'phase': phase, 'jarSha256': a['jars'][0]['sha256']})
    for variant in ('before', 'after'):
        warm, edited = (by[variant, phase, rep]['jars'][0]['classes'] for phase in ('warm', 'incremental'))
        changed = changes(warm, edited)
        assert len(changed) == 1 and changed[0].endswith('/TimeKt.class')
        edits.append({'rep': rep, 'variant': variant, 'changedClasses': changed})
summary = {}
for phase in ('cold', 'warm', 'incremental'):
    values = {}
    for variant in ('before', 'after'):
        selected = [r for r in rows if r['phase'] == phase and r['variant'] == variant]
        values[variant] = {
            'gradleMedianSeconds': statistics.median(r['profileSeconds']['Total Build Time'] for r in selected),
            'reservedE2EMedianSeconds': statistics.median(r['endToEndSeconds'] for r in selected),
            'gradleSamples': [r['profileSeconds']['Total Build Time'] for r in selected],
            'reservedE2ESamples': [r['endToEndSeconds'] for r in selected],
        }
    values['gradleChangePercent'] = (values['after']['gradleMedianSeconds'] / values['before']['gradleMedianSeconds'] - 1) * 100
    values['reservedE2EChangePercent'] = (values['after']['reservedE2EMedianSeconds'] / values['before']['reservedE2EMedianSeconds'] - 1) * 100
    summary[phase] = values
proof = {
    'completedCommands': len(rows), 'treatmentPaths': treatment,
    'sourceFiles': len(manifests['before', 'base']), 'equalArchivePairs': pairs,
    'editAssertions': edits, 'summary': summary,
    'reservation': json.loads((here / 'bootstrap-parallel-reservation.json').read_text()),
    'workerLoadAverage1mRange': [min(r[s]['load'][0] for r in rows for s in ('before', 'after')),
                               max(r[s]['load'][0] for r in rows for s in ('before', 'after'))],
    'timingBoundary': 'Normal routed build wall under one reserved slot lease; admission is the reservation value. Per-row transport queue_seconds is cumulative campaign bookkeeping and is not per-build waiting time.',
    'sampleSha256': hashlib.sha256((here / 'bootstrap-parallel-results.json').read_bytes()).hexdigest(),
}
(here / 'bootstrap-parallel-summary.json').write_text(json.dumps(proof, indent=2) + '\n')
print(json.dumps({'summary': summary, 'equalArchivePairs': len(pairs), 'editAssertions': len(edits)}, indent=2))
