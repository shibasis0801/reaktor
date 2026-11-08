import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import time
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import patch

import build_control
from test_remote_router import router


class BuildControlTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.helper = router()
        self.helper.ROOT = self.root
        self.helper.STATE = self.root / '.remote-dev'
        self.helper.STATE.mkdir()
        (self.helper.STATE / 'logs').mkdir()
        self.helper.CONFIG.update(projects=['fixture'], remote_root=str(self.root / 'worker'))
        self.helper.PROJECTS = ['fixture']
        self.helper.REMOTE = self.helper.BASE_REMOTE = str(self.root / 'worker')
        (self.root / 'fixture').mkdir()
        self.processes = []
        self.addCleanup(self.close_processes)

    def close_processes(self):
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

    def launch(self, host='local', command=None, snapshot=False):
        run = 'fixture-' + uuid.uuid4().hex
        execution = uuid.uuid4().hex
        remote_root = self.root / ('worker/.remote-dev/slots/worker' if snapshot else 'worker')
        jobs = self.helper.STATE / 'jobs' if host == 'local' else remote_root / '.remote-dev/jobs'
        job = jobs / run
        control = build_control.prepare(job, run, execution,
                                        command or [sys.executable, '-c', 'import time; time.sleep(120)'], self.root)
        control['host'] = host
        process = subprocess.Popen([sys.executable, str(Path(build_control.__file__).resolve()), '_supervise', str(job)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        self.processes.append(process)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                value = build_control.state(job, execution)
                if value['status'] != 'Starting':
                    break
            except FileNotFoundError:
                pass
            time.sleep(0.02)
        else:
            self.fail('Owned fixture supervisor did not start')
        workspace = self.helper.STATE / 'slots' / ('local' if host == 'local' else 'worker') if snapshot else self.root
        workspace.mkdir(parents=True, exist_ok=True)
        saved = {'run_id': run, 'workspace_root': str(workspace), 'project': 'fixture',
                 'project_directory': str(self.root / 'fixture'), 'worker': host, 'tasks': [':check'],
                 'exit_code': None, 'execution_control': control, 'owner': build_control.process_identity(os.getpid())}
        if host != 'local':
            saved['remote_root'] = str(remote_root)
        path = self.helper.STATE / 'logs' / (run + '.json')
        path.parent.mkdir(exist_ok=True)
        build_control.write_json(path, saved)
        return run, execution, job, process, path

    def test_owned_local_stop_waits_and_records_cancelled_receipt(self):
        run, execution, job, process, path = self.launch()
        before = build_control.inspect(self.helper, run)
        self.assertTrue(before['actions']['stop']['supported'])
        self.assertEqual('Running', before['status'])
        result = build_control.execute(self.helper, 'stop', run, execution)
        self.assertEqual('Cancelled', result['status'])
        self.assertFalse(result['actions']['stop']['supported'])
        self.assertEqual(130, process.wait(timeout=3))
        saved = json.loads(path.read_text())
        self.assertTrue(saved['cancelled'])
        self.assertEqual(130, saved['exit_code'])
        self.assertEqual('Finished', saved['phase'])
        self.assertTrue(saved['finished'])

    def test_stale_displayed_execution_never_creates_stop_request(self):
        run, execution, job, _, _ = self.launch()
        with self.assertRaisesRegex(ValueError, 'stale'):
            build_control.execute(self.helper, 'stop', run, 'wrong-execution')
        self.assertFalse((job / 'stop.json').exists())

    def test_mismatched_pid_birth_and_pgid_cannot_stop(self):
        run, execution, job, _, _ = self.launch()
        state = build_control.state(job, execution)
        for field, wrong in [('started', 'reused process'), ('pgid', os.getpgrp())]:
            altered = dict(state, process=dict(state['process'], **{field: wrong}))
            build_control.write_json(job / 'control.json', altered)
            self.assertFalse(build_control.inspect(self.helper, run)['actions']['stop']['supported'])
            with self.assertRaises(ValueError):
                build_control.execute(self.helper, 'stop', run, execution)
            self.assertFalse((job / 'stop.json').exists())
        build_control.write_json(job / 'control.json', state)

    def test_old_receipt_is_explicitly_unsupported_and_does_not_signal(self):
        run, _, job, _, path = self.launch()
        saved = json.loads(path.read_text())
        del saved['execution_control']
        build_control.write_json(path, saved)
        result = build_control.inspect(self.helper, run)
        self.assertFalse(result['actions']['stop']['supported'])
        self.assertIn('older receipt', result['actions']['stop']['reason'])
        self.assertFalse((job / 'stop.json').exists())

    def test_completed_receipt_conflict_refuses_stop(self):
        run, execution, job, _, path = self.launch()
        saved = json.loads(path.read_text())
        saved.update(exit_code=0, finished='recorded completion')
        build_control.write_json(path, saved)
        with self.assertRaisesRegex(ValueError, 'complete'):
            build_control.execute(self.helper, 'stop', run, execution)
        self.assertFalse((job / 'stop.json').exists())

    def test_changed_execution_between_inspection_and_write_cannot_stop_new_identity(self):
        run, execution, job, _, path = self.launch()
        original = build_control.inspect

        def changed(helper, run_id):
            result = original(helper, run_id)
            saved = json.loads(path.read_text())
            saved['execution_control']['executionId'] = uuid.uuid4().hex
            build_control.write_json(path, saved)
            return result

        with patch.object(build_control, 'inspect', side_effect=changed), self.assertRaisesRegex(ValueError, 'changed'):
            build_control.execute(self.helper, 'stop', run, execution)
        self.assertFalse((job / 'stop.json').exists())

    def test_worker_control_runs_identity_checks_on_worker_transport(self):
        run, execution, job, process, path = self.launch('fixture')
        calls = []

        def transport(source, *args, **options):
            calls.append((source, args, options))
            return subprocess.run([sys.executable, '-c', source, *args], check=True, **options)

        with patch.object(self.helper, 'remote_python', side_effect=transport):
            self.assertTrue(build_control.inspect(self.helper, run)['actions']['stop']['supported'])
            result = build_control.execute(self.helper, 'stop', run, execution)
        self.assertEqual('Cancelled', result['status'])
        self.assertEqual(130, process.wait(timeout=3))
        self.assertTrue(json.loads(path.read_text())['cancelled'])
        self.assertTrue(calls)
        self.assertTrue(all(call[1] == (str(job), execution) for call in calls))
        self.assertTrue(all(call[2]['timeout'] <= 23 for call in calls))

    def test_real_worker_launcher_payload_starts_its_private_supervisor(self):
        worker = self.root / 'worker'
        (worker / 'fixture').mkdir(parents=True)
        (worker / '.remote-dev').mkdir()
        (self.root / 'fixture/gradlew').touch()
        wrapper = worker / 'fixture/gradlew'
        wrapper.write_text('#!/bin/sh\nsleep 120\n')
        wrapper.chmod(0o755)
        (worker / '.remote-dev/run-gradle').write_text('#!/bin/sh\nexec "$@"\n')
        captured = []

        def transport(source, *args, **options):
            source = source.replace('/opt/homebrew/bin/python3', sys.executable)
            return subprocess.run([sys.executable, '-c', source, *args], check=True, **options)

        def retained(saved, worker_lock):
            captured.append(saved)
            return 0

        with patch.object(self.helper, 'remote_python', side_effect=transport), \
             patch.object(self.helper, 'finish_build', side_effect=retained), \
             patch.object(self.helper, 'cache_environment', return_value={}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.helper.build('fixture', Path('.'), [':check'], synchronize=False))
        saved = captured[0]
        control = saved['execution_control']
        job = Path(control['jobDirectory'])
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                if build_control.state(job, control['executionId'])['status'] == 'Running':
                    break
            except FileNotFoundError:
                pass
            time.sleep(0.02)
        else:
            self.fail('Worker launcher did not start supervised execution')
        try:
            self.assertEqual(0o600, (job / 'supervisor.py').stat().st_mode & 0o777)
            with patch.object(self.helper, 'remote_python', side_effect=transport):
                result = build_control.execute(self.helper, 'stop', saved['run_id'], control['executionId'])
            self.assertEqual('Cancelled', result['status'])
            self.assertEqual('130', (job / 'exitcode').read_text().strip())
        finally:
            if build_control.state(job, control['executionId'])['status'] not in build_control.TERMINAL:
                build_control.stop(job, control['executionId'])

    def test_worker_host_slot_and_path_drift_refuse_transport(self):
        run, execution, _, _, path = self.launch('fixture')
        saved = json.loads(path.read_text())
        cases = [dict(saved, worker='unconfigured-host'), dict(saved, worker_slot=99),
                 dict(saved, remote_root='/outside'),
                 dict(saved, execution_control=dict(saved['execution_control'], jobDirectory='/outside'))]
        with patch.object(self.helper, 'remote_python', side_effect=AssertionError('must not contact worker')):
            for case in cases:
                build_control.write_json(path, case)
                self.assertFalse(build_control.inspect(self.helper, run)['actions']['stop']['supported'])

    def test_configured_snapshot_slots_keep_exact_local_and_worker_control(self):
        def transport(source, *args, **options):
            return subprocess.run([sys.executable, '-c', source, *args], check=True, **options)

        for host in ['local', 'fixture']:
            with self.subTest(host=host):
                run, execution, _, process, _ = self.launch(host, snapshot=True)
                with patch.object(self.helper, 'remote_python', side_effect=transport):
                    self.assertTrue(build_control.inspect(self.helper, run)['actions']['stop']['supported'])
                    self.assertEqual('Cancelled', build_control.execute(self.helper, 'stop', run, execution)['status'])
                self.assertEqual(130, process.wait(timeout=3))

    def test_cancel_waits_for_group_child_that_ignores_initial_interrupt(self):
        child = self.root / 'child.py'
        child_pid = self.root / 'child.pid'
        child.write_text('import os,pathlib,signal,time\nsignal.signal(signal.SIGINT, signal.SIG_IGN)\n'
                         f'pathlib.Path({str(child_pid)!r}).write_text(str(os.getpid()))\n'
                         'time.sleep(120)\n')
        program = ('import subprocess,time,pathlib\n'
                   f'p=subprocess.Popen([{sys.executable!r},{str(child)!r}])\n'
                   'time.sleep(120)\n')
        run, execution, _, process, _ = self.launch(command=[sys.executable, '-c', program])
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if child_pid.is_file() and child_pid.read_text().strip():
                break
            time.sleep(0.02)
        pid = int(child_pid.read_text())
        self.assertIsNotNone(build_control.process_identity(pid))
        self.assertEqual('Cancelled', build_control.execute(self.helper, 'stop', run, execution)['status'])
        self.assertEqual(130, process.wait(timeout=3))
        self.assertIsNone(build_control.process_identity(pid))

    def test_cli_explicit_state_is_json_and_never_uses_default_state(self):
        run, execution, _, process, _ = self.launch()
        build_control.write_json(self.helper.STATE / 'config.json', self.helper.CONFIG)
        command = [sys.executable, str(Path(__file__).with_name('remote-dev')), 'job-control', 'inspect', run,
                   '--state-root', str(self.helper.STATE)]
        result = subprocess.run(command, text=True, capture_output=True, check=True)
        self.assertTrue(json.loads(result.stdout)['actions']['stop']['supported'])
        command[3] = 'stop'
        result = subprocess.run([*command, '--execution', execution], text=True, capture_output=True, check=True)
        self.assertEqual('Cancelled', json.loads(result.stdout)['status'])
        self.assertEqual(130, process.wait(timeout=3))
        command[-1] = 'relative-state'
        failed = subprocess.run(command, text=True, capture_output=True)
        self.assertEqual(1, failed.returncode)
        self.assertFalse(json.loads(failed.stdout)['actions']['stop']['supported'])

    def test_invalid_receipt_id_and_unowned_paths_never_signal(self):
        for run in ['../outside', '/absolute', 'a/b']:
            with self.assertRaises(ValueError):
                build_control.inspect(self.helper, run)
        run, _, _, _, path = self.launch()
        target = path.with_name('outside.json')
        path.rename(target)
        path.symlink_to(target)
        with self.assertRaises(OSError):
            build_control.inspect(self.helper, run)


class RouterCancellationTest(unittest.TestCase):
    def test_snapshot_receipt_preserves_confirmed_local_cancellation_without_collection(self):
        import remote_build
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            state = root / '.remote-dev'
            (state / 'logs').mkdir(parents=True)
            slot = root / 'slot'
            wrapper = slot / 'fixture/gradle/wrapper/gradle-wrapper.properties'
            wrapper.parent.mkdir(parents=True)
            wrapper.write_text('fixture wrapper')
            transport = state / 'logs/fixture-transport.json'
            transport.write_text(json.dumps({'cancelled': True, 'exit_code': 130}))
            helper = SimpleNamespace(ROOT=root, STATE=state, REMOTE='/worker', CONFIG={'java_home': '/fixture'},
                                     LAST_RUN_ID='fixture-transport', local_gradle=lambda *args: 130)
            with patch.object(remote_build, 'disk_admission', return_value={}), \
                 patch.object(remote_build, 'materialize', return_value=slot), \
                 patch.object(remote_build.subprocess, 'run', return_value=SimpleNamespace(stdout='fixture java', stderr='')), \
                 patch.object(remote_build, 'collect_local', side_effect=AssertionError('cancelled snapshot collected')), \
                 self.assertRaises(KeyboardInterrupt):
                remote_build.isolated_build(helper, 'a' * 64, 'local', 'fixture', [':check'], 'snapshot-fixture')
            retained = json.loads((state / 'runs/snapshot-fixture/receipt.json').read_text())
            self.assertEqual('cancelled', retained['status'])
            self.assertEqual(130, retained['exit_code'])
            self.assertEqual(str(transport), retained['transportReceipt'])
            self.assertEqual(root, helper.ROOT)

    def test_local_keyboard_interrupt_persists_cancelled_and_stops_owned_execution(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve()
            helper.STATE = helper.ROOT / '.remote-dev'
            project = helper.ROOT / 'fixture'
            project.mkdir()
            wrapper = project / 'gradlew'
            wrapper.write_text('#!/bin/sh\nprintf "fixture active\\n"\nsleep 120\n')
            wrapper.chmod(0o755)

            def interrupt(line):
                if line.strip() == 'fixture active':
                    raise KeyboardInterrupt('fixture cancellation')

            import workspace_storage
            with patch.object(workspace_storage, 'automatic'), patch.object(helper, 'cache_environment', return_value={}), \
                 contextlib.redirect_stdout(io.StringIO()), self.assertRaises(KeyboardInterrupt):
                helper.local_gradle('fixture', Path('.'), [':check'], on_line=interrupt)
            saved = json.loads(next((helper.STATE / 'logs').glob('*.json')).read_text())
            self.assertTrue(saved['cancelled'])
            self.assertEqual(130, saved['exit_code'])
            self.assertTrue(saved['finished'])
            control = saved['execution_control']
            self.assertEqual('Cancelled', build_control.state(control['jobDirectory'], control['executionId'])['status'])

    def test_cancelled_worker_never_collects_or_publishes_outputs(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.STATE = Path(temporary).resolve()
            (helper.STATE / 'logs').mkdir()
            log = helper.STATE / 'gradle.log'
            saved = {'run_id': 'fixture-cancelled', 'project': 'fixture', 'remote_tasks': [':check'],
                     'log': str(log), 'execution_control': {'executionId': 'owned-execution'}}
            responses = [{'text': '', 'offset': 0, 'code': 130, 'started': 1, 'cancelled': True}]
            with patch.object(helper, 'remote_python', side_effect=[subprocess.CompletedProcess([], 0, json.dumps(value)) for value in responses]), \
                 patch.object(helper, 'pull', side_effect=AssertionError('cancelled output collected')), \
                 patch.object(helper, 'publish_build', side_effect=AssertionError('cancelled output published')):
                self.assertEqual(130, helper.finish_build(saved))
            retained = json.loads((helper.STATE / 'logs/fixture-cancelled.json').read_text())
            self.assertTrue(retained['cancelled'])
            self.assertEqual('Finished', retained['phase'])


if __name__ == '__main__':
    unittest.main()
