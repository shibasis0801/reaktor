"""Controls only executions supervised by this router, through private request files."""
import datetime
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time
import uuid


TERMINAL = {'Succeeded', 'Failed', 'Cancelled'}


def write_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(value, output)
            output.write('\n')
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def read_json(path):
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | os.O_NONBLOCK)
    with os.fdopen(descriptor) as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_size > 1_048_576:
            raise ValueError('Control file is not an owned, bounded file')
        value = json.load(source)
    if not isinstance(value, dict):
        raise ValueError('Control file is not an object')
    return value


def owned_directory(path):
    path = Path(path)
    if not path.is_absolute() or path.resolve() != path or not path.is_dir() or path.stat().st_uid != os.getuid():
        raise ValueError('Control directory is not an owned canonical directory')
    return path


def selected_state(arguments, default):
    if '--state-root' not in arguments:
        return Path(default)
    control = arguments[:1] == ['job-control']
    inventory = arguments[:2] == ['mobile', 'inventory']
    if not (control or inventory) or arguments.count('--state-root') != 1:
        raise ValueError('--state-root is accepted only by job-control or mobile inventory')
    index = arguments.index('--state-root')
    if index + 1 >= len(arguments):
        raise ValueError('--state-root requires an absolute directory')
    state = owned_directory(Path(arguments[index + 1]))
    read_json(state / 'config.json')
    return state


def process_identity(pid):
    if type(pid) is not int or pid <= 1:
        return None
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'lstart=', '-o', 'pgid=', '-o', 'stat='],
                            capture_output=True, text=True, env=dict(os.environ, LC_ALL='C'), timeout=3)
    fields = result.stdout.split()
    if result.returncode or len(fields) != 7 or fields[-1].startswith('Z'):
        return None
    return {'pid': pid, 'started': ' '.join(fields[:5]), 'pgid': int(fields[5])}


def live(identity):
    return isinstance(identity, dict) and process_identity(identity.get('pid')) == identity


def group_running(pgid):
    result = subprocess.run(['/bin/ps', '-ax', '-o', 'pgid=', '-o', 'stat='],
                            capture_output=True, text=True, check=True, timeout=3)
    return any(len(fields) == 2 and fields[0] == str(pgid) and not fields[1].startswith('Z')
               for fields in map(str.split, result.stdout.splitlines()))


def prepare(job, run_id, execution_id, argv, cwd):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,199}', run_id):
        raise ValueError('Invalid run identity')
    job = Path(job)
    job.mkdir(mode=0o700, parents=True, exist_ok=False)
    owned_directory(job)
    write_json(job / 'request.json', {'runId': run_id, 'executionId': execution_id, 'argv': argv, 'cwd': str(cwd)})
    return {'schemaVersion': 1, 'executionId': execution_id, 'jobDirectory': str(job)}


def state(job, execution_id):
    job = owned_directory(job)
    request = read_json(job / 'request.json')
    if request.get('executionId') != execution_id or request.get('runId') != job.name:
        raise ValueError('Execution identity does not match this job')
    value = read_json(job / 'control.json')
    if value.get('executionId') != execution_id or value.get('runId') != job.name:
        raise ValueError('Supervisor identity does not match this job')
    return value


def stop_reason(value):
    if value.get('status') in TERMINAL:
        return 'Execution already finished'
    if value.get('status') == 'Stopping' and live(value.get('supervisor')):
        return 'Cancellation already requested; waiting for the supervised execution to stop'
    if value.get('status') not in {'Running', 'Stopping'}:
        return 'Execution has no active supervised process'
    if not live(value.get('supervisor')) or not live(value.get('process')):
        return 'Recorded supervisor or execution process is no longer live under its recorded birth identity'
    process = value['process']
    if process['pgid'] != process['pid']:
        return 'Execution does not own an isolated process group'
    return None


def stop(job, execution_id):
    value = state(job, execution_id)
    reason = stop_reason(value)
    if reason:
        raise ValueError(reason)
    write_json(Path(job) / 'stop.json', {'executionId': execution_id})
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        value = state(job, execution_id)
        if value.get('status') in TERMINAL:
            return value
        if not live(value.get('supervisor')):
            raise ValueError('Supervisor exited before confirming cancellation')
        time.sleep(0.1)
    raise ValueError('Cancellation was requested; supervisor has not confirmed completion within 20 seconds')


