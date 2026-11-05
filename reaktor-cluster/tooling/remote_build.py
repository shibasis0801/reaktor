"""Frozen Gradle invocation; transport and artifact adapters stay in remote-dev."""
import contextlib
import ctypes
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import time
import uuid
import sys
import re
import remote_retention
from concurrent.futures import ThreadPoolExecutor


@contextlib.contextmanager
def try_leases(paths):
    held = []
    acquired = True
    try:
        for path in paths:
            handle = Path(path).open('a')
            held.append(handle)
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                for previous in reversed(held):
                    previous.close()
                held.clear()
                acquired = False
                break
        yield held if acquired else None
    finally:
        for handle in reversed(held):
            handle.close()


def rename_preflight(source, destination):
    source, destination = Path(source), Path(destination)
    for path in (source, destination):
        if not path.is_absolute() or '..' in path.parts:
            raise ValueError('Rename paths must be absolute and normalized')
        if any(parent.is_symlink() for parent in (path, *path.parents)):
            raise ValueError('Rename roots and ancestors must be real directories')
    if not source.is_dir() or os.path.lexists(destination) or not destination.parent.is_dir():
        raise ValueError('Rename requires a real source and absent destination')
    if source.stat().st_dev != destination.parent.stat().st_dev:
        raise ValueError('Rename requires the same volume')
    return {'device': source.stat().st_dev, 'inode': source.stat().st_ino}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def file_identity(path):
    if path.is_symlink():
        return ['link', os.readlink(path)]
    checksum = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            checksum.update(chunk)
    return ['file', checksum.hexdigest(), path.stat().st_mode & 0o777]


def copy_source(source, target):
    if source.is_symlink():
        if os.path.lexists(target): target.unlink()
        target.symlink_to(os.readlink(source))
        return
    if sys.platform == 'darwin':
        if target.exists(): target.unlink()
        if ctypes.CDLL(None).clonefile(os.fsencode(source), os.fsencode(target), 0) == 0:
            return
    shutil.copy2(source, target)


def source_manifest(helper):
    files, repositories = {}, {}
    for name in helper.PROJECTS:
        base = helper.ROOT / name
        for relative in helper.source_files(name):
            path = base / relative
            if path.is_symlink() and not path.resolve().is_relative_to(helper.ROOT):
                raise ValueError(f'Source symlink escapes the workspace: {name}/{relative}')
            files[f'{name}/{relative}'] = file_identity(path)
        if (base / '.git').exists():
            for relative in helper.git_repositories(base):
                repository = base / relative
                repositories[str(Path(name) / relative)] = subprocess.check_output(
                    ['git', '-C', str(repository), 'rev-parse', 'HEAD'], text=True).strip()
    return {'files': files, 'repositories': repositories}


