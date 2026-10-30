"""Qualify matched clients through the normal reserved secondary-slot path."""
import contextlib, hashlib, importlib.machinery, importlib.util, json, sys, time
from pathlib import Path
ROOT = Path('/Users/ovd/dev')
REPORT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools'))
loader = importlib.machinery.SourceFileLoader('fingerprint_package_router', str(ROOT / 'tools/remote-dev'))
h = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
sys.modules[loader.name] = h
loader.exec_module(h)
import remote_build, workspace_storage
owned = [ROOT / name for name in (
    'reaktor/reaktor-service/src/commonMain/kotlin/dev/shibasis/reaktor/service/Operation.kt',
    'reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModel.kt',
    'reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModelKernel.kt')]
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
before = {str(path): digest(path) for path in owned}
start = time.monotonic()
with contextlib.ExitStack() as stack:
    stack.enter_context(workspace_storage.graph_activity(h.STATE))
    lock = stack.enter_context(remote_build.lease(h.STATE / 'worker-slot-1.lock'))
    admission = time.monotonic() - start
    code = h.slot_build('bestbuds', Path('.'), [':kernel:test', ':reaktorCli:test', ':kernel:verifyKernelBoundary',
        ':kernel:agentDist', ':reaktorCli:installDist', '--continue', '--no-daemon', '--console=plain'], 1, lock)
receipt = json.loads((h.STATE / 'logs' / (h.LAST_RUN_ID + '.json')).read_text())
result = dict(runId=h.LAST_RUN_ID, workerSlot=1, exitCode=code, reservedAdmissionSeconds=admission,
    endToEndSeconds=time.monotonic()-start, receipt=receipt, ownedSourceSha256=before,
    ownedSourceStillMatches=before=={str(path):digest(path) for path in owned},
    scope='Normal slot_build/collection/publication; shared source, no frozen whole-workspace claim.')
(REPORT / 'schema-fingerprint-client-package-router-proof.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k not in {'receipt','ownedSourceSha256'}}), flush=True)
raise SystemExit(code)
