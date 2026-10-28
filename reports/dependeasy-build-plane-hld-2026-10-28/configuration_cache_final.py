#!/usr/bin/env python3
"""Requalify the final compatible candidate using the retained campaign controls."""
import contextlib, hashlib, json, time
import performance_followup as p

original = p.STATE
D = json.loads((p.HERE / 'performance-snapshots.json').read_text())
with contextlib.ExitStack() as stack:
    stack.enter_context(p.workspace_storage.snapshot_pin(original, D['variants']['after']['base']))
    stack.enter_context(p.workspace_storage.graph_activity(original))
    p.h.ROOT = p.materialize(D['variants']['after']['base'])
    changed = [
        'dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/KernelSpec.kt',
        'dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/analysis/KotlinAnalysisCompile.kt',
        'dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/workspace/DeclarationSites.kt',
        'dependeasy/gradle/src/test/kotlin/dev/shibasis/dependeasy/DslEntryPointsTest.kt',
        'gradle.properties',
    ]
    source_hashes = {}
    for relative in changed:
        source = p.DEV / 'reaktor' / relative
        p.frozen.copy_source(source, p.h.ROOT / 'reaktor' / relative)
        source_hashes[relative] = hashlib.sha256(source.read_bytes()).hexdigest()
    base = p.frozen.freeze(p.h)
    stack.enter_context(p.workspace_storage.snapshot_pin(original, base))
    source = p.h.ROOT / 'reaktor' / p.EDIT
    text = source.read_text()
    assert text.count('Clock.System.now().toEpochMilliseconds()') == 1
    source.write_text(text.replace('Clock.System.now().toEpochMilliseconds()', '(Clock.System.now().toEpochMilliseconds() + 1L)'))
    body = p.frozen.freeze(p.h)
    stack.enter_context(p.workspace_storage.snapshot_pin(original, body))
    source.write_text(text)
    declaration = p.h.ROOT / 'reaktor/build.gradle.kts'
    declaration.write_text(declaration.read_text() + '\n// Final compatible configuration-cache freshness qualification.\n')
    edited = p.frozen.freeze(p.h)
    stack.enter_context(p.workspace_storage.snapshot_pin(original, edited))
    D['variants']['after'] = {'base': base, 'incremental': body}
    started = time.monotonic()
    stack.enter_context(p.frozen.lease(original / 'worker-slot-1.lock'))
    stack.enter_context(p.h.worker_slot(1))
    p.STATE = p.TEMP / 'state'
    p.h.STATE = p.STATE
    reservation = {'queueSeconds': time.monotonic() - started, 'slot': 1,
                   'remote': p.h.REMOTE, 'snapshots': {'base': base, 'bodyEdit': body, 'declarationEdit': edited},
                   'sourceHashes': source_hashes,
                   'scriptSha256': hashlib.sha256(p.Path(__file__).read_bytes()).hexdigest(),
                   'scope': 'Final compatible Process analysis spec; typed JVM archive and Reaktor cache default retained.'}
    (p.HERE / 'configuration-cache-final-reservation.json').write_text(json.dumps(reservation, indent=2) + '\n')
    rows = [p.run(D, 'after', 'cold', 5, cc=None, profile=False)]
    for rep in range(1, 4):
        rows.append(p.run(D, 'after', 'final-warm', rep, cc=None, profile=False))
    rows.append(p.run(D, 'after', 'final-body-incremental', 1, cc=None, profile=False))
    D['variants']['after']['incremental'] = edited
    rows.append(p.run(D, 'after', 'final-declaration-incremental', 1, cc=None, profile=False))
    rows.append(p.run(D, 'after', 'final-restored', 1, cc=None, profile=False))
    (p.HERE / 'configuration-cache-final-results.json').write_text(json.dumps(rows, indent=2) + '\n')