@contextlib.contextmanager
def lease(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield lock


@contextlib.contextmanager
def place_in_line(state):
    """A ticket for the local Gradle lease, held by its locked file while this process waits."""
    line = state / 'local-gradle.line'
    line.mkdir(parents=True, exist_ok=True)
    name = f'{time.time_ns():020d}-{os.getpid()}-{uuid.uuid4().hex[:8]}'
    pending = line / ('.' + name)
    with pending.open('w') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        ticket = pending.rename(line / name)
        try:
            yield ticket
        finally:
            ticket.unlink(missing_ok=True)


def stand_aside(ticket, aside):
    if ticket.exists():
        ticket.write_text('aside' if aside else '')


def first_in_line(ticket):
    """No older ticket is held by a waiter that can take the lease now; tickets of exited waiters are removed."""
    for other in sorted(entry for entry in ticket.parent.iterdir() if not entry.name.startswith('.')):
        if other.name >= ticket.name:
            return True
        try:
            with other.open('r') as handle:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                other.unlink(missing_ok=True)
        except FileNotFoundError:
            continue
        except BlockingIOError:
            try:
                if other.read_text() == 'aside':
                    continue
            except FileNotFoundError:
                continue
            return False
    return True


def try_lock(handle):
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except BlockingIOError:
        return False


@contextlib.contextmanager
def local_lease(state, wait=0.5, ticket=None):
    """The local Gradle lease in arrival order, so a waiter that polls is not overtaken by later blocking waiters."""
    waiting = contextlib.nullcontext(ticket) if ticket is not None else place_in_line(state)
    with waiting as ticket, (state / 'local-gradle.lock').open('a') as lock:
        while not (first_in_line(ticket) and try_lock(lock)):
            time.sleep(wait)
        ticket.unlink(missing_ok=True)
        yield lock


def disk_admission(helper, host):
    minimum = int(helper.CONFIG.get('minimum_free_disk_mib', 1024)) * 1024 * 1024
    if minimum <= 0:
        raise ValueError('minimum_free_disk_mib must be positive')
    local_free = shutil.disk_usage(helper.STATE).free
    observed = {'minimumFreeBytes': minimum, 'localFreeBytes': local_free}
    if local_free < minimum:
        raise ValueError(f'Insufficient local disk headroom: {local_free // 1048576} MiB free; '
                         f'{minimum // 1048576} MiB required for execution and evidence')
    if host == 'worker':
        result = helper.remote_python('''import shutil,sys
print(shutil.disk_usage(sys.argv[1]).free)
''', helper.REMOTE, capture_output=True, text=True)
        worker_free = int(result.stdout.strip())
        observed['workerFreeBytes'] = worker_free
        if worker_free < minimum:
            raise ValueError(f'Insufficient worker disk headroom: {worker_free // 1048576} MiB free; '
                             f'{minimum // 1048576} MiB required')
    return observed


def freeze(helper):
    with lease(helper.STATE / 'capture.lock'):
        remote_retention.require_admission(helper.STATE)
        for _ in range(3):
            manifest = source_manifest(helper)
            identity = digest(manifest)
            print(f'Capturing {len(manifest["files"])} source files: {identity[:12]}', flush=True)
            destination = helper.STATE / 'snapshots' / identity
            if destination.exists():
                verify_sources(destination / 'workspace', manifest)
                return identity
            destination.parent.mkdir(parents=True, exist_ok=True)
            pending = Path(tempfile.mkdtemp(prefix='capture-', dir=destination.parent))
            try:
                workspace = pending / 'workspace'
                for repository, head in manifest['repositories'].items():
                    target = workspace / repository
                    target.parent.mkdir(parents=True, exist_ok=True)
                    subprocess.run(['git', 'clone', '--quiet', '--local', '--no-checkout',
                                    str(helper.ROOT / repository), str(target)], check=True)
                    subprocess.run(['git', '-C', str(target), 'reset', '--quiet', '--mixed', head], check=True)
                for relative in manifest['files']:
                    source, target = helper.ROOT / relative, workspace / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    if source.is_symlink():
                        # Absolute in-workspace links become relocatable within the snapshot.
                        link = os.readlink(source)
                        if os.path.isabs(link):
                            raise ValueError(f'Use a relative source symlink: {relative}')
                        target.symlink_to(link)
                    else:
                        copy_source(source, target)
                verify_sources(workspace, manifest)
                if source_manifest(helper) != manifest:
                    continue
                (pending / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True) + '\n')
                pending.rename(destination)
                return identity
            except (OSError, ValueError):
                if source_manifest(helper) == manifest:
                    raise
            finally:
                if pending.exists():
                    shutil.rmtree(pending)
        raise ValueError('Sources changed during three snapshot attempts; no build started')


def verify_sources(workspace, manifest):
    for relative, expected in manifest['files'].items():
        path = workspace / relative
        if not os.path.lexists(path) or file_identity(path) != expected:
            raise ValueError(f'Snapshot source mismatch: {relative}')


def materialize(helper, identity, host):
    snapshot = helper.STATE / 'snapshots' / identity
    manifest = json.loads((snapshot / 'manifest.json').read_text())
    if digest(manifest) != identity:
        raise ValueError('Snapshot manifest identity mismatch')
    verify_sources(snapshot / 'workspace', manifest)
    slot = remote_retention.configured_slot_roots(helper.STATE, getattr(helper, 'CONFIG', {}))[0] / host
    slot.mkdir(parents=True, exist_ok=True)
    slot.chmod(0o700)
    previous = slot / 'source-manifest.json'
    old = json.loads(previous.read_text())['files'] if previous.exists() else {}
    for relative in old.keys() - manifest['files'].keys():
        path = slot / relative
        if path.is_file() or path.is_symlink():
            path.unlink()
    for repository in manifest['repositories']:
        target = slot / repository / '.git'
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists() and sys.platform == 'darwin':
            helper.run(['/bin/cp', '-cRp', str(snapshot / 'workspace' / repository / '.git'), str(target)])
        else:
            helper.run(['rsync', '-a', '--delete', str(snapshot / 'workspace' / repository / '.git') + '/', str(target) + '/'])
    for relative in manifest['files']:
        source, target = snapshot / 'workspace' / relative, slot / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if os.path.lexists(target) and file_identity(target) == manifest['files'][relative]:
            continue
        if target.is_symlink():
            target.unlink()
        if source.is_symlink():
            if target.exists(): target.unlink()
            target.symlink_to(os.readlink(source))
        else:
            copy_source(source, target)
    verify_sources(slot, manifest)
    previous.write_text(json.dumps(manifest, sort_keys=True) + '\n')
    return slot


def checksums(directory):
    return {str(path.relative_to(directory)): file_identity(path) for path in sorted(directory.rglob('*'))
            if path.is_file() or path.is_symlink()}


