#!/usr/bin/env python3
"""Summarize observed runs without pooling setup, exclusions or sensitivity treatments."""
import importlib.util,json,re,statistics
from pathlib import Path

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('bench_analysis',HERE/'analyze.py')
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)

def load(folder):
    if not (folder/'results.jsonl').exists():return [],[]
    a.HERE=folder
    excluded=json.loads((folder/'excluded-runs.json').read_text())
    raw=[json.loads(line) for line in (folder/'results.jsonl').read_text().splitlines()]
    rows=[]
    for source in raw:
        row=a.derive(source)
        if row['run'] in excluded:row['excludedReason']=excluded[row['run']]
        log=(folder/'runs'/row['run']/'worker.log')
        text=log.read_text(errors='replace') if log.exists() else ''
        row['downloads']=len(re.findall(r'^Downloading ',text,re.M))
        row['fromCacheConsole']=len(re.findall(r'^> Task .* FROM-CACHE$',text,re.M))
        row['configurationCacheReused']='Reusing configuration cache.' in text
        row['configurationCacheStored']='Configuration cache entry stored.' in text
        row['consoleSummary']=re.findall(r'^\d+ actionable tasks:.*$',text,re.M)[-1:]
        row['gradleOutcome']=re.findall(r'^BUILD (?:SUCCESSFUL|FAILED).*$',text,re.M)[-1:]
        rows.append(row)
    return raw,rows

def distribution(rows,key):
    vals=[r[key] for r in rows if isinstance(r.get(key),(int,float))]
    if not vals:return None
    return dict(n=len(vals),median=statistics.median(vals),minimum=min(vals),maximum=max(vals),values=vals)

def main():
    raw,rows=load(HERE)
    fixed_raw,fixed=load(HERE/'fixed-source')
    valid=[r for r in rows if r['status']=='succeeded' and 'excludedReason' not in r]
    fields=['gradle','e2e','nonGradle','queue','materialize','sync','execution','collection','publication',
            'startup','settings','configuration','coreCompile','observerCpu','observedCompilerCpu','observedCpu',
            'sampledPeakCombinedRssGiB','gc','heapPoolPeakGiB',
            'tasksDidWork','observedTasks','collectedBytes','collectedFiles','jarBytes','classes']
    groups={}
    for variant,phase in sorted({(r['variant'],r['phase']) for r in valid}):
        selected=[r for r in valid if r['variant']==variant and r['phase']==phase]
        groups[variant+'/'+phase]={k:distribution(selected,k) for k in fields if distribution(selected,k) is not None}
    paired={}
    for phase in ('cold','warm','incremental'):
        pairs=[]
        for rep in (1,2,3):
            old=next((r for r in valid if r['variant']=='september' and r['phase']==phase and r['rep']==rep),None)
            new=next((r for r in valid if r['variant']=='current' and r['phase']==phase and r['rep']==rep),None)
            if old and new:
                pairs.append({'rep':rep,**{k:{'seconds':new[k]-old[k],'percent':100*(new[k]/old[k]-1)}
                                          for k in ('gradle','e2e','observerCpu') if k in old and k in new}})
        paired[phase]=pairs
    checks=[]
    for scope,source in [('real-project',raw),('identical-source',fixed_raw)]:
        exclusions=json.loads(((HERE if scope=='real-project' else HERE/'fixed-source')/'excluded-runs.json').read_text())
        for variant in ('september','current'):
            for rep in (1,2,3):
                candidates=[r for r in source if r['variant']==variant and r['repetition']==rep and
                            r['receipt']['status']=='succeeded' and r['receipt']['run_id'] not in exclusions]
                warm=next((r for r in candidates if r['phase']=='warm'),None)
                edited=next((r for r in candidates if r['phase']=='incremental'),None)
                if not warm or not edited:continue
                before={k:v for x in warm['artifactVerification'] for k,v in x['classes'].items()}
                after={k:v for x in edited['artifactVerification'] for k,v in x['classes'].items()}
                checks.append({'scope':scope,'variant':variant,'rep':rep,
                    'addedClasses':sorted(after.keys()-before.keys()),'removedClasses':sorted(before.keys()-after.keys()),
                    'changedClasses':sorted(k for k in before.keys()&after.keys() if before[k]!=after[k])})
    out={'groups':groups,'pairedDifferences':paired,'incrementalArtifactChecks':checks,'rows':rows,'fixedSourceRows':fixed}
    (HERE/'summary.json').write_text(json.dumps(out,indent=2)+'\n')
    for name,group in groups.items():
        print(name,{k:round(group[k]['median'],3) for k in ('gradle','e2e','nonGradle','observerCpu','collection') if k in group})
    print('Measured successful nonexcluded runs:',len([r for r in valid if r['phase'] in ('cold','warm','incremental')]))
    print('Incremental class checks:',checks)

if __name__=='__main__':main()
