#!/usr/bin/env python3
"""One paired source-size sensitivity check, separate from the 18-run real-project matrix."""
import contextlib,json,shutil,subprocess,sys,time
from pathlib import Path
import benchmark as b
import workspace_storage

MAIN=b.HERE
FIXED=MAIN/'fixed-source'

def capture():
    FIXED.mkdir(exist_ok=True)
    common=json.loads((MAIN/'snapshots.json').read_text())
    data={'snapshots':{},'projectRoots':common['projectRoots'],'overlays':{}}
    with workspace_storage.graph_activity(b.h.STATE):
        for variant,ref in b.REFS.items():
            subprocess.run(['git','-C',str(b.SOURCE),'reset','--hard',ref],check=True)
            criteria=b.SOURCE/'gradle/gradle-daemon-jvm.properties'
            if criteria.exists():criteria.write_text(criteria.read_text().replace('toolchainVersion=25','toolchainVersion=21'))
            (b.SOURCE/'benchmark.init.gradle').write_bytes((MAIN/'benchmark.init.gradle').read_bytes())
            path=b.SOURCE/b.EDIT;original=path.read_text()
            removed=[]
            for source_set in ('commonMain','jvmMain'):
                root=b.SOURCE/'reaktor-core/src'/source_set
                for p in root.rglob('*.kt'):
                    if p!=path:removed.append(str(p.relative_to(b.SOURCE)));p.unlink()
            data['overlays'][variant]={'removedKotlinSources':sorted(removed),
                'retainedCompileSource':b.EDIT,'sourceText':original,
                'scope':'Only commonMain/jvmMain Kotlin sources reduced; root projects, dependencies and build logic stay native.'}
            data['snapshots'][variant]={'base':b.frozen.freeze(b.h)}
            path.write_text(original.replace('Clock.System.now().toEpochMilliseconds()',
                                             '(Clock.System.now().toEpochMilliseconds() + 1L)'))
            data['snapshots'][variant]['incremental']=b.frozen.freeze(b.h)
    shutil.copy2(MAIN/'benchmark.init.gradle',FIXED/'benchmark.init.gradle')
    (FIXED/'snapshots.json').write_text(json.dumps(data,indent=2)+'\n')
    (FIXED/'excluded-runs.json').write_text('{}\n')
    print(json.dumps(data['snapshots'],indent=2),flush=True)

def run():
    b.HERE=FIXED
    original=b.frozen.lease;lock_path=b.h.STATE/'worker-gradle.lock'
    began=time.monotonic()
    with contextlib.ExitStack() as stack:
        for variants in json.loads((FIXED/'snapshots.json').read_text())['snapshots'].values():
            for identity in variants.values():stack.enter_context(workspace_storage.snapshot_pin(b.h.STATE,identity))
        owned=stack.enter_context(original(lock_path))
        for index in range(1,max(3,b.h.worker_slot_count())):stack.enter_context(original(b.h.STATE/(b.h.slot_lock_name(index)+'.lock')))
        cleanup=workspace_storage.cleanup(wait=True,held_locks=('worker-gradle','worker-slot-1','worker-slot-2'))
        (FIXED/'reservation-cleanup.json').write_text(json.dumps(cleanup,indent=2)+'\n')
        stack.enter_context(workspace_storage.graph_activity(b.h.STATE))
        (FIXED/'reservation.json').write_text(json.dumps({'queueSeconds':time.monotonic()-began,'epoch':time.time()},indent=2)+'\n')
        @contextlib.contextmanager
        def reentrant(path):
            if path==lock_path:yield owned
            else:
                with original(path) as lock:yield lock
        b.frozen.lease=reentrant
        for variant in ('september','current'):
            for phase in ('cold','warm','incremental'):b.run(variant,phase,1)

if __name__=='__main__':
    if sys.argv[1]=='capture':capture()
    elif sys.argv[1]=='run':run()
    else:raise SystemExit('capture|run')