def verify_worker(helper, identity):
    manifest = helper.STATE / 'snapshots' / identity / 'manifest.json'
    remote = helper.REMOTE + '/.remote-dev/frozen-source.json'
    helper.run(['scp', '-q', str(manifest), helper.HOST + ':' + remote])
    helper.remote_python('''import hashlib,json,os,sys,subprocess
from pathlib import Path
root=Path(sys.argv[1]); manifest=json.loads(Path(sys.argv[2]).read_text())
for relative,expected in manifest['files'].items():
 p=root/relative
 if not os.path.lexists(p):raise ValueError('Missing frozen source: '+relative)
 if p.is_symlink():actual=['link',os.readlink(p)]
 else:
  checksum=hashlib.sha256()
  with p.open('rb') as source:
   for chunk in iter(lambda:source.read(1048576),b''):checksum.update(chunk)
  actual=['file',checksum.hexdigest(),p.stat().st_mode&0o777]
 if actual!=expected:raise ValueError('Worker source mismatch: '+relative)
for repository,head in manifest['repositories'].items():
 actual=subprocess.check_output(['git','-C',str(root/repository),'rev-parse','HEAD'],text=True).strip()
 if actual!=head:raise ValueError('Worker repository mismatch: '+repository)
''', helper.REMOTE, remote)


def collect_local(helper, slot, destination, successful, family):
    output_roots = {'reports', 'test-results', 'hangar-flows', 'hangar-shards', 'dependeasy'}
    if successful and family not in {'metadata', 'verification'}:
        output_roots.update({'outputs', 'libs', 'classes', 'resources', 'processedResources', 'compose', 'distributions', 'bin', 'xcode-frameworks', 'XCFrameworks'})
    for name in helper.PROJECTS:
        base = slot / name
        for directory, dirs, files in os.walk(base):
            dirs[:] = [entry for entry in dirs if entry not in helper.EXCLUDED - {'build', 'reports'}]
            path = Path(directory)
            parts = path.relative_to(base).parts
            evidence = any(part == 'build' and index + 1 < len(parts) and parts[index + 1] in output_roots for index, part in enumerate(parts))
            if evidence:
                for dirname in list(dirs):
                    source = path / dirname
                    if source.is_symlink():
                        target = destination / name / source.relative_to(base)
                        target.parent.mkdir(parents=True, exist_ok=True)
                        copy_source(source, target)
                        dirs.remove(dirname)
                for filename in files:
                    source = path / filename
                    target = destination / name / source.relative_to(base)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    copy_source(source, target)


