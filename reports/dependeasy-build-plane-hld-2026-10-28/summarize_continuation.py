#!/usr/bin/env python3
"""Keep the measured deltas and output-equivalence assertions next to raw runs."""
import hashlib
import json
import statistics
from pathlib import Path

root = Path(__file__).resolve().parent
rows = json.loads((root / 'continuation-benchmark-results.json').read_text())
assert len(rows) == 24 and all(row['code'] == 0 for row in rows)
summary = {'samples': len(rows), 'repetitions': 3, 'slot': 1,
           'gradleTiming': 'Gradle console wall time rounded to seconds; no profiler in these runs',
           'endToEndTiming': 'Reserved execution plus transport, collection and isolated publication; admission separately recorded',
           'rows': [], 'outputChecks': []}
for phase in ('cold', 'first-warm', 'steady-warm', 'body-incremental'):
    entry = {'phase': phase}
    for variant in ('before', 'after'):
        samples = [row for row in rows if row['variant'] == variant and row['phase'] == phase]
        entry[variant] = {
            'gradleMedianSeconds': statistics.median(row['gradleConsoleSeconds'] for row in samples),
            'endToEndMedianSeconds': statistics.median(row['endToEndSeconds'] for row in samples),
            'gradleSeconds': [row['gradleConsoleSeconds'] for row in samples],
            'endToEndSeconds': [row['endToEndSeconds'] for row in samples],
            'collectedFilesMedian': statistics.median(row['collectedFiles'] for row in samples),
            'collectedBytesMedian': statistics.median(row['collectedBytes'] for row in samples),
        }
    for metric in ('gradleMedianSeconds', 'endToEndMedianSeconds'):
        entry[metric + 'ChangePercent'] = (entry['after'][metric] / entry['before'][metric] - 1) * 100
    summary['rows'].append(entry)

def jars(row):
    return {jar['path']: jar for jar in row['jars']}

for repetition in (21, 22, 23):
    selected = {(row['variant'], row['phase']): row for row in rows if row['rep'] == repetition}
    for phase in ('cold', 'first-warm', 'steady-warm', 'body-incremental'):
        before = jars(selected['before', phase])
        after = jars(selected['after', phase])
        assert before.keys() == after.keys()
        assert all(before[name]['sha256'] == after[name]['sha256'] for name in before)
        summary['outputChecks'].append({'rep': repetition, 'phase': phase, 'byteIdentical': True})
    for variant in ('before', 'after'):
        base = jars(selected[variant, 'cold'])
        edited = jars(selected[variant, 'body-incremental'])
        changed = sorted({name for path in base for name in set(base[path]['classes']) | set(edited[path]['classes'])
                          if base[path]['classes'].get(name) != edited[path]['classes'].get(name)})
        assert changed == ['dev/shibasis/reaktor/core/utils/TimeKt.class'], changed
        for phase in ('first-warm', 'steady-warm'):
            assert {path: jar['sha256'] for path, jar in base.items()} == {path: jar['sha256'] for path, jar in jars(selected[variant, phase]).items()}
        summary['outputChecks'].append({'rep': repetition, 'variant': variant, 'bodyEditChanges': changed})
summary['rawResultsSha256'] = hashlib.sha256((root / 'continuation-benchmark-results.json').read_bytes()).hexdigest()
(root / 'continuation-benchmark-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps({'samples': summary['samples'], 'rows': summary['rows'], 'outputChecks': len(summary['outputChecks'])}, indent=2))
