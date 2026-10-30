#!/usr/bin/env python3
"""Qualify the continuation from one frozen source, independent of shared edits."""
import contextlib
import hashlib
import json
import subprocess
import time
from pathlib import Path
import performance_followup as p

p.h.PROJECTS = ['bestbuds', 'reaktor']
p.TEMP = p.DEV / 'tmp/dependeasy-bounded-runs-20261029'
identity = '93d47f8e39cbb2bb7d24ccb19de9c9cd942bfd5b41d6bf6b22be73524a319f1c'  # already captured before the storage guard stopped this run
started = time.monotonic()
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(p.GLOBAL_STATE, identity))
    stack.enter_context(p.workspace_storage.graph_activity(p.GLOBAL_STATE))
    stack.enter_context(p.frozen.lease(p.GLOBAL_STATE / 'worker-slot-1.lock'))
    stack.enter_context(p.h.worker_slot(1))
    admission = time.monotonic() - started
    p.workspace_storage.automatic(p.h, held_locks={'worker-slot-1'})
    p.h.ROOT = p.materialize(identity)
    manifest = p.GLOBAL_STATE / 'snapshots' / identity / 'manifest.json'
    (p.HERE / 'bounded-runs-manifest.json').write_bytes(manifest.read_bytes())
    p.h.sync(['bestbuds', 'reaktor'], frozen=True)
    p.frozen.verify_worker(p.h, identity)
    private = p.h.BASE_REMOTE + '/.remote-dev/dependeasy-bounded-runs-20261029'
    result = p.remote(r'''import hashlib,json,subprocess,sys,time,zipfile
from pathlib import Path
root=Path(sys.argv[1]);private=Path(sys.argv[2]);project=root/'bestbuds'
assert private.name=='dependeasy-bounded-runs-20261029'
private.mkdir(parents=True,exist_ok=True)
tasks=[':kernel:test','--rerun',':reaktorCli:test','--rerun',':kernel:verifyKernelBoundary','--rerun',':reaktor:reaktor-tooling:jvmTest','--rerun','--continue','--no-daemon','--max-workers=6','--console=plain']
runtime=subprocess.check_output([str(project/'gradlew'),'--version'],cwd=project,text=True)
started=time.monotonic()
with (private/'build.log').open('w') as log:
 result=subprocess.run([str(project/'gradlew'),*tasks],cwd=project,stdout=log,stderr=subprocess.STDOUT,timeout=1200)
summary={'exitCode':result.returncode,'workerBuildWallSeconds':time.monotonic()-started,'tasks':tasks,'runtime':runtime}
with zipfile.ZipFile(private/'test-results.zip','w',zipfile.ZIP_DEFLATED) as z:
 for base in [project/'modules/kernel/build/test-results/test',project/'targets/reaktorCli/build/test-results/test',root/'reaktor/reaktor-tooling/build/test-results/jvmTest']:
  for file in base.glob('TEST-*.xml'):z.write(file,str(file.relative_to(root)))
summary['xmlArchiveSha256']=hashlib.sha256((private/'test-results.zip').read_bytes()).hexdigest()
(private/'result.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary))
''', p.h.REMOTE, private)
    for name in ('build.log', 'result.json', 'test-results.zip'):
        subprocess.run(['scp', '-q', p.h.HOST + ':' + private + '/' + name,
                        str(p.HERE / ('bounded-runs-' + name))], check=True)
    p.frozen.verify_worker(p.h, identity)
    result.update(snapshot=identity, workerSlot=1, admissionSeconds=admission,
                  sourceVerifiedBeforeAndAfter=True,
                  harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    (p.HERE / 'bounded-runs-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
    assert result['exitCode'] == 0, result
