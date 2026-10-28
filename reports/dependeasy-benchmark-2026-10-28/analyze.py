#!/usr/bin/env python3
"""Derive tables from raw profiles and receipts; never infer a Gradle duration from wrapper time."""
from pathlib import Path
from html.parser import HTMLParser
import csv,json,re,statistics
HERE=Path(__file__).resolve().parent

class Tables(HTMLParser):
    def __init__(self):
        super().__init__();self.section='';self.heading=None;self.row=None;self.cell=None;self.rows=[]
    def handle_starttag(self,tag,attrs):
        if tag=='h2':self.heading=''
        elif tag=='tr':self.row=[]
        elif tag in ('td','th'):self.cell=''
    def handle_data(self,data):
        if self.heading is not None:self.heading+=data
        if self.cell is not None:self.cell+=data
    def handle_endtag(self,tag):
        if tag=='h2' and self.heading is not None:self.section=self.heading.strip();self.heading=None
        elif tag in ('td','th') and self.cell is not None:
            if self.row is not None:self.row.append(self.cell.strip())
            self.cell=None
        elif tag=='tr' and self.row is not None:self.rows.append((self.section,self.row));self.row=None

def seconds(value):
    return sum(float(number)*{'h':3600,'m':60,'s':1}[unit] for number,unit in re.findall(r'([\d.]+)([hms])',value))

def compiler_processes(snapshot):
    found={}
    for line in snapshot.get('compilerProcesses',[]):
        parts=line.split(None,3)
        if len(parts)!=4 or not parts[3].split()[0].endswith('/java'):continue
        cpu=sum(float(n)*60**i for i,n in enumerate(reversed(parts[1].split(':'))))
        found[int(parts[0])]={'cpu':cpu,'rssKiB':int(parts[2])}
    return found

