"""Read-only alternating comparison of identical apparent/allocated du accounting."""
from concurrent.futures import ThreadPoolExecutor
import os
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
ROOTS = [Path('/Users/ovd/dev/.remote-dev/snapshots')]


def identity():
    rows = []
    for root in ROOTS:
        for path in [root, *root.rglob('*')]:
            stat = path.lstat()
            rows.append((str(path), stat.st_ino, stat.st_size, stat.st_mtime_ns))
    return hashlib.sha256(json.dumps(sorted(rows)).encode()).hexdigest(), len(rows)


def serial(paths):
    """Count every hard-link name, so the cap also holds for Finder's apparent size."""
    paths = [Path(p) for p in paths if os.path.lexists(p)]
    if not paths:
        return {}
    sizes = {path: 0 for path in paths}
    for options in (['-A', '-l', '-sk'], ['-l', '-sk']):
        # Compiler temp roots can contain thousands of entries; stay below ARG_MAX.
        for start in range(0, len(paths), 128):
            batch = paths[start:start + 128]
            result = subprocess.run(['du', *options, *map(str, batch)],
                                    capture_output=True, text=True)
            if result.returncode:
                # Temp files can disappear while another process finishes its work.
                if all('No such file or directory' in line for line in result.stderr.splitlines()):
                    batch = [path for path in batch if os.path.lexists(path)]
                    result = subprocess.run(['du', *options, *map(str, batch)],
                                            capture_output=True, text=True) if batch else None
                if result and result.returncode:
                    raise OSError('Cannot measure managed storage: ' + result.stderr.strip()[:300])
            if result:
                for line in result.stdout.splitlines():
                    count, name = line.split('\t', 1)
                    path = Path(name)
                    sizes[path] = max(sizes.get(path, 0), int(count) * 1024)
    return sizes


before = identity()
rows = []
expected = workspace_storage.charged_sizes(ROOTS)
for pair in range(4):
    for label in (['serial', 'parallel'] if pair % 2 == 0 else ['parallel', 'serial']):
        start = time.monotonic()
        result = (serial if label == 'serial' else workspace_storage.charged_sizes)(ROOTS)
        seconds = time.monotonic() - start
        assert result == expected, (label, result, expected)
        rows.append({'pair': pair, 'mode': label, 'seconds': seconds})
        print(json.dumps(rows[-1]), flush=True)
after = identity()
assert before == after, (before, after)
medians = {label: statistics.median(row['seconds'] for row in rows if row['mode'] == label) for label in ['serial', 'parallel']}
proof = {'scope': 'charged_sizes only; no eviction/admission behavior changed; no whole-build speed claim', 'roots': list(map(str, ROOTS)), 'identity': before, 'chargedBytes': {str(k): v for k, v in expected.items()}, 'rows': rows, 'medians': medians, 'changePercent': (medians['parallel'] / medians['serial'] - 1) * 100}
(REPORT / 'storage-production-scan-benchmark.json').write_text(json.dumps(proof, indent=2) + '\n')
print(json.dumps(proof), flush=True)
