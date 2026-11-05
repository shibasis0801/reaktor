#!/usr/bin/env python3
"""Run Atlas source reads in worker slot 2, separate from the attached phones.

The local owner retains mutation authority and runtime/device operations. Sources
are synchronized under the slot lease before each request; outputs stay remote.
"""
import contextlib
import fcntl
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time
import urllib.request

SLOT = 2
OPERATIONS = {'atlas.query', 'atlas.read'}
IMAGE = Path.home() / '.reaktor/dist/conductor-analysis'
JAVA = str(Path.home() / 'Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home/bin/java')
BINARY_SUFFIXES = {'.png', '.jpg', '.jpeg', '.gif', '.webp', '.heic', '.pdf', '.mp4', '.mov',
    '.jar', '.zip', '.apk', '.aab', '.ttf', '.woff', '.woff2', '.otf', '.bin', '.dylib', '.so',
    '.a', '.o', '.class', '.db', '.sqlite', '.ico', '.icns', '.wav', '.mp3', '.m4a'}


def bounded_run(argv, deadline, **kwargs):
    """End the entire transfer group before the owner's 90-second deadline."""
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise subprocess.TimeoutExpired(argv, 60)
    timeout = min(kwargs.pop('timeout', remaining), remaining)
    supplied_input = 'input' in kwargs
    data = kwargs.pop('input', None)
    if supplied_input:
        kwargs['stdin'] = subprocess.PIPE
    if kwargs.pop('capture_output', False):
        kwargs['stdout'] = kwargs['stderr'] = subprocess.PIPE
    process = subprocess.Popen([str(a) for a in argv], start_new_session=True, **kwargs)
    try:
        stdout, stderr = process.communicate(data, timeout=timeout)
    except BaseException as error:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        stdout, stderr = process.communicate()
        if isinstance(error, subprocess.TimeoutExpired):
            error.output, error.stderr = stdout, stderr
        raise
    if process.returncode:
        raise subprocess.CalledProcessError(process.returncode, argv, stdout, stderr)
    return subprocess.CompletedProcess(argv, process.returncode, stdout, stderr)


def analysis_files(base, files):
    for relative in files:
        path = base / relative
        if '.reaktor/libexec' in relative or path.suffix.lower() in BINARY_SUFFIXES:
            continue
        if path.is_symlink():
            yield relative
            continue
        with path.open('rb') as source:
            if b'\0' not in source.read(8192):
                yield relative


def source_request(payload):
    if payload['operation'] not in OPERATIONS:
        raise ValueError('Only Atlas source reads may be offloaded')
    def observations(arguments):
        return arguments.get('preset') in {'runtime', 'routes', 'affected-checks'} or any(
            observations(query) for query in arguments.get('queries', []))
    if payload['operation'] == 'atlas.query' and observations(payload['arguments']):
        raise ValueError('Runtime observations and check outcomes belong to the local owner')


def router():
    loader = importlib.machinery.SourceFileLoader('atlas_remote_router', str(Path(__file__).with_name('remote-dev')))
    module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
    sys.modules[loader.name] = module
    loader.exec_module(module)
    return module


def translate(value, source, target, field=''):
    if isinstance(value, dict):
        return {k: translate(v, source, target, k) for k, v in value.items()}
    if isinstance(value, list):
        return [translate(v, source, target, field) for v in value]
    if isinstance(value, str) and field not in {'text', 'snippet', 'signature'}:
        if value == source or value.startswith(source + '/') or value.startswith(source + ':'):
            return target + value[len(source):]
    return value


def source_view(helper, name):
    import remote_build
    base = helper.ROOT / name
    digest = hashlib.sha256()
    files = helper.source_files(name)
    text = set(analysis_files(base, files))
    identities = {}
    for relative in files:
        path = base / relative
        if relative in text:
            identity = remote_build.file_identity(path)
        else:
            stat = path.lstat()
            identity = ['binary', stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns, stat.st_ino, stat.st_mode]
        identities[relative] = identity
        digest.update(json.dumps([relative, identity]).encode())
    if (base / '.git').exists():
        for relative in helper.git_repositories(base):
            head = subprocess.check_output(['git', '-C', str(base / relative), 'rev-parse', 'HEAD'])
            digest.update(str(relative).encode() + b'\0' + head)
    return digest.hexdigest(), identities


def fingerprint(helper, name):
    return source_view(helper, name)[0]


def source_stamps(helper, names):
    result = helper.remote_python('''import json,sys
from pathlib import Path
state=Path(sys.argv[1])/'.remote-dev'
print(json.dumps({name:(state/(name+'.source.json')).stat().st_mtime_ns
 if (state/(name+'.source.json')).is_file() else None for name in sys.argv[2:]}))
''', helper.REMOTE, *names, capture_output=True, text=True)
    return json.loads(result.stdout)