def derive(row):
    folder=HERE/'runs'/row['receipt']['run_id']
    out={'run':folder.name,'variant':row['variant'],'phase':row['phase'],'rep':row['repetition'],
         'status':row['receipt']['status'],'e2e':row['endToEndSeconds']}
    log=(folder/'worker.log').read_text(errors='replace') if (folder/'worker.log').exists() else ''
    profile_names=re.findall(r'See the profiling report at: .*?/([^/\s]+\.html)',log)
    profiles=[folder/'outputs/build/reports/profile'/profile_names[-1]] if profile_names else []
    profiles=[p for p in profiles if p.exists()]
    if not profiles:return out
    out['profile']=str(profiles[-1].relative_to(folder))
    parsed=Tables();parsed.feed(profiles[-1].read_text())
    totals={r[0]:seconds(r[1]) for section,r in parsed.rows if section=='Summary' and len(r)==2 and r[0]!='Description'}
    out.update(gradle=totals['Total Build Time'],startup=totals.get('Startup'),
               settings=totals.get('Settings and buildSrc'),configuration=totals.get('Configuring Projects'),
               profileTaskAggregate=totals.get('Task Execution'))
    out['nonGradle']=out['e2e']-out['gradle']
    out.update(queue=row['receipt'].get('queueSeconds'),materialize=row['receipt'].get('materializeSeconds'))
    transport=json.loads((folder/'transport.json').read_text())
    out.update(sync=transport.get('sync_seconds'),execution=transport.get('execution_seconds'),
               collection=transport.get('collection_seconds'),publication=transport.get('publication_seconds'),
               publicationQueue=transport.get('publication_queue_seconds'),collectedBytes=row['collectedBytes'],
               collectedFiles=row['collectedFiles'])
    out['executionOutsideGradle']=out['execution']-out['gradle']
    metric=folder/'outputs/build/reports/benchmark/metrics.json'
    if metric.exists() and row.get('diagnostics',{}).get('observerEnabled',True):
        m=json.loads(metric.read_text());out.update(observerCpu=m['processCpuSeconds'],gc=m['gcSeconds'],
             heapPoolPeakGiB=m['sumHeapPoolPeakBytes']/1024**3,gradleVersion=m['gradleVersion'],jdk=m['javaVersion'])
        compiler=next((t for t in m['tasks'] if t['path']==':reaktor-core:compileKotlinJvm'),None)
        if compiler:out.update(coreCompile=compiler['seconds'],coreCompileUpToDate=compiler['upToDate'])
        before=row.get('diagnostics',{}).get('before',{});after=row.get('diagnostics',{}).get('after',{})
        prior=compiler_processes(before);later=compiler_processes(after)
        out['observedCompilerCpu']=sum(max(0,p['cpu']-prior.get(pid,{}).get('cpu',0)) for pid,p in later.items())
        out['observedCpu']=out['observerCpu']+out['observedCompilerCpu']
        out['compilerPidsBefore']=sorted(prior);out['compilerPidsAfter']=sorted(later)
        out['compilerPidsGone']=sorted(prior.keys()-later.keys())
        pids={m['pid']}|later.keys()
        if row['phase'] not in ('cold','prepare'):pids|=prior.keys()
        samples=[]
        trace=HERE/'worker-samples.jsonl'
        if not trace.exists():trace=HERE.parent/'worker-samples.jsonl'
        if trace.exists():
            for line in trace.read_text().splitlines():
                try:sample=json.loads(line)
                except json.JSONDecodeError:continue
                if before.get('epoch',0)<=sample.get('epoch',0)<=after.get('epoch',0):samples.append(sample)
        samples.extend([before,after])
        rss=[]
        for sample in samples:
            total=0;matched=[]
            for line in sample.get('jvmProcesses',[]):
                parts=line.split(None,4)
                if len(parts)==5 and int(parts[0]) in pids:total+=int(parts[2]);matched.append(int(parts[0]))
            if matched:rss.append(total)
        if rss:out['sampledPeakCombinedRssGiB']=max(rss)/1024**2;out['rssSampleCount']=len(rss)
    artifacts=row.get('artifactVerification',[])
    if artifacts:out.update(jarBytes=sum(a['bytes'] for a in artifacts),classes=sum(len(a['classes']) for a in artifacts))
    out['sourceVerified']=row['receipt'].get('workerInputsUnchanged') is True and row['receipt'].get('inputsUnchanged') is True
    report_names=re.findall(r'Kotlin build report is written to .*?/([^/\s]+\.txt)',log)
    if report_names:
        report=folder/'outputs/build/reports/kotlin-build'/report_names[-1]
        if report.exists():
            text=report.read_text()
            out['kotlinReport']=str(report.relative_to(folder))
            out['compilerReportModes']=sorted(set(re.findall(r'^\s*((?:Non incremental|Non-incremental|Incremental|In-process|REBUILD_REASON:)[^\n]*)',text,re.M)))
            lines=re.findall(r'Number of lines analyzed:\s*(\d+)',text)
            if lines:out['compilerLinesAnalyzed']=max(map(int,lines))
    out['tasksConsole']=row['receipt'].get('taskOutcomes')
    all_tasks=[]
    if row.get('diagnostics',{}).get('observerEnabled',True):
        for p in (folder/'outputs').glob('**/build/reports/benchmark/metrics.json'):
            m=json.loads(p.read_text())
            for t in m['tasks']:all_tasks.append(dict(t,build=m['root']))
        out['observedTasks']=len(all_tasks)
        out['tasksDidWork']=sum(bool(t['didWork']) for t in all_tasks)
        out['topTasks']=sorted(all_tasks,key=lambda t:t['seconds'],reverse=True)[:8]
    return out

if __name__=='__main__':
    raw=[json.loads(line) for line in (HERE/'results.jsonl').read_text().splitlines()]
    rows=[derive(row) for row in raw]
    exclusions=json.loads((HERE/'excluded-runs.json').read_text()) if (HERE/'excluded-runs.json').exists() else {}
    for row in rows:
        if row['run'] in exclusions:row['excludedReason']=exclusions[row['run']]
    (HERE/'derived.json').write_text(json.dumps(rows,indent=2)+'\n')
    fields=['run','variant','phase','rep','status','gradle','e2e','nonGradle','queue','materialize','sync',
            'execution','collection','publication','startup','settings','configuration','coreCompile',
            'observerCpu','gc','heapPoolPeakGiB','observedTasks','tasksDidWork','collectedBytes','jarBytes','classes']
    with (HERE/'timings.csv').open('w') as f:
        writer=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore');writer.writeheader();writer.writerows(rows)
    for variant in sorted({r['variant'] for r in rows}):
        for phase in ('prepare','cold','warm','incremental'):
            group=[r for r in rows if r['variant']==variant and r['phase']==phase and 'gradle' in r and 'excludedReason' not in r]
            if group:
                print(variant,phase,len(group), {k:round(statistics.median(r[k] for r in group),3) for k in ('gradle','e2e','nonGradle','coreCompile','observerCpu','gc','heapPoolPeakGiB') if all(k in r for r in group)})
