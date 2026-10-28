#!/usr/bin/env python3
"""Separate treatments: configuration-cache reuse and native JDK, without task observers."""
import contextlib,hashlib,json,os,time
from pathlib import Path
import benchmark as b
import workspace_storage

original_isolated=b.frozen.isolated_build
treatment='plain'
def invoke(helper,identity,host,project,args,run_id=None):
    b.active['observerEnabled']=False
    b.active['treatment']=treatment
    if b.active['phase']=='plain-cold':b.active['phase']='cold'
    filtered=[];i=0
    while i<len(args):
        if args[i]=='--init-script' and args[i+1]=='benchmark.init.gradle':i+=2;continue
        if args[i]=='--no-configuration-cache' and treatment=='cc':i+=1;continue
        filtered.append(args[i]);i+=1
    if treatment=='cc':filtered.extend(['--configuration-cache','--configuration-cache-problems=fail'])
    if treatment=='export':filtered.append(':exportBuildModel')
    if b.active['variant']=='current-native':
        # The runtime treatment shares the current stack's already-warmed dependencies and outputs.
        os.environ['GRADLE_USER_HOME']=b.cache_root+'/current'
        helper.CONFIG['java_home']='/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home'
    else:helper.CONFIG['java_home']=b.JDK21
    return original_isolated(helper,identity,host,project,filtered,run_id=run_id)
b.frozen.isolated_build=invoke

if __name__=='__main__':
    original_lease=b.frozen.lease; lock_path=b.h.STATE/'worker-gradle.lock'
    started=time.monotonic()
    with contextlib.ExitStack() as stack:
        for variants in json.loads((b.HERE/'snapshots.json').read_text())['snapshots'].values():
            for identity in variants.values():stack.enter_context(workspace_storage.snapshot_pin(b.h.STATE,identity))
        owned=stack.enter_context(original_lease(lock_path))
        for index in range(1,max(3,b.h.worker_slot_count())):
            stack.enter_context(original_lease(b.h.STATE/(b.h.slot_lock_name(index)+'.lock')))
        cleanup=workspace_storage.cleanup(wait=True,held_locks=('worker-gradle','worker-slot-1','worker-slot-2'))
        (b.HERE/'probe-cleanup.json').write_text(json.dumps(cleanup,indent=2)+'\n')
        stack.enter_context(workspace_storage.graph_activity(b.h.STATE))
        (b.HERE/'probe-reservation.json').write_text(json.dumps({'queueSeconds':time.monotonic()-started,
             'epoch':time.time(),'probeSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},indent=2)+'\n')
        @contextlib.contextmanager
        def reentrant(path):
            if path==lock_path:yield owned
            else:
                with original_lease(path) as lock:yield lock
        b.frozen.lease=reentrant
        for variant in ('september','current'):
            treatment='plain';b.run(variant,'plain-cold',0)
            for rep in range(1,4):b.run(variant,'plain-warm',rep)
            if variant=='current':
                treatment='export'
                for rep in range(1,4):b.run(variant,'export-warm',rep)
            treatment='cc'
            try:
                b.run(variant,'cc-store',0)
                for rep in range(1,4):b.run(variant,'cc-warm',rep)
            except RuntimeError as error:
                print(f'Configuration-cache treatment for {variant}: {error}',flush=True)
        treatment='plain';b.run('current-native','native-seed',0)
        for rep in range(1,4):b.run('current-native','native-warm',rep)
