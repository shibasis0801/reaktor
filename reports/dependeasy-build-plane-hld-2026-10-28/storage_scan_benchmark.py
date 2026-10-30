"""Read-only alternating comparison of identical apparent/allocated du accounting."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time

sys.path.insert(0, '/Users/ovd/dev/tools')
import workspace_storage

REPORT = Path(__file__).parent
ROOTS = [Path('/Users/ovd/dev/.remote-dev/artifacts/20261029-070559-d909058b-bestbuds')]


def identity():
    rows = []
    for root in ROOTS:
        for path in [root, *root.rglob('*')]:
            stat = path.lstat()
            rows.append((str(path), stat.st_ino, stat.st_size, stat.st_mtime_ns))
    return hashlib.sha256(json.dumps(sorted(rows)).encode()).hexdigest(), len(rows)


def parallel(paths):
    paths = [Path(p) for p in paths if p.exists() or p.is_symlink()]
    if not paths:
        return {}
    def measure(options):
        sizes = {}
        for start in range(0, len(paths), 128):
            batch = paths[start:start + 128]
            result = subprocess.run(['du', *options, *map(str, batch)], capture_output=True, text=True, check=True)
            for line in result.stdout.splitlines():
                count, name = line.split('\t', 1)
                sizes[Path(name)] = int(count) * 1024
        return sizes
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(measure, (['-A', '-l', '-sk'], ['-l', '-sk'])))
    return {p: max(result.get(p, 0) for result in results) for p in paths}


before = identity()
rows = []
expected = workspace_storage.charged_sizes(ROOTS)
for pair in range(4):
    for label in (['serial', 'parallel'] if pair % 2 == 0 else ['parallel', 'serial']):
        start = time.monotonic()
        result = (workspace_storage.charged_sizes if label == 'serial' else parallel)(ROOTS)
        seconds = time.monotonic() - start
        assert result == expected, (label, result, expected)
        rows.append({'pair': pair, 'mode': label, 'seconds': seconds})
        print(json.dumps(rows[-1]), flush=True)
after = identity()
assert before == after, (before, after)
medians = {label: statistics.median(row['seconds'] for row in rows if row['mode'] == label) for label in ['serial', 'parallel']}
proof = {'scope': 'charged_sizes only; no eviction/admission behavior changed; no whole-build speed claim', 'roots': list(map(str, ROOTS)), 'identity': before, 'chargedBytes': {str(k): v for k, v in expected.items()}, 'rows': rows, 'medians': medians, 'changePercent': (medians['parallel'] / medians['serial'] - 1) * 100}
(REPORT / 'storage-scan-benchmark.json').write_text(json.dumps(proof, indent=2) + '\n')
print(json.dumps(proof), flush=True)
