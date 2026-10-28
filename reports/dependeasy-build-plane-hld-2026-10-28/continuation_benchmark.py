#!/usr/bin/env python3
"""Isolate compatible lazy configuration changes on the retained worker controls."""
import contextlib
import hashlib
import json
import subprocess
import time
import performance_followup as p

OWN = [
    'dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/analysis/KotlinAnalysisCompile.kt',
    'dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/workspace/BuildWorkspace.kt',
    'dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/web/JavaScriptComponent.kt',
]
original = p.STATE
D = json.loads((p.HERE / 'performance-snapshots.json').read_text())
baseline = json.loads((p.HERE / 'configuration-cache-final-reservation.json').read_text())['snapshots']['base']
rows = []
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(original, baseline))
    stack.enter_context(p.workspace_storage.graph_activity(original))
    p.h.ROOT = p.materialize(baseline)
    D['variants'] = {}
    source_hashes = {}
    for variant in ('before', 'after'):
        if variant == 'after':
            for relative in OWN:
                source = p.DEV / 'reaktor' / relative
                source_hashes[relative] = hashlib.sha256(source.read_bytes()).hexdigest()
                p.frozen.copy_source(source, p.h.ROOT / 'reaktor' / relative)
        base = p.frozen.freeze(p.h)
        stack.enter_context(p.workspace_storage.snapshot_pin(original, base))
        source = p.h.ROOT / 'reaktor' / p.EDIT
        text = source.read_text()
        assert text.count('Clock.System.now().toEpochMilliseconds()') == 1
        source.write_text(text.replace('Clock.System.now().toEpochMilliseconds()', '(Clock.System.now().toEpochMilliseconds() + 1L)'))
        body = p.frozen.freeze(p.h)
        stack.enter_context(p.workspace_storage.snapshot_pin(original, body))
        source.write_text(text)
        D['variants'][variant] = {'base': base, 'incremental': body}
    # The two snapshots differ only in these three production files.
    manifests = [json.loads((original / 'snapshots' / D['variants'][v]['base'] / 'manifest.json').read_text()) for v in ('before', 'after')]
    differing = sorted(k for k in manifests[0]['files'] if manifests[0]['files'][k] != manifests[1]['files'][k])
    assert differing == sorted('reaktor/' + path for path in OWN), differing
    (p.HERE / 'continuation-benchmark-snapshots.json').write_text(json.dumps(D, indent=2) + '\n')
    started = time.monotonic()
    stack.enter_context(p.frozen.lease(original / 'worker-slot-1.lock'))
    stack.enter_context(p.h.worker_slot(1))
    p.STATE = p.TEMP / 'state'
    p.h.STATE = p.STATE
    for category in ('logs', 'artifacts', 'runs'):
        (p.STATE / category).mkdir(parents=True, exist_ok=True)
    reservation = {'queueSeconds': time.monotonic() - started, 'slot': 1, 'remote': p.h.REMOTE,
                   'snapshots': D['variants'], 'changedFiles': differing, 'sourceHashes': source_hashes,
                   'routerSha256': hashlib.sha256((p.DEV / 'tools/remote-dev').read_bytes()).hexdigest(),
                   'scriptSha256': hashlib.sha256(p.Path(__file__).read_bytes()).hexdigest(),
                   'scope': 'Retained compatible baseline plus only three lazy-configuration production files; same default cache, target, toolchain, worker slot and dependency state.'}
    (p.HERE / 'continuation-benchmark-reservation.json').write_text(json.dumps(reservation, indent=2) + '\n')
    print(json.dumps(reservation), flush=True)
    print(json.dumps(p.remote('''import json,subprocess,sys
from pathlib import Path
root=Path(sys.argv[1]);source=Path(sys.argv[2]);seeded=[]
assert root.name=="performance-followup-20261028"
assert (source/"caches/modules-2").is_dir()
for name in ("before","after"):
 home=root/name;home.mkdir(parents=True,exist_ok=True)
 for rel in ("wrapper","caches/modules-2"):
  src=source/rel;dest=home/rel
  if src.exists() and not dest.exists():
   dest.parent.mkdir(parents=True,exist_ok=True)
   subprocess.run(["/bin/cp","-cRp",str(src),str(dest)],check=True);seeded.append(str(dest))
print(json.dumps({"seeded":seeded}))
''', p.h.BASE_REMOTE+'/.remote-dev/performance-followup-20261028', '/Users/ovd/.gradle')), flush=True)
    for rep in range(1, 4):
        for variant in (('before', 'after') if rep % 2 else ('after', 'before')):
            for phase in ('cold', 'first-warm', 'steady-warm', 'body-incremental'):
                rows.append(p.run(D, variant, phase, 20 + rep, cc=None, profile=False))
                (p.HERE / 'continuation-benchmark-results.json').write_text(json.dumps(rows, indent=2) + '\n')