def sync_sources(helper, names):
    """Reuse an identical source view, invalidated by content or any other sync."""
    path = helper.STATE / 'atlas-worker-sync.json'
    try:
        cache = json.loads(path.read_text())
    except (OSError, ValueError):
        cache = {}
    if cache.get('destination') != helper.REMOTE:
        cache = {'destination': helper.REMOTE, 'projects': {}}
    projects = cache.setdefault('projects', {})
    stamps = source_stamps(helper, names)
    for name in names:
        current, files = source_view(helper, name)
        previous = projects.get(name)
        if not isinstance(previous, dict) or previous.get('stamp') != stamps[name]:
            previous = {}
        if previous.get('fingerprint') == current:
            continue
        old = previous.get('files', {})
        selected = {p for p in files if files[p] != old.get(p)} | set(previous.get('uncertain', []))
        # An existing complete view seeds binary media by metadata, as the build
        # router does. Text is always checked; later binary edits are checked too.
        checked = None if previous else {name: sorted(p for p in selected if files[p][0] != 'binary')}
        helper.sync([name], transfer_files={name: sorted(selected)}, checksum_files=checked)
        stamp = source_stamps(helper, [name])[name]
        after, after_files = source_view(helper, name)
        uncertain = sorted(p for p in files.keys() | after_files.keys() if files.get(p) != after_files.get(p))
        projects[name] = dict(fingerprint=current if current == after else None,
            stamp=stamp, files=files, uncertain=uncertain)
        # Persist each completed repository, even if a later transfer times out.
        pending = path.with_suffix('.pending')
        pending.write_text(json.dumps(cache))
        pending.replace(path)


def operation(root, endpoint, payload):
    request = urllib.request.Request(f'http://127.0.0.1:{endpoint["port"]}/operations',
        data=json.dumps(dict(version=1, workspaceRoot=root, operation='kernel',
            arguments=dict(operation=payload['operation'], arguments=payload['arguments']))).encode(),
        headers={'Authorization': 'Bearer ' + endpoint['token'], 'Content-Type': 'application/json'})
    result = json.load(urllib.request.urlopen(request, timeout=45))
    if result.get('isError'): raise RuntimeError('Worker owner rejected the request')
    outcome = result['data']
    if outcome.get('type') != 'completed': raise RuntimeError(outcome.get('message', 'Worker query failed'))
    return outcome['result']


def serve(payload):
    root = str(Path(payload['workspaceRoot']).resolve())
    value = os.environ.get('REMOTE_DEV_WORKER_ROOT')
    if not value:
        raise ValueError('Configured worker root is required')
    base = Path(value)
    if not base.is_absolute() or '..' in base.parts or base.is_symlink():
        raise ValueError('Invalid configured worker root')
    allowed = str(base / '.remote-dev/slots/job-2')
    if root not in {allowed + '/' + p for p in ['bestbuds', 'reaktor', 'manna']}:
        raise ValueError('Atlas worker root is outside the analysis slot')
    source_request(payload)
    state = Path.home() / '.reaktor/agents' / hashlib.sha256(root.encode()).hexdigest()
    endpoint = state / 'connection.json'
    # --install reuses a healthy owner and its immutable runtime image.
    subprocess.run([JAVA, '-Xmx2g', '-cp', str(IMAGE / 'lib/*'),
        'ai.bestbuds.reaktor.kernel.KernelAgentServiceKt', '--install', root],
        check=True, stdout=sys.stderr, timeout=30)
    return operation(root, json.loads(endpoint.read_text()), payload)


def local(payload):
    helper = router()
    deadline = time.monotonic() + 60
    helper.run = lambda argv, **kwargs: bounded_run(argv, deadline, **kwargs)
    root = str(Path(payload['workspaceRoot']).resolve())
    if root not in {str(helper.ROOT / p) for p in ['bestbuds', 'reaktor', 'manna']}:
        raise ValueError('Unsupported local Atlas workspace')
    source_request(payload)
    # The same lease as the build router excludes a third portable job while
    # analysis is syncing/reading; mobile slot 0 and build slot 1 remain independent.
    lock_deadline = min(deadline, time.monotonic() + 2)
    with (helper.STATE / 'worker-slot-2.lock').open('a') as lock:
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= lock_deadline:
                    raise TimeoutError('Worker analysis is busy; retry this read')
                time.sleep(0.1)
        import remote_retention
        remote_retention.require_admission(helper.STATE)
        with helper.worker_slot(SLOT), contextlib.redirect_stdout(sys.stderr):
            sync_sources(helper, [p for p in ['reaktor', 'manna', 'bestbuds'] if p in helper.PROJECTS])
            destination = helper.REMOTE
            remote = translate(payload, str(helper.ROOT), destination)
            command = shlex.join(['env', 'REMOTE_DEV_WORKER_ROOT=' + helper.BASE_REMOTE, '/opt/homebrew/bin/python3', destination + '/tools/atlas-worker.py', '--serve'])
            try:
                result = helper.ssh(command, input=json.dumps(remote), capture_output=True, text=True, timeout=75)
            except subprocess.CalledProcessError as error:
                sys.stderr.write(error.stderr or '')
                raise RuntimeError('worker read failed') from error
            sys.stderr.write(result.stderr)
            return translate(json.loads(result.stdout), destination, str(helper.ROOT))


def install():
    helper = router()
    destination = helper.slot_remote(SLOT)
    helper.ssh(shlex.join(['mkdir', '-p', str(IMAGE), destination + '/tools']))
    helper.run(['rsync', '-a', '--delete', str(Path.home() / '.reaktor/dist/conductor') + '/', f'{helper.HOST}:{IMAGE}/'])
    helper.run(['rsync', '-a', str(Path(__file__).resolve()), f'{helper.HOST}:{destination}/tools/atlas-worker.py'])


if __name__ == '__main__':
    try:
        if sys.argv[1:] == ['--install']:
            install()
        else:
            payload = json.load(sys.stdin)
            json.dump(serve(payload) if sys.argv[1:] == ['--serve'] else local(payload), sys.stdout)
    except Exception as error:
        print(f'Atlas worker: {error}', file=sys.stderr)
        sys.exit(1)
