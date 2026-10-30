#!/usr/bin/env python3
"""Fixed-runtime projection microbenchmark; deliberately excludes RPC and builds."""
import contextlib, hashlib, json, shutil, statistics, subprocess, time
from pathlib import Path
import performance_followup as p

HERE = Path(__file__).resolve().parent
TEMP = p.DEV / 'tmp/dependeasy-run-projection-20261029'
JDK = '/Users/ovd/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home'
private = p.h.BASE_REMOTE + '/.remote-dev/run-projection-20261029'
started = time.monotonic()
with contextlib.ExitStack() as stack:
    stack.enter_context(p.frozen.lease(p.GLOBAL_STATE / 'worker-slot-1.lock'))
    stack.enter_context(p.workspace_storage.graph_activity(p.GLOBAL_STATE))
    stack.enter_context(p.h.worker_slot(1))
    admission = time.monotonic() - started
    p.workspace_storage.automatic(p.h, held_locks={'worker-slot-1'})
    runtime = TEMP / 'runtime'
    runtime.mkdir(parents=True, exist_ok=True)
    sources = [p.DEV / 'bestbuds/modules/kernel/build/libs/kernel.jar',
               p.DEV / 'reaktor/reaktor-tooling/build/libs/reaktor-tooling-jvm-2026.1.6-SNAPSHOT.jar']
    sources += sorted((p.DEV / 'bestbuds/targets/reaktorCli/build/install/reaktor/lib').glob('*.jar'))
    hashes = {}
    for index, source in enumerate(sources):
        target = runtime / (f'{index:03d}-' + source.name)
        shutil.copyfile(source, target)
        hashes[target.name] = hashlib.sha256(target.read_bytes()).hexdigest()
    assert len(hashes) > 2
    p.remote('''import json,sys
from pathlib import Path
path=Path(sys.argv[1]);assert path.name=='run-projection-20261029'
assert not path.exists(), 'Private benchmark directory already exists'
path.mkdir(parents=True);print(json.dumps({'created':str(path)}))
''', private)
    subprocess.run(['scp', '-qr', str(runtime), str(HERE/'RunProjectionBenchmark.java'),
                    str(HERE/'run-projection-fixture.json'), p.h.HOST+':'+private+'/'], check=True)
    result = p.remote('''import hashlib,json,os,platform,subprocess,sys,time
from pathlib import Path
root=Path(sys.argv[1]);jdk=Path(sys.argv[2]);jars=sorted((root/'runtime').glob('*.jar'))
def hashes():return {jar.name:hashlib.sha256(jar.read_bytes()).hexdigest() for jar in jars}
before=hashes();cp=os.pathsep.join(map(str,jars))
subprocess.run([str(jdk/'bin/javac'),'-cp',cp,'-d',str(root),str(root/'RunProjectionBenchmark.java')],check=True)
loadBefore=os.getloadavg();start=time.monotonic()
with (root/'samples.jsonl').open('w') as out:
 subprocess.run([str(jdk/'bin/java'),'-Xms2g','-Xmx2g','-cp',str(root)+os.pathsep+cp,
                 'RunProjectionBenchmark',str(root/'run-projection-fixture.json')],stdout=out,check=True)
elapsed=time.monotonic()-start
assert before==hashes(), 'Runtime changed during measurement'
result={'worker':platform.node(),'slot':1,'platform':platform.platform(),
 'hardware':subprocess.check_output(['/usr/sbin/sysctl','hw.model','hw.ncpu','hw.memsize'],text=True),
 'java':subprocess.check_output([str(jdk/'bin/java'),'-version'],stderr=subprocess.STDOUT,text=True),
 'loadBefore':loadBefore,'loadAfter':os.getloadavg(),'measurementWallSeconds':elapsed,
 'runtimeHashes':before,'runtimeUnchanged':True,
 'fixtureSha256':hashlib.sha256((root/'run-projection-fixture.json').read_bytes()).hexdigest(),
 'harnessSha256':hashlib.sha256((root/'RunProjectionBenchmark.java').read_bytes()).hexdigest()}
print(json.dumps(result))
''', private, JDK)
    assert result['runtimeHashes'] == hashes
    subprocess.run(['scp','-q',p.h.HOST+':'+private+'/samples.jsonl',str(HERE/'run-projection-samples.jsonl')],check=True)
    rows=[json.loads(line) for line in (HERE/'run-projection-samples.jsonl').read_text().splitlines()]
    summary={}
    for scope in ('list','show'):
        selected=[row for row in rows if row['scope']==scope and row['rep']>0]
        assert len(selected)==16 and all(row['equal'] for row in selected)
        assert len({row['sha256'] for row in selected})==1
        values={variant:[row['seconds'] for row in selected if row['variant']==variant]
                for variant in ('snapshot','bounded')}
        old,new=(statistics.median(values[variant]) for variant in ('snapshot','bounded'))
        summary[scope]={'samplesSecondsPerCall':values,'beforeMedianSeconds':old,'afterMedianSeconds':new,
                        'speedup':old/new,'reductionPercent':100*(1-new/old),
                        'outputSha256':selected[0]['sha256'],'rows':selected[0]['rows']}
    result.update(admissionSeconds=admission,summary=summary,
        scope='Projection-only synthetic microbenchmark; excludes ledger, RPC, UI, Gradle and end-to-end build time.',
        fixture={'tasks':2000,'runs':250,'events':5000},
        warmupPairsPerScope=5,measuredPairsPerScope=8,callsPerSample=4)
    cleanup=p.remote('''import json,shutil,sys
from pathlib import Path
root=Path(sys.argv[1]);assert root.name=='run-projection-20261029'
shutil.rmtree(root);print(json.dumps({'removed':str(root)}))
''',private)
    result['remoteCleanup']=cleanup
    (HERE/'run-projection-proof.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'admissionSeconds':admission,'summary':summary}),flush=True)
