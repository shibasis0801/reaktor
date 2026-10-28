#!/usr/bin/env python3
"""Exercise real argument-file compilation and the missing-model planning bridge."""
import contextlib
import hashlib
import json
import shutil
import time
import performance_followup as p

original = p.STATE
D = json.loads((p.HERE / 'continuation-benchmark-snapshots.json').read_text())
candidate = D['variants']['after']['base']
rows = []
started = time.monotonic()
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(original, candidate))
    stack.enter_context(p.frozen.lease(original / 'worker-slot-1.lock'))
    stack.enter_context(p.workspace_storage.graph_activity(original))
    stack.enter_context(p.h.worker_slot(1))
    p.h.ROOT = p.materialize(candidate)
    fixture = 'dependeasy/gradle/src/test/kotlin/dev/shibasis/dependeasy/GradlePlanningTest.kt'
    p.frozen.copy_source(p.DEV / 'reaktor' / fixture, p.h.ROOT / 'reaktor' / fixture)
    candidate = p.frozen.freeze(p.h)
    stack.enter_context(p.workspace_storage.snapshot_pin(original, candidate))
    p.h.STATE = p.TEMP / 'state'
    p.os.environ['GRADLE_USER_HOME'] = p.h.BASE_REMOTE + '/.remote-dev/performance-followup-20261028/after'
    (p.HERE / 'continuation-qualification-reservation.json').write_text(json.dumps({
        'queueSeconds': time.monotonic() - started, 'snapshot': candidate, 'slot': 1,
        'remote': p.h.REMOTE, 'fixtureSha256': hashlib.sha256((p.DEV / 'reaktor' / fixture).read_bytes()).hexdigest(),
    }, indent=2) + '\n')
    for label, tasks in (
        ('analysis-first', [':reaktor-code:compileKotlinAnalysis', ':exportBuildModel']),
        ('analysis-repeat', [':reaktor-code:compileKotlinAnalysis', ':exportBuildModel']),
        ('planning-producer', ['-p', 'dependeasy', ':gradle:test', '--tests', 'dev.shibasis.dependeasy.GradlePlanningTest.bridge plans Gradles selected dependencies and links only the new run']),
    ):
        arguments = tasks + ['--max-workers=6', '--no-daemon', '--no-build-cache', '--console=plain', '--stacktrace',
                            '-Dorg.gradle.java.home=' + p.JDK,
                            '-Dorg.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8']
        dest = p.HERE / 'continuation-qualification' / label
        dest.mkdir(parents=True, exist_ok=True)
        t = time.monotonic()
        with (dest / 'console.log').open('w') as log, contextlib.redirect_stdout(log):
            code = p.h.build('reaktor', p.Path('.'), arguments, frozen=True,
                             verify=lambda: p.frozen.verify_worker(p.h, candidate))
        p.frozen.verify_worker(p.h, candidate)
        receipt = json.loads((p.h.STATE / 'logs' / (p.h.LAST_RUN_ID + '.json')).read_text())
        shutil.copy2(p.h.STATE / 'logs' / (p.h.LAST_RUN_ID + '.log'), dest / 'worker.log')
        evidence = p.remote('''import hashlib,json,sys
from pathlib import Path
root=Path(sys.argv[1]);base=root/'reaktor-code/build/kotlin-analysis';args=base/'compiler.args'
classes={str(p.relative_to(base)):hashlib.sha256(p.read_bytes()).hexdigest() for p in base.rglob('*.class')}
tests=root/'dependeasy/gradle/build/test-results/test/TEST-dev.shibasis.dependeasy.GradlePlanningTest.xml'
print(json.dumps({'argumentFile':{'sha256':hashlib.sha256(args.read_bytes()).hexdigest(),'bytes':args.stat().st_size} if args.is_file() else None,
 'classes':classes,'planningTestXml':tests.read_text() if tests.is_file() else None}))
''', p.h.REMOTE + '/reaktor')
        row = {'label': label, 'code': code, 'snapshot': candidate, 'endToEndSeconds': time.monotonic() - t,
               'receipt': receipt, 'evidence': evidence}
        rows.append(row)
        (dest / 'result.json').write_text(json.dumps(row, indent=2) + '\n')
        (p.HERE / 'continuation-qualification-results.json').write_text(json.dumps(rows, indent=2) + '\n')
        print(json.dumps({'label': label, 'code': code, 'seconds': row['endToEndSeconds'], 'classes': len(evidence['classes'])}), flush=True)
        if code:
            raise RuntimeError(label + ' failed')
        if label.startswith('analysis'):
            assert evidence['argumentFile'] and evidence['classes']
        if label == 'analysis-repeat':
            assert rows[0]['evidence']['classes'] == evidence['classes']
            text = (dest / 'worker.log').read_text()
            assert 'Reusing configuration cache' in text
            assert ':reaktor-code:compileKotlinAnalysis UP-TO-DATE' in text
