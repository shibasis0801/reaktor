#!/usr/bin/env python3
"""Render measured tables from retained evidence; never launches a build."""
import json,statistics
from pathlib import Path

HERE=Path(__file__).resolve().parent
d=json.loads((HERE/'summary.json').read_text())
g=d['groups']
phases=('cold','warm','incremental')
assert all(g[v+'/'+p]['gradle']['n']==3 for v in ('september','current') for p in phases)
def med(v,p,k):return g[v+'/'+p][k]['median']
def span(v,p,k):
    x=g[v+'/'+p][k]
    return f"{x['median']:.2f} ({x['minimum']:.2f}–{x['maximum']:.2f})"
def delta(p,k):return f"+{100*(med('current',p,k)/med('september',p,k)-1):.1f}%"
names={'cold':'Cold build state','warm':'Unchanged warm','incremental':'One-file edit'}
out=['## Measured outcome','',
'All **18/18** prescribed builds succeeded. All source-content guards passed. The',
'current stack is slower in every paired cold, warm and source-edit observation',
'under the common cache-disabled controls. This workload establishes no build-speed',
'benefit under those controls. Capabilities and optimization treatments',
'must be evaluated separately from that finding.','',
'Times are median seconds, with minimum–maximum in parentheses. End to end is',
'**reserved per-run latency**: the initial reservation/admission delay is separate.','',
'| Phase | September Gradle | Current Gradle | Change | September reserved E2E | Current reserved E2E | Change |',
'|---|---:|---:|---:|---:|---:|---:|']
for p in phases:
    out.append(f"| {names[p]} | {span('september',p,'gradle')} | {span('current',p,'gradle')} | {delta(p,'gradle')} | {span('september',p,'e2e')} | {span('current',p,'e2e')} | {delta(p,'e2e')} |")
out+=['','Percentages above compare group medians. The median within-block current-minus-',
'September differences retain the pairing:','',
'| Phase | Gradle paired difference | Reserved E2E paired difference |',
'|---|---:|---:|']
for p in phases:
    r=d['pairedDifferences'][p]
    vals=[]
    for k in ('gradle','e2e'):
        vals.append(f"+{statistics.median(x[k]['seconds'] for x in r):.2f}s / +{statistics.median(x[k]['percent'] for x in r):.1f}%")
    out.append(f"| {names[p]} | {' | '.join(vals)} |")
reservation=json.loads((HERE/'reservation.json').read_text())
out+=['',f"The successful primary reservation plus storage admission took **{reservation['queueSeconds']:.2f}s ({reservation['queueSeconds']/60:.2f} minutes)**.",
'Earlier interrupted admission attempts are retained separately. This campaign was',
'delayed by existing worker/device work and the cleanup admission lease; the number',
'is not a normal queue percentile and is not assigned to either stack. Per-run queue',
'wait once reserved was below one millisecond. All 15 acceptance checks pass in',
'[verification.json](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/verification.json).','',
'## Where the time goes','',
'Median seconds below are independent component summaries. Medians are not additive.','',
'| Component | September cold | Current cold | September warm | Current warm | September edit | Current edit |',
'|---|---:|---:|---:|---:|---:|---:|']
for label,key in (('Snapshot materialization','materialize'),('Source sync','sync'),('Execution outside Gradle profile','executionOutsideGradle'),('Collection','collection'),('Isolated publication','publication'),('Total non-Gradle','nonGradle')):
    cells=[]
    for p in phases:
        for v in ('september','current'):
            if key in g[v+'/'+p]:value=med(v,p,key)
            else:value=statistics.median(r[key] for r in d['rows'] if r['variant']==v and r['phase']==p and 'excludedReason' not in r)
            cells.append(f'{value:.2f}')
    out.append(f"| {label} | {' | '.join(cells)} |")
out+=['','Remaining non-Gradle time includes the common diagnostics, Java/source checks and',
'adapter bookkeeping. Shared-checkout publication contention is absent from this',
'isolated experiment; ordinary developer publication latency can be much larger.','',
'| Cold Gradle profile component | September median | Current median |',
'|---|---:|---:|']
for label,key in (('Startup','startup'),('Settings and buildSrc','settings'),('Configuring projects','configuration'),('Target Kotlin task','coreCompile')):
    out.append(f"| {label} | {med('september','cold',key):.2f}s | {med('current','cold',key):.2f}s |")
out+=['','Included-build work can overlap these profile categories. Current cold runs also',
'compile buildSrc, model, toolchain and Gradle adapter code, whereas September has',
'one convention-plugin compilation. Configuration/build-logic work is a stronger',
'cold-latency lead than the small requested JAR. The identical-source fixture below',
'tests how much target-source growth changes this interpretation.','',
'## Resource, incrementality and output observations','',
'| Median observed metric | September cold / warm / edit | Current cold / warm / edit |',
'|---|---:|---:|']
for label,key,suffix in (('Gradle process CPU seconds','observerCpu','s'),('Gradle GC seconds','gc','s'),('Sum of Gradle heap-pool peak usage','heapPoolPeakGiB',' GiB')):
    out.append('| '+label+' | '+' | '.join(' / '.join(f'{med(v,p,key):.2f}{suffix}' for p in phases) for v in ('september','current'))+' |')
out+=['','These are Gradle-process observations, **not total build CPU or peak build RSS**.',
'No owned compiler process survived the primary boundary samples, so compiler CPU',
'was missed. Sparse RSS samples sometimes caught only JVM startup on short runs;',
'the raw lower bounds are retained but do not support a credible warm/edit RSS',
'comparison. Heap-pool peaks can occur at different times and are not RSS. No energy',
'or financial-efficiency conclusion follows from these measurements.','',
'Every warm target compilation was up to date. All six edit runs executed compilation',
'and changed **only `TimeKt.class`** in the resulting JAR. However, both stacks report',
'the `KT-62686` safety fallback to non-incremental compilation for this edit. Thus the',
'measured edit rebuild is valid, but a single-file compiler incrementality benefit',
'is not demonstrated. The shared fallback is an optimization lead. There were no',
'dependency downloads or FROM-CACHE outcomes in any primary run.','',
'| Requested real artifact / collection | September | Current |',
'|---|---:|---:|',
f"| JAR bytes | {med('september','cold','jarBytes'):,.0f} | {med('current','cold','jarBytes'):,.0f} |",
f"| Class entries | {med('september','cold','classes'):.0f} | {med('current','cold','classes'):.0f} |",
f"| Cold collected bytes | {med('september','cold','collectedBytes'):,.0f} | {med('current','cold','collectedBytes'):,.0f} |",
f"| Cold collected files | {med('september','cold','collectedFiles'):.0f} | {med('current','cold','collectedFiles'):.0f} |",'',
'Native collection uses family `all` on both stacks. Current collects about 16.5 MB',
'for a 167 KB requested JAR, but its measured collection cost is only about 2.4s.',
'A scoped JVM artifact collector is useful; collection alone cannot explain the',
'roughly 32s Gradle cold difference. The real library gained classes/source between',
'commits; its larger JAR is not evidence that build-framework code is embedded in it.','']
section='<!-- results:start -->\n'+'\n'.join(out)+'\n<!-- results:end -->\n\n'
path=HERE/'report.md';text=path.read_text()
if '<!-- results:start -->' in text:
    a=text.index('<!-- results:start -->');z=text.index('<!-- results:end -->',a)+len('<!-- results:end -->\n\n')
    text=text[:a]+section+text[z:]
else:text=text.replace('## Comparison and controls\n',section+'## Comparison and controls\n',1)
path.write_text(text)
