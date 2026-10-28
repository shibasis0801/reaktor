#!/usr/bin/env python3
"""Summarize retained measurements without running another build."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
DEV = Path('/Users/ovd/dev')
result = {'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'gradle': [], 'cli': {}, 'endState': {}, 'existingTestReports': {}}
for row in json.loads((OUT / 'gradle-benchmark.json').read_text())['runs']:
    log = (OUT / row['log']).read_text()
    summary = dict(row)
    summary['gradleSummary'] = re.findall(r'BUILD SUCCESSFUL in (.+)', log)
    summary['configurationTimeResolutionWarnings'] = log.count('was resolved during configuration time.')
    summary['configurationCacheReused'] = 'Reusing configuration cache.' in log
    summary['configurationCacheStored'] = 'Configuration cache entry stored.' in log
    summary['configurationCacheMessages'] = re.findall(r'^.*(?:[Cc]onfiguration cache|cached configuration).*$', log, re.MULTILINE)
    summary['taskSummary'] = re.findall(r'\d+ actionable tasks:.+', log)
    receipts = re.findall(r'exit 0; receipt (.+)', log)
    if receipts:
        receipt = json.loads(Path(receipts[-1]).read_text())
        summary['routerReceipt'] = receipts[-1]
        summary['router'] = {k: receipt.get(k) for k in (
            'worker', 'worker_slot', 'remote_path', 'source_head', 'remote_tasks',
            'sync_seconds', 'queue_seconds', 'execution_seconds', 'collection_seconds',
            'publication_queue_seconds', 'publication_seconds', 'copied_files',
            'withheld_outputs', 'unverified_copies', 'copy_error', 'exit_code')}
    result['gradle'].append(summary)
cli = json.loads((OUT / 'cli-benchmark.json').read_text())
for name, op in cli['operations'].items():
    good = [s['ms'] for s in op['samples'] if s.get('exit', 1) == 0]
    result['cli'][name] = {'attempts': len(op['samples']), 'successes': len(good),
                          'successfulMedianMs': statistics.median(good) if good else None,
                          'successfulMinMs': min(good) if good else None,
                          'successfulMaxMs': max(good) if good else None,
                          'failures': [{k: v for k, v in s.items() if k != 'response'}
                                       for s in op['samples'] if s.get('exit', 1) != 0]}
for repo in ('reaktor', 'bestbuds', 'manna'):
    root = DEV / repo
    model = root / 'build/dependeasy/model.json'
    receipts = list((root / 'build/dependeasy/receipts').glob('*.json'))
    result['endState'][repo] = {
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        'modelSha256': hashlib.sha256(model.read_bytes()).hexdigest(),
        'receiptFiles': len(receipts), 'receiptBytes': sum(p.stat().st_size for p in receipts)}
for module in ('model', 'gradle'):
    path = DEV / 'reaktor/dependeasy' / module / 'build/test-results/test'
    files = list(path.glob('TEST-*.xml'))
    result['existingTestReports'][module] = {
        'suites': len(files),
        **{k: sum(int(ET.parse(f).getroot().get(k, 0)) for f in files)
           for k in ('tests', 'failures', 'errors', 'skipped')},
        'latestModifiedAt': datetime.datetime.fromtimestamp(
            max(f.stat().st_mtime for f in files), datetime.timezone.utc).isoformat()}
(OUT / 'analysis.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
