#!/usr/bin/env python3
"""Paired warm builds differing only in the router's source synchronization function."""
import ast
import contextlib
import hashlib
import json
import statistics
import time
import performance_followup as p

old_path=p.TEMP/'router-before-sync.py'
new_path=p.DEV/'tools/remote-dev'
old_text=old_path.read_text();new_text=new_path.read_text()
original=p.STATE
D=json.loads((p.HERE/'continuation-benchmark-snapshots.json').read_text())
identity=D['variants']['after']['base']
functions={}
for name,source in [('before',old_text),('after',new_text)]:
    node=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='sync')
    namespace=dict(p.h.__dict__)
    exec(compile(ast.Module(body=[node],type_ignores=[]),'<'+name+'-sync>','exec'),namespace)
    functions[name]=namespace['sync']
rows=[]
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(original,identity))
    stack.enter_context(p.workspace_storage.graph_activity(original))
    started=time.monotonic()
    stack.enter_context(p.frozen.lease(original/'worker-slot-1.lock'))
    stack.enter_context(p.h.worker_slot(1))
    p.STATE=p.TEMP/'state';p.h.STATE=p.STATE
    reservation={'queueSeconds':time.monotonic()-started,'slot':1,'snapshot':identity,
                 'routerSha256':{name:hashlib.sha256(source.encode()).hexdigest() for name,source in [('before',old_text),('after',new_text)]},
                 'scope':'Only sync function differs; same source, checkout, private Gradle home, normal verification and collection; alternating warm samples.'}
    # The extracted functions use current slot state and the same real helper implementations.
    (p.HERE/'sync-transport-reservation.json').write_text(json.dumps(reservation,indent=2)+'\n')
    for rep in range(1,4):
        for variant in (('before','after') if rep%2 else ('after','before')):
            def synchronize(*args, **kwargs):
                function=functions[variant]
                function.__globals__.update(p.h.__dict__)
                return function(*args, **kwargs)
            p.h.sync=synchronize
            row=p.run(D,'after','steady-warm',40+len(rows),cc=None,profile=False)
            row['syncVariant']=variant
            rows.append(row)
            (p.HERE/'sync-transport-results.json').write_text(json.dumps(rows,indent=2)+'\n')
    jars={r['jars'][0]['sha256'] for r in rows}
    assert len(jars)==1,jars
    assert all(r['code']==0 and r['workerInputsUnchanged'] for r in rows)
    summary={'samples':len(rows),'identicalJarSha256':next(iter(jars)),'medians':{}}
    for variant in ('before','after'):
        selected=[r for r in rows if r['syncVariant']==variant]
        summary['medians'][variant]={k:statistics.median(r['transport'][k] for r in selected)
            for k in ('sync_seconds','execution_seconds','collection_seconds','publication_seconds')}
        summary['medians'][variant].update(gradleSeconds=statistics.median(r['gradleConsoleSeconds'] for r in selected),
            reservedEndToEndSeconds=statistics.median(r['endToEndSeconds'] for r in selected))
    (p.HERE/'sync-transport-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary),flush=True)
