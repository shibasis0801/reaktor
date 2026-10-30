#!/usr/bin/env python3
"""Bounded receipt materialization on fixed real history and one reserved worker slot."""
import contextlib, hashlib, json, os, shutil, statistics, subprocess, sys, time, zipfile
from pathlib import Path
import performance_followup as p

HERE = Path(__file__).resolve().parent
TEMP = p.DEV / 'tmp/dependeasy-receipt-ledger-20261029'
PRIVATE = p.h.BASE_REMOTE + '/.remote-dev/receipt-ledger-20261029'
JDK = '/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home'
TOOLING = p.DEV / 'reaktor/reaktor-tooling/build/libs/reaktor-tooling-jvm-2026.1.6-SNAPSHOT.jar'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def manifest(root): return {str(f.relative_to(root)): {'bytes': f.stat().st_size, 'sha256': sha(f)}
                            for f in sorted(root.rglob('*')) if f.is_file() and not f.is_symlink()}

def prepare():
    assert (TEMP / 'before-tooling.jar').is_file()
    runtime = TEMP / 'runtime'; runtime.mkdir(exist_ok=True)
    shutil.copy2(TEMP / 'before-tooling.jar', runtime / '000-tooling.jar')
    for source in sorted((p.DEV / 'bestbuds/targets/reaktorCli/build/install/reaktor/lib').glob('*.jar')):
        if not source.name.startswith('reaktor-tooling-'): shutil.copy2(source, runtime / source.name)
    source = p.DEV / 'bestbuds/build/dependeasy/receipts'
    target = TEMP / 'history/build/dependeasy/receipts'; target.mkdir(parents=True, exist_ok=True)
    entries = {}
    for base in [source, source / 'deploy']:
        for file in sorted(base.glob('*.json')):
            if file.is_symlink() or not file.is_file(): continue
            rel = file.relative_to(source); dest = target / rel; dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, dest)
            digest = sha(dest)
            assert digest == sha(file), 'History changed during capture'
            entries[str(rel)] = {'bytes': dest.stat().st_size, 'sha256': digest}
    proof = {'history': entries, 'historyFiles': len(entries), 'historyBytes': sum(x['bytes'] for x in entries.values()),
             'runtime': manifest(runtime), 'beforeToolingSha256': sha(TEMP / 'before-tooling.jar')}
    (HERE / 'receipt-ledger-inputs.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps({k: v for k,v in proof.items() if k not in {'history','runtime'}}), flush=True)

def run():
    proof = json.loads((HERE / 'receipt-ledger-inputs.json').read_text())
    assert sha(TOOLING) != proof['beforeToolingSha256'], 'Updated tooling has not been built'
    overlay = TEMP / 'after-ledger.jar'
    with zipfile.ZipFile(TOOLING) as source, zipfile.ZipFile(overlay, 'w', zipfile.ZIP_DEFLATED) as out:
        for name in source.namelist():
            if name.startswith(('dev/shibasis/reaktor/tooling/GradleReceiptRunLedger',
                                'dev/shibasis/reaktor/tooling/GradleReceiptEvidence')) and name.endswith('.class'):
                out.writestr(name, source.read(name))
    with zipfile.ZipFile(overlay) as z: proof['overlayEntries'] = {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist()}
    assert proof['overlayEntries']
    proof['afterToolingSha256'] = sha(TOOLING)
    proof['overlaySha256'] = sha(overlay)
    started = time.monotonic()
    with contextlib.ExitStack() as stack:
        stack.enter_context(p.frozen.lease(p.GLOBAL_STATE / 'worker-slot-1.lock'))
        stack.enter_context(p.workspace_storage.graph_activity(p.GLOBAL_STATE))
        stack.enter_context(p.h.worker_slot(1))
        proof['admissionSeconds'] = time.monotonic() - started
        p.workspace_storage.automatic(p.h, held_locks={'worker-slot-1'})
        assert manifest(TEMP / 'runtime') == proof['runtime']
        assert manifest(TEMP / 'history/build/dependeasy/receipts') == proof['history']
        p.remote('''import json,sys
from pathlib import Path
p=Path(sys.argv[1]);assert p.name=='receipt-ledger-20261029';assert not p.exists();p.mkdir(parents=True)
print(json.dumps({'created':str(p)}))
''', PRIVATE)
        subprocess.run(['scp','-qr',str(TEMP/'runtime'),str(TEMP/'history'),str(overlay),
                        str(HERE/'ReceiptLedgerBenchmark.java'),p.h.HOST+':'+PRIVATE+'/'],check=True)
        result = p.remote('''import hashlib,json,os,platform,subprocess,sys,time
from pathlib import Path
root=Path(sys.argv[1]);jdk=Path(sys.argv[2])
def manifest(base):return {str(f.relative_to(base)):{'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in sorted(base.rglob('*')) if f.is_file()}
history=root/'history/build/dependeasy/receipts';before=manifest(history);runtime=manifest(root/'runtime')
subprocess.run([str(jdk/'bin/javac'),'-d',str(root),str(root/'ReceiptLedgerBenchmark.java')],check=True)
load=os.getloadavg();start=time.monotonic()
with (root/'samples.jsonl').open('w') as log:
 result=subprocess.run([str(jdk/'bin/java'),'-Xms2g','-Xmx2g','-cp',str(root),'ReceiptLedgerBenchmark',str(root/'history'),str(root/'runtime'),str(root/'after-ledger.jar')],stdout=log,stderr=subprocess.PIPE,text=True,timeout=900)
elapsed=time.monotonic()-start
print(json.dumps({'exitCode':result.returncode,'stderr':result.stderr[-5000:],'history':before,'historyUnchanged':before==manifest(history),'runtime':runtime,'runtimeUnchanged':runtime==manifest(root/'runtime'),'measurementWallSeconds':elapsed,'loadBefore':load,'loadAfter':os.getloadavg(),'host':platform.node(),'java':subprocess.check_output([str(jdk/'bin/java'),'-version'],text=True,stderr=subprocess.STDOUT),'hardware':subprocess.check_output(['/usr/sbin/sysctl','hw.model','hw.ncpu','hw.memsize'],text=True)}))
''', PRIVATE, JDK)
        subprocess.run(['scp','-q',p.h.HOST+':'+PRIVATE+'/samples.jsonl',str(HERE/'receipt-ledger-samples.jsonl')],check=True)
        assert result.pop('history') == proof['history']
        assert result.pop('runtime') == proof['runtime']
        proof.update(result)
        cleanup = p.remote('''import json,shutil,sys
from pathlib import Path
p=Path(sys.argv[1]);assert p.name=='receipt-ledger-20261029';shutil.rmtree(p);print(json.dumps({'removed':str(p)}))
''', PRIVATE)
        proof['remoteCleanup'] = cleanup
    assert result['exitCode'] == 0 and result['historyUnchanged'] and result['runtimeUnchanged'], result
    rows = [json.loads(line) for line in (HERE/'receipt-ledger-samples.jsonl').read_text().splitlines()]
    proof['summary'] = {}
    for scope in ['list5','list1000','show']:
        measured = [r for r in rows if r['scope']==scope and r['rep']>=0]
        assert len(measured)==8 and len({r['sha256'] for r in measured})==1
        old,new = (statistics.median(r['seconds'] for r in measured if r['variant']==v) for v in ['before','after'])
        proof['summary'][scope]={'beforeMedianSeconds':old,'afterMedianSeconds':new,'speedup':old/new,
                                  'reductionPercent':100*(1-new/old),'outputSha256':measured[0]['sha256'],'rows':measured[0]['rows']}
    proof['scope']='Full ledger reads of fixed real receipt history, isolated bytecode overlay; excludes RPC/CLI, Gradle and build E2E.'
    proof['warmupPairsPerScope']=2;proof['measuredPairsPerScope']=4;proof['workerSlot']=1
    proof['harnessSha256']=sha(HERE/'ReceiptLedgerBenchmark.java')
    (HERE/'receipt-ledger-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps({k:proof[k] for k in ['summary','admissionSeconds','measurementWallSeconds','scope']}),flush=True)

if __name__=='__main__': {'prepare':prepare,'run':run}[sys.argv[1]]()