def isolated_build(helper, identity, host, project, args, run_id=None):
    remote_retention.require_admission(helper.STATE)
    if host not in {'local', 'worker'}: raise ValueError('Unknown host')
    if not all(character in '0123456789abcdef' for character in identity) or len(identity) != 64:
        raise ValueError('Invalid snapshot identity')
    run_id = run_id or datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
    if not re.fullmatch(r'[A-Za-z0-9-]+', run_id): raise ValueError('Invalid run identity')
    evidence = helper.STATE / 'runs' / run_id
    evidence.mkdir(parents=True)
    evidence.chmod(0o700)
    receipt = {'schemaVersion': 1, 'run_id': run_id, 'snapshot': identity, 'host': host,
               'project': project, 'arguments': args, 'argumentsDigest': digest(args), 'status': 'queued'}
    started = time.monotonic()
    def save():
        (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    save()
    try:
        with lease(helper.STATE / ('local-gradle.lock' if host == 'local' else 'worker-gradle.lock')):
            remote_retention.require_admission(helper.STATE)
            receipt['queueSeconds'] = time.monotonic() - started
            receipt['diskAdmission'] = disk_admission(helper, host)
            receipt['status'] = 'materializing'
            save()
            original_root, original_remote = helper.ROOT, helper.REMOTE
            try:
                started = time.monotonic()
                slot = materialize(helper, identity, host)
                receipt['materializeSeconds'] = time.monotonic() - started
                java_command = [str(Path(helper.CONFIG['java_home']) / 'bin/java'), '-version']
                java = (helper.ssh(shlex.join(java_command), capture_output=True, text=True) if host == 'worker'
                        else subprocess.run(java_command, capture_output=True, text=True, check=True))
                receipt['java'] = (java.stderr + java.stdout).strip()
                receipt['gradleWrapper'] = file_identity(slot / project / 'gradle/wrapper/gradle-wrapper.properties')
                receipt['adapterDigest'] = digest({filename: file_identity(Path(__file__).parent / filename)
                                                  for filename in ('remote-dev', 'remote_build.py', 'hosts_contract.py', 'physical-devices.gradle', 'build-cache.gradle')})
                receipt['cachePolicy'] = os.environ.get('REMOTE_DEV_CACHE', 'off')
                helper.ROOT = slot
                if host == 'worker':
                    helper.REMOTE = original_remote + '/.remote-dev/slots/worker'
                    helper.remote_python('''import sys
from pathlib import Path
source=Path(sys.argv[1]); target=Path(sys.argv[2]); target.mkdir(parents=True,exist_ok=True)
script=(source/'run-gradle').read_text().replace('job="$worker_state/jobs/', 'job="${REMOTE_DEV_JOB_ROOT:-$worker_state}/jobs/')
(target/'run-gradle').write_text(script)
(target/'physical-devices.gradle').write_bytes((source/'physical-devices.gradle').read_bytes())
''', original_remote + '/.remote-dev', helper.REMOTE + '/.remote-dev')
                receipt['status'] = 'running'
                receipt['executionStarted'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
                save()
                started = time.monotonic()
                log = evidence / 'gradle.log'
                try:
                    if host == 'local':
                        code = helper.local_gradle(project, Path('.'), args, log)
                        artifact_root = evidence / 'artifacts'
                        hosts = helper.read_hosts_contract(slot / project)
                        family = helper.artifact_family(args, hosts.get('taskFamilies', {}), hosts.get('taskOptions', {}))
                        receipt['artifactFamily'] = family
                        collect_local(helper, slot, artifact_root, code == 0, family)
                    else:
                        # Existing worker supervision and scoped transfer publish into this idle staging slot.
                        with log.open('w') as output, contextlib.redirect_stdout(output):
                            code = helper.build(project, Path('.'), args, frozen=True,
                                                verify=lambda: verify_worker(helper, identity))
                        verify_worker(helper, identity)
                        receipt['workerInputsUnchanged'] = True
                        artifact_root = helper.STATE / 'artifacts' / helper.LAST_RUN_ID
                        receipt['transportReceipt'] = str(helper.STATE / 'logs' / (helper.LAST_RUN_ID + '.json'))
                    receipt['executionAndCollectionSeconds'] = time.monotonic() - started
                    if host == 'worker':
                        transport = json.loads(Path(receipt['transportReceipt']).read_text())
                        receipt['phases'] = {key: transport[key] for key in ('sync_seconds', 'execution_seconds', 'collection_seconds') if key in transport}
                    receipt['exit_code'] = code
                    receipt['status'] = 'succeeded' if code == 0 else 'failed'
                    receipt['artifactDirectory'] = str(artifact_root)
                    receipt['artifacts'] = checksums(artifact_root)
                    content = log.read_text(errors='replace')
                    receipt['taskOutcomes'] = {key: len(re.findall(r'^> Task .* ' + key + r'$', content, re.MULTILINE))
                                               for key in ('FROM-CACHE', 'UP-TO-DATE', 'FAILED', 'SKIPPED', 'NO-SOURCE')}
                    receipt['taskOutcomesScope'] = 'Console matches; nested builds may report a task more than once'
                    changes = re.findall(r"Class path of task '([^']+)' has changed from (\w+) to (\w+)", content)
                    receipt['implementationInvalidations'] = len({task for task, _, _ in changes}) if '--info' in args or '--debug' in args else None
                    if changes:
                        (evidence / 'implementation-changes.json').write_text(json.dumps(changes, indent=2) + '\n')
                    for filename in ('model.json', 'remote/hosts.json'):
                        report = slot / project / 'build/dependeasy' / filename
                        if report.exists(): receipt[filename] = file_identity(report)
                    try:
                        verify_sources(slot, json.loads((helper.STATE / 'snapshots' / identity / 'manifest.json').read_text()))
                        receipt['inputsUnchanged'] = True
                    except ValueError as error:
                        receipt['inputsUnchanged'] = False
                        receipt['inputError'] = str(error)
                        receipt['status'] = 'failed'
                        receipt['exit_code'] = code or 74
                finally:
                    helper.ROOT, helper.REMOTE = original_root, original_remote
            except Exception as error:
                receipt.update(status='incomplete', error=str(error), exit_code=74)
            finally:
                helper.ROOT, helper.REMOTE = original_root, original_remote
                receipt['finished'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
                save()
    except BaseException as error:
        receipt.update(status='cancelled' if isinstance(error, KeyboardInterrupt) else 'incomplete',
                       error=str(error) or type(error).__name__, exit_code=130 if isinstance(error, KeyboardInterrupt) else 74)
        if host == 'worker' and helper.LAST_RUN_ID:
            receipt['transportReceipt'] = str(helper.STATE / 'logs' / (helper.LAST_RUN_ID + '.json'))
            receipt['workerMayContinue'] = True
        save()
        raise
    print(f'{host} {project}: {receipt["status"]}; {evidence / "receipt.json"}', flush=True)
    return receipt
