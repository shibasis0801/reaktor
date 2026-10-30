#!/usr/bin/env python3
"""Private compiler treatment; frozen controls and existing worker/router only."""
import contextlib
import hashlib
import json
import time
from pathlib import Path
import performance_followup as p

original = p.STATE
identity = "bc1581702ae7a00ad4be18a9e37a2a4967501ab2ce563ca89823e523162bb882"
data = json.loads((p.HERE / "continuation-benchmark-snapshots.json").read_text())
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(original, identity))
    stack.enter_context(p.workspace_storage.graph_activity(original))
    p.h.ROOT = p.materialize(identity)
    data['variants'] = {}
    edit = p.h.ROOT / 'reaktor' / p.EDIT
    text = edit.read_text()
    for variant in ('before', 'after'):
        if variant == 'after':
            root = p.h.ROOT / 'reaktor/dependeasy/build.gradle.kts'
            find = 'plugins.withId("org.jetbrains.kotlin.jvm") {'
            assert root.read_text().count(find) == 1
            root.write_text(root.read_text().replace(find, find + '''
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions.freeCompilerArgs.add("-Xbackend-threads=2")
        }'''))
            bootstrap = p.h.ROOT / 'reaktor/dependeasy/buildSrc/build.gradle.kts'
            find = 'compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)'
            assert bootstrap.read_text().count(find) == 1
            bootstrap.write_text(bootstrap.read_text().replace(find, find + '\n    compilerOptions.freeCompilerArgs.add("-Xbackend-threads=2")'))
        data['variants'][variant] = {'base': p.frozen.freeze(p.h)}
        edit.write_text(text.replace('Clock.System.now().toEpochMilliseconds()', '(Clock.System.now().toEpochMilliseconds() + 1L)'))
        data['variants'][variant]['incremental'] = p.frozen.freeze(p.h)
        edit.write_text(text)
    for item in data['variants'].values():
        for snapshot in item.values():
            stack.enter_context(p.workspace_storage.snapshot_pin(original, snapshot))
    (p.HERE / 'bootstrap-parallel-snapshots.json').write_text(json.dumps(data, indent=2) + '\n')
    started = time.monotonic()
    stack.enter_context(p.frozen.lease(original / 'worker-slot-1.lock'))
    stack.enter_context(p.h.worker_slot(1))
    p.STATE = p.TEMP / 'state'
    p.h.STATE = p.STATE
    for category in ('logs', 'artifacts', 'runs'):
        (p.STATE / category).mkdir(parents=True, exist_ok=True)
    reservation = {'queueSeconds': time.monotonic() - started, 'slot': 1, 'remote': p.h.REMOTE,
                   'scriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   'treatment': 'two backend threads in Dependeasy bootstrap only; no live source changes'}
    (p.HERE / 'bootstrap-parallel-reservation.json').write_text(json.dumps(reservation, indent=2) + '\n')
    print(json.dumps(reservation), flush=True)
    print(json.dumps(p.remote('''import json,subprocess,sys
from pathlib import Path
root=Path(sys.argv[1]);source=Path('/Users/ovd/.gradle')
assert root.name=='performance-followup-20261028'
for name in ('before','after'):
 home=root/name;home.mkdir(parents=True,exist_ok=True)
 for rel in ('wrapper','caches/modules-2'):
  src=source/rel;dest=home/rel
  if src.exists() and not dest.exists():
   dest.parent.mkdir(parents=True,exist_ok=True)
   subprocess.run(['/bin/cp','-cRp',str(src),str(dest)],check=True)
print(json.dumps({'seededDownloadedArtifacts':True}))
''', p.h.BASE_REMOTE + '/.remote-dev/performance-followup-20261028')), flush=True)
    results = []
    for rep in range(101, 104):
        for variant in (('before', 'after') if rep % 2 else ('after', 'before')):
            for phase in ('cold', 'warm', 'incremental'):
                results.append(p.run(data, variant, phase, rep, cc=False, profile=True))
                (p.HERE / 'bootstrap-parallel-results.json').write_text(json.dumps(results, indent=2) + '\n')
