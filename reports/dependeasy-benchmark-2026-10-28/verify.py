#!/usr/bin/env python3
"""Acceptance checks on the completed measured dataset, not benchmark implementation tests."""
import json
from pathlib import Path

HERE=Path(__file__).resolve().parent

def main():
    data=json.loads((HERE/'summary.json').read_text())
    rows=[r for r in data['rows'] if r['phase'] in ('cold','warm','incremental') and 'excludedReason' not in r]
    raw={r['receipt']['run_id']:r for r in map(json.loads,(HERE/'results.jsonl').read_text().splitlines())}
    checks=[]
    def check(name,passed,detail=None):checks.append({'name':name,'passed':bool(passed),'detail':detail})
    expected={(v,p,r) for v in ('september','current') for p in ('cold','warm','incremental') for r in (1,2,3)}
    keys=[(r['variant'],r['phase'],r['rep']) for r in rows]
    check('Exactly 18 measured runs and one of each prescribed cell',len(keys)==18 and set(keys)==expected,keys)
    check('All measured builds and both source-content guards succeeded',all(r['status']=='succeeded' and r.get('sourceVerified') for r in rows))
    check('No primary FROM-CACHE task outcomes',all(r.get('fromCacheConsole')==0 for r in rows))
    check('No measured dependency download lines',all(r.get('downloads')==0 for r in rows),{r['run']:r.get('downloads') for r in rows})
    check('Warm target compilation up to date',all(r.get('coreCompileUpToDate') is True for r in rows if r['phase']=='warm'))
    check('Edited target compilation executed',all(r.get('coreCompileUpToDate') is False for r in rows if r['phase']=='incremental'))
    check('Common Gradle runtime JDK',all(r.get('jdk')=='21.0.8' for r in rows))
    check('Native Gradle versions retained',all(r.get('gradleVersion')=={'september':'8.14.2','current':'9.7.0'}[r['variant']] for r in rows))
    adapter={raw[r['run']]['receipt'].get('adapterDigest') for r in rows}
    check('One shared router adapter digest',len(adapter)==1,sorted(adapter))
    args=[raw[r['run']]['receipt']['arguments'] for r in rows]
    check('Identical requested argument list',all(x==args[0] for x in args) if args else False,args[:1])
    roots=set();heaps=set();families=set()
    for r in rows:
        base=HERE/'runs'/r['run']
        m=json.loads((base/'outputs/build/reports/benchmark/metrics.json').read_text())
        t=json.loads((base/'transport.json').read_text())
        roots.add(m['root']);heaps.add(m['heapMaxBytes']);families.add(t.get('artifact_family'))
    check('One exact worker execution directory',roots=={'/Users/ovd/dev/offload/.remote-dev/slots/worker/reaktor'},sorted(roots))
    check('8 GiB maximum Gradle heap',heaps=={8*1024**3},sorted(heaps))
    check('Every measured execution routed to worker',all(raw[r['run']]['receipt']['host']=='worker' for r in rows))
    check('Same native collection family',len(families)==1,list(families))
    artifact_checks=[c for c in data['incrementalArtifactChecks'] if c['scope']=='real-project']
    check('Six incremental artifacts changed only TimeKt.class',len(artifact_checks)==6 and all(
        not c['addedClasses'] and not c['removedClasses'] and c['changedClasses']==['dev/shibasis/reaktor/core/utils/TimeKt.class']
        for c in artifact_checks),artifact_checks)
    result={'passed':all(c['passed'] for c in checks),'checks':checks}
    (HERE/'verification.json').write_text(json.dumps(result,indent=2)+'\n')
    for c in checks:print(('PASS' if c['passed'] else 'FAIL'),c['name'])
    raise SystemExit(0 if result['passed'] else 1)

if __name__=='__main__':main()