def supervise(job):
    job = owned_directory(Path(job))
    request = read_json(job / 'request.json')
    if request.get('runId') != job.name:
        raise ValueError('Request run identity does not match this job')
    value = {'schemaVersion': 1, 'runId': job.name, 'executionId': request['executionId'],
             'status': 'Starting', 'supervisor': process_identity(os.getpid())}
    write_json(job / 'control.json', value)
    interrupted = False

    def interrupt(signum, frame):
        nonlocal interrupted
        interrupted = True

    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    process = subprocess.Popen(request['argv'], cwd=request['cwd'], start_new_session=True)
    value['process'] = process_identity(process.pid)
    value['status'] = 'Running'
    write_json(job / 'control.json', value)
    cancelled = False
    while process.poll() is None:
        requested = interrupted
        if (job / 'stop.json').exists():
            requested = requested or read_json(job / 'stop.json').get('executionId') == request['executionId']
        if requested:
            cancelled = True
            value['status'] = 'Stopping'
            write_json(job / 'control.json', value)
            if not live(value.get('process')):
                # A naturally finished child is settled without signaling any unproved process.
                cancelled = False
                break
            # Keep the child unreaped until its group is empty: its PID cannot be reused while
            # escalation is in progress, even if the group leader exits before its children.
            for sig, seconds in [(signal.SIGINT, 3), (signal.SIGTERM, 3), (signal.SIGKILL, 2)]:
                if not group_running(process.pid):
                    break
                os.killpg(process.pid, sig)
                deadline = time.monotonic() + seconds
                while group_running(process.pid) and time.monotonic() < deadline:
                    time.sleep(0.05)
            if group_running(process.pid):
                raise ValueError('Owned execution group did not stop; cancellation is unconfirmed')
            break
        time.sleep(0.05)
    code = process.wait()
    value.update(status='Cancelled' if cancelled else 'Succeeded' if code == 0 else 'Failed',
                 exitCode=130 if cancelled else code,
                 finished=datetime.datetime.now(datetime.timezone.utc).isoformat())
    write_json(job / 'control.json', value)
    (job / 'exitcode.tmp').write_text(str(value['exitCode']) + '\n')
    os.replace(job / 'exitcode.tmp', job / 'exitcode')
    return value['exitCode']


def snapshot_host(helper, value):
    workspace = value.get('workspace_root')
    expected = 'local' if value.get('worker') == 'local' else 'worker'
    index = value.get('local_slot')
    folder = 'local-1' if expected == 'local' and type(index) is int and index == 1 else expected
    roots = helper.remote_retention.configured_slot_roots(helper.STATE, helper.CONFIG)
    return expected if any(workspace == str(root / folder) for root in roots) else None


def receipt(helper, run_id):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,199}', run_id):
        raise ValueError('Invalid retained run identity')
    path = owned_directory(Path(helper.STATE) / 'logs') / (run_id + '.json')
    value = read_json(path)
    if value.get('run_id') != run_id:
        raise ValueError('Receipt identity does not match its retained file')
    workspace = value.get('workspace_root')
    project = value.get('project')
    if not isinstance(workspace, str) or Path(workspace).resolve() != Path(workspace):
        raise ValueError('Receipt has no canonical source workspace')
    if project not in helper.CONFIG.get('projects', []):
        raise ValueError('Receipt project is not configured')
    if Path(workspace) != Path(helper.ROOT) and snapshot_host(helper, value) is None:
        try:
            helper.project_at(Path(workspace) / project)
        except SystemExit as error:
            raise ValueError(str(error)) from error
        if Path(workspace) != Path(helper.ROOT):
            raise ValueError('Receipt workspace is not the configured source workspace')
    return path, value


