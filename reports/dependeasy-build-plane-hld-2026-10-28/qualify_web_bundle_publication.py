"""Exercise the normal build/collection/publication path under secondary-slot lease."""
import contextlib
import hashlib
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import sys
import time

ROOT = Path('/Users/ovd/dev')
REPORT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools'))
loader = importlib.machinery.SourceFileLoader('bundle_publication_router', str(ROOT / 'tools/remote-dev'))
h = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
sys.modules[loader.name] = h
loader.exec_module(h)
import remote_build
import workspace_storage

owned = [ROOT / name for name in (
    'tools/remote-dev', 'tools/hosts_contract.py', 'tools/test_remote_router.py',
    'reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModel.kt',
    'reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModelKernel.kt')]
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
before = {str(path): digest(path) for path in owned}
probe = ROOT / 'bestbuds/targets/appWeb/dist/client/assets/index-dependeasy-publication-probe.js'
assert not probe.exists()
probe.write_text('/* owned obsolete hashed-asset publication probe */\n')
probe_hash = digest(probe)
start = time.monotonic()
try:
    with contextlib.ExitStack() as stack:
        stack.enter_context(workspace_storage.graph_activity(h.STATE))
        lock = stack.enter_context(remote_build.lease(h.STATE / 'worker-slot-1.lock'))
        admission = time.monotonic() - start
        code = h.slot_build('bestbuds', Path('.'), [':appWeb:appWebBundle', ':appWeb:budget',
                            ':exportBuildModel', '--no-daemon', '--console=plain'], 1, lock)
    receipt = json.loads((h.STATE / 'logs' / (h.LAST_RUN_ID + '.json')).read_text())
    assert code == 0, receipt.get('copy_error')
    snapshot = h.STATE / 'artifacts' / h.LAST_RUN_ID / 'bestbuds'
    policy = h.read_hosts_contract(snapshot)
    assert 'targets/appWeb/dist' in policy['bundleDirectories'], policy['bundleDirectories']
    assert not probe.exists(), 'Whole-directory publication failed to remove the obsolete asset'
    bundle = Path('targets/appWeb/dist')
    tree = lambda base: {str(p.relative_to(base)): digest(p) for p in base.rglob('*') if p.is_file()}
    staged, published = tree(snapshot / bundle), tree(ROOT / 'bestbuds' / bundle)
    assert staged == published, 'Published bundle differs from the exact collected producer'
    after = {str(path): digest(path) for path in owned}
    assert before == after, 'Owned implementation changed during qualification'
    result = dict(runId=h.LAST_RUN_ID, workerSlot=1, exitCode=code,
                  reservedAdmissionSeconds=admission, endToEndSeconds=time.monotonic() - start,
                  obsoleteProbeRemoved=True, collectedEqualsPublished=True,
                  bundleFiles=len(staged), bundleDirectories=policy['bundleDirectories'],
                  receipt=receipt, ownedSourceSha256=before,
                  scope='Normal slot_build, typed export, worker build, collection and local publication. Shared source; no frozen whole-workspace claim.')
    (REPORT / 'web-bundle-real-publication-proof.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: value for key, value in result.items() if key not in {'receipt', 'ownedSourceSha256'}}), flush=True)
finally:
    if probe.exists() and digest(probe) == probe_hash:
        probe.unlink()
