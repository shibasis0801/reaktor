#!/usr/bin/env python3
"""Summarize retained observations without pooling distinct experiments."""
import json, statistics, hashlib, re
from pathlib import Path
here=Path(__file__).resolve().parent
rows=[json.loads(line) for line in (here/'performance-results.jsonl').read_text().splitlines()]
groups={}
for row in rows:
    key=row['variant']+':'+('default:' if row['configurationCache'] is None else '')+row['phase']
    groups.setdefault(key,[]).append(row)
summary={}
for key,all_values in groups.items():
    values=[v for v in all_values if v['code']==0 and v['profileSeconds'].get('Total Build Time') is not None]
    if not values:
        summary[key]={'attempts':len(all_values),'successfulSamples':sum(v['code']==0 for v in all_values),'profileTimingsAvailable':False,'gradleConsoleSeconds':[v.get('gradleConsoleSeconds') for v in all_values],'reservedEndToEndSeconds':{'median':statistics.median([v['endToEndSeconds'] for v in all_values if v['code']==0])}}
        continue
    def series(f):
        a=[f(v) for v in values]
        return {'median':statistics.median(a),'min':min(a),'max':max(a),'values':a}
    summary[key]={'samples':len(values),'attempts':len(all_values),'allSuccessful':all(v['code']==0 for v in all_values),
        'gradleSeconds':series(lambda v:v['profileSeconds']['Total Build Time']),
        'reservedEndToEndSeconds':series(lambda v:v['endToEndSeconds']),
        'collectedFiles':series(lambda v:v['collectedFiles']),
        'collectedBytes':series(lambda v:v['collectedBytes']),
        'transportSeconds':{k:series(lambda v:v['transport'].get(k,0)) for k in ['sync_seconds','execution_seconds','collection_seconds','publication_seconds','publication_queue_seconds']}}
checks=[]
for rep in range(1,4):
    by={(v['variant'],v['phase']):v for v in rows if v['rep']==rep and v['configurationCache'] is False}
    for phase in ('cold','warm','incremental'):
        if all((variant,phase) in by for variant in ('before','after')):
            b,a=[by[(variant,phase)]['jars'] for variant in ('before','after')]
            checks.append({'rep':rep,'phase':phase,'sameJarBytes':[(j['path'],j['sha256']) for j in b]==[(j['path'],j['sha256']) for j in a],
                           'sameClasses':[j['classes'] for j in b]==[j['classes'] for j in a]})
    for variant in ('before','after'):
        if all((variant,phase) in by for phase in ('warm','incremental')):
            b,a=[by[(variant,phase)]['jars'][0]['classes'] for phase in ('warm','incremental')]
            checks.append({'rep':rep,'variant':variant,'editChangedClasses':[k for k in sorted(set(b)|set(a)) if b.get(k)!=a.get(k)]})
cache=[]
for directory in sorted((here/'performance-runs').iterdir()):
    path=directory/'result.json'
    if not path.exists():continue
    row=json.loads(path.read_text())
    if row['configurationCache'] is False:continue
    log=(directory/'worker.log').read_text()
    cache.append({'case':directory.name,'code':row['code'],
                  'reused':'Reusing configuration cache' in log,
                  'stored':'Configuration cache entry stored' in log,
                  'problems':re.findall(r'.*(?:configuration cache problems|Configuration cache entry discarded|problems were found).*',log),
                  'jarTask':re.findall(r'^> Task :reaktor-core:jvmJar.*',log,re.M),
                  'compileTask':re.findall(r'^> Task :reaktor-core:compileKotlinJvm.*',log,re.M),
                  'exportTask':re.findall(r'^> Task :exportBuildModel.*',log,re.M)})
result={'source':'performance-results.jsonl','sourceSha256':hashlib.sha256((here/'performance-results.jsonl').read_bytes()).hexdigest(),
        'samples':len(rows),'groups':summary,'artifactChecks':checks,'configurationCacheChecks':cache,
        'timingNote':'Per-row queue_seconds is cumulative process elapsed in this reservation harness; use performance-reservation.json for admission. Publication queue is separately measured. No queue percentiles are inferred.'}
(here/'performance-summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'samples':len(rows),'groups':{k:{m:v[m] for m in ('samples','gradleSeconds','reservedEndToEndSeconds') if m in v} for k,v in summary.items()},'artifactChecks':checks,'configurationCacheChecks':cache},indent=2))