def control(helper, value):
    recorded = value.get('execution_control')
    if not isinstance(recorded, dict) or recorded.get('schemaVersion') != 1:
        raise ValueError('This older receipt has no verified supervisor identity; Stop is unavailable')
    host = value.get('worker')
    if recorded.get('host') != host or host not in {'local', helper.HOST}:
        raise ValueError('Receipt execution host does not match configured local or worker host')
    if host == 'local':
        expected = Path(helper.STATE) / 'jobs' / value['run_id']
    else:
        index = value.get('worker_slot') or 0
        if type(index) is not int or not 0 <= index < helper.MAX_WORKER_SLOTS:
            raise ValueError('Receipt worker slot is not configured')
        root = (helper.BASE_REMOTE + '/.remote-dev/slots/worker' if snapshot_host(helper, value) == 'worker'
                else helper.slot_remote(index))
        if not helper.remote_retention.receipt_root_matches(value, root):
            raise ValueError('Receipt names a worker folder outside its configured slot')
        expected = Path(root) / '.remote-dev/jobs' / value['run_id']
    if recorded.get('jobDirectory') != str(expected) or not isinstance(recorded.get('executionId'), str):
        raise ValueError('Receipt control directory or execution identity is invalid')
    return recorded


def remote(helper, operation, recorded):
    source = Path(__file__).read_text()
    code = source + '\nprint(json.dumps(' + operation + '(sys.argv[1], sys.argv[2])))\n'
    result = helper.remote_python(code, recorded['jobDirectory'], recorded['executionId'],
                                  capture_output=True, text=True, timeout=23 if operation == 'stop' else 5)
    if len(result.stdout) > 65_536:
        raise ValueError('Worker control response exceeds its bound')
    return json.loads(result.stdout)


def inspect(helper, run_id):
    _, saved = receipt(helper, run_id)
    result = {'schemaVersion': 1, 'runId': run_id, 'host': saved.get('worker'), 'status': 'Unknown',
              'executionId': None, 'actions': {
                  'stop': {'supported': False, 'reason': 'Execution identity has not been verified'},
                  'resume': {'supported': False, 'reason': 'Collection recovery remains available through the existing Terminal resume command'}}}
    try:
        recorded = control(helper, saved)
        result['executionId'] = recorded['executionId']
        value = (inspection(recorded['jobDirectory'], recorded['executionId']) if saved['worker'] == 'local'
                 else remote(helper, 'inspection', recorded))
        result['status'] = value.get('status', 'Unknown')
        reason = value['stopReason']
        if saved.get('finished') or saved.get('exit_code') is not None:
            reason = 'Retained router receipt is already complete'
        result['actions']['stop'] = {'supported': reason is None, 'reason': reason}
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        result['actions']['stop']['reason'] = str(error)
    return result


def inspection(job, execution_id):
    value = state(job, execution_id)
    reason = stop_reason(value)
    if value.get('status') == 'Running' and reason or value.get('status') == 'Stopping' and not live(value.get('supervisor')):
        value = dict(value, status='Unknown')
    return dict(value, stopReason=reason)


def failure(run_id, execution_id, error):
    return {'schemaVersion': 1, 'runId': run_id, 'host': None, 'status': 'Unknown', 'executionId': execution_id,
            'actions': {'stop': {'supported': False, 'reason': str(error)},
                        'resume': {'supported': False, 'reason': str(error)}}, 'message': str(error)}


def execute(helper, operation, run_id, execution_id=None):
    result = inspect(helper, run_id)
    if operation == 'inspect':
        return result
    action = result['actions'][operation]
    if execution_id != result['executionId'] or not execution_id:
        raise ValueError('Displayed execution identity is stale or missing; inspect this run again')
    if not action['supported']:
        raise ValueError(action['reason'])
    path, saved = receipt(helper, run_id)
    recorded = control(helper, saved)
    if recorded['executionId'] != execution_id:
        raise ValueError('Execution identity changed since inspection')
    if saved.get('finished') or saved.get('exit_code') is not None:
        raise ValueError('Retained router receipt completed since inspection')
    value = (stop(recorded['jobDirectory'], execution_id) if saved['worker'] == 'local'
             else remote(helper, 'stop', recorded))
    if value.get('status') == 'Cancelled':
        _, latest = receipt(helper, run_id)
        latest.update(cancelled=True, exit_code=130, finished=value['finished'], phase='Finished', phase_host=saved['worker'])
        write_json(path, latest)
    result['status'] = value.get('status', 'Unknown')
    result['actions']['stop'] = {'supported': False, 'reason': 'Execution already finished'}
    result['message'] = 'Execution cancellation confirmed' if value.get('status') == 'Cancelled' else 'Execution finished before cancellation'
    return result


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '_supervise':
        raise SystemExit(supervise(sys.argv[2]))
