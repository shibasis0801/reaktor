import ast
import contextlib
import hashlib
import fcntl
import importlib.machinery
import importlib.util
import io
import json
import os
import shlex
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
import threading
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch


def router():
    path = Path(__file__).with_name('remote-dev')
    loader = importlib.machinery.SourceFileLoader('remote_router_fixture', str(path))
    module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
    sys.modules[loader.name] = module
    read_text = Path.read_text
    def fixture_config(file, *args, **kwargs):
        if file.name == 'config.json' and file.parent.name == '.remote-dev':
            return json.dumps({'host': 'fixture', 'remote_root': '/fixture', 'projects': [], 'worker_slots': 1})
        return read_text(file, *args, **kwargs)
    with patch.object(Path, 'read_text', fixture_config):
        loader.exec_module(module)
    return module


class SourceCaptureTest(unittest.TestCase):
    def capture(self, git):
        import remote_build
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary)
            project = helper.ROOT / 'fixture'
            paths = ['tests/reaktorDesktop/maestro/build/F-BUILD-10-deliverables.yaml',
                     'tests/reaktorDesktop/maestro/build/F-BUILD-11-status.yaml',
                     'build/generated.bin', 'modules/engine/build/classes/generated.bin',
                     'tests/reaktorDesktop/maestro/build/node_modules/generated.bin',
                     'tests/reaktorDesktop/maestro/build/build/generated.bin']
            for relative in paths:
                target = project / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(relative)
            if git:
                subprocess.run(['git', 'init', '-q', str(project)], check=True)
                (project / '.git/info/exclude').write_text('build/\n')
                subprocess.run(['git', '-C', str(project), 'add', '-f', paths[0]], check=True)
            selected = helper.source_files('fixture')
            self.assertEqual(paths[:2], selected)
            capture = SimpleNamespace(ROOT=helper.ROOT, STATE=helper.ROOT / '.state', PROJECTS=['fixture'],
                                      source_files=helper.source_files, git_repositories=lambda _: [])
            identity = remote_build.freeze(capture)
            frozen = capture.STATE / 'snapshots' / identity
            manifest = json.loads((frozen / 'manifest.json').read_text())
            self.assertEqual({f'fixture/{relative}' for relative in paths[:2]}, set(manifest['files']))
            for relative in paths[:2]:
                self.assertEqual(relative, (frozen / 'workspace/fixture' / relative).read_text())

    def test_git_capture_retains_tracked_and_untracked_build_flows(self):
        self.capture(True)

    def test_non_git_capture_retains_build_flows(self):
        self.capture(False)


class LocalBuildReceiptTest(unittest.TestCase):
    def test_local_job_is_observable_while_active_and_updates_its_receipt_on_exit(self):
        import workspace_storage
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve()
            helper.STATE = helper.ROOT / '.remote-dev'
            project = helper.ROOT / 'fixture'
            project.mkdir()
            release = project / 'release'
            wrapper = project / 'gradlew'
            wrapper.write_text('#!/bin/sh\nprintf "fixture active\\n"\n'
                               f'while [ ! -f {shlex.quote(str(release))} ]; do sleep 0.02; done\nexit 7\n')
            wrapper.chmod(0o755)
            active = []

            def observe(line):
                if line.strip() != 'fixture active':
                    return
                receipts = list((helper.STATE / 'logs').glob('*.json'))
                self.assertEqual(1, len(receipts))
                receipt = json.loads(receipts[0].read_text())
                self.assertEqual('local', receipt['worker'])
                self.assertEqual([':fixture:check'], receipt['tasks'])
                self.assertEqual(str(helper.ROOT), receipt['workspace_root'])
                self.assertIsNone(receipt['exit_code'])
                self.assertNotIn('finished', receipt)
                self.assertEqual(os.getpid(), receipt['owner']['pid'])
                self.assertTrue(receipt['owner']['started'])
                self.assertTrue(workspace_storage.owner_alive(receipt, workspace_storage.process_table()))
                active.append((receipts[0], receipt))
                release.touch()

            with patch.object(workspace_storage, 'automatic'), \
                 patch.object(helper, 'cache_environment', return_value={}), \
                 contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(7, helper.local_gradle('fixture', Path('.'), ['--', ':fixture:check'], on_line=observe))
            self.assertEqual(1, len(active))
            path, initial = active[0]
            finished = json.loads(path.read_text())
            self.assertEqual(initial['run_id'], finished['run_id'])
            self.assertEqual(initial['owner'], finished['owner'])
            self.assertEqual(7, finished['exit_code'])
            self.assertTrue(finished['finished'])
            self.assertGreaterEqual(finished['execution_seconds'], 0)
            self.assertEqual([path], list(path.parent.glob('*.json')))


class DryRunTransportTest(unittest.TestCase):
    def test_artifact_reference_skips_malformed_and_incomplete_receipts(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.STATE = Path(temporary)
            (helper.STATE / 'logs').mkdir()
            receipts = {
                '01-valid': json.dumps({'artifact_family': 'metadata', 'exit_code': 0, 'finished': 'done'}),
                '02-unfinished': json.dumps({'artifact_family': 'metadata', 'exit_code': 0}),
                '03-invalid-type': '[]',
                '04-empty': '',
            }
            for name, contents in receipts.items():
                (helper.STATE / 'artifacts' / name / 'bestbuds').mkdir(parents=True)
                (helper.STATE / 'logs' / (name + '.json')).write_text(contents)
            self.assertEqual(helper.STATE / 'artifacts/01-valid/bestbuds',
                             helper.artifact_reference('bestbuds', 'current', 'metadata'))

    def test_local_admission_reserves_memory_for_interactive_work(self):
        helper = router()
        gib = 1024 ** 3
        cases = [
            (1, 2 * gib, 0, True),
            (2, 2 * gib, 0, False),
            (4, 2 * gib, 0, False),
            (1, 14 * gib, 5 * gib, False),
            (1, 9 * gib, 0, False),
            (1, 2 * gib, 2 * gib, False),
        ]
        for pressure, compressed, swap, admitted in cases:
            output = [
                'CPU usage: 10% user, 10% sys, 80% idle',
                'System-wide memory free percentage: 59%',
                f'page size of 16384 bytes\nPages occupied by compressor: {compressed // 16384}.',
                f'hw.memsize: {48 * gib}\nkern.memorystatus_vm_pressure_level: {pressure}\n'
                f'vm.swapusage: total = 6144.00M used = {swap / 1024 ** 2:.2f}M free = 0.00M',
            ]
            with self.subTest(pressure=pressure, compressed=compressed, swap=swap), \
                 patch.object(helper.subprocess, 'run', side_effect=[SimpleNamespace(stdout=value) for value in output]):
                self.assertEqual(admitted, helper.local_capacity()[0])

    def test_remote_python_streams_long_source_and_preserves_payload_and_output_modes(self):
        helper = router()
        commands = []
        def execute(command, **kwargs):
            commands.append(command)
            return subprocess.run([sys.executable, *shlex.split(command)[1:]], check=True, **kwargs)
        argument = "quoted ' value with spaces\n" * 4000
        source = '# large source\n' * 4000 + 'import sys; print(sys.argv[1])'
        with patch.object(helper, 'ssh', side_effect=execute):
            for text in (False, True):
                result = helper.remote_python(source, argument, capture_output=True, text=text)
                self.assertEqual(argument + '\n', result.stdout if text else result.stdout.decode())
                self.assertNotIn('large source', commands[-1])
                self.assertNotIn(argument, commands[-1])
            result = helper.remote_python('import sys; print(sys.argv[1]); print(sys.stdin.read())',
                                          argument, input='environment payload', capture_output=True, text=True)
            self.assertEqual(argument + '\nenvironment payload\n', result.stdout)

    def test_worker_and_workspace_activity_do_not_block_an_available_local_host(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary)
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.STATE.mkdir()
            with (helper.STATE / 'workspace.lock').open('a') as workspace, \
                 (helper.STATE / 'worker-gradle.lock').open('a') as worker:
                fcntl.flock(workspace, fcntl.LOCK_EX)
                fcntl.flock(worker, fcntl.LOCK_EX)
                with patch.object(helper, 'local_capacity', return_value=(True, 'fixture capacity')), \
                     patch.object(helper, 'local_gradle', return_value=7) as local, \
                     patch.object(helper, 'build') as remote:
                    self.assertEqual(7, helper.scheduled_gradle('bestbuds', '.', [':app:jvmTest']))
                    self.assertEqual(1, local.call_count)
                    remote.assert_not_called()

    def test_local_activity_does_not_block_the_worker(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary)
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.STATE.mkdir()
            with (helper.STATE / 'local-gradle.lock').open('a') as local:
                fcntl.flock(local, fcntl.LOCK_EX)
                def worker_build(*args, **kwargs):
                    with (helper.STATE / 'worker-gradle.lock').open('a') as worker:
                        with self.assertRaises(BlockingIOError):
                            fcntl.flock(worker, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    return 0
                with patch.object(helper, 'local_capacity', return_value=(True, 'fixture capacity')), \
                     patch.object(helper, 'local_gradle') as build_local, \
                     patch.object(helper, 'worker_admission', return_value=(True, 'fixture capacity')), \
                     patch('workspace_storage.automatic'), \
                     patch.object(helper, 'build', side_effect=worker_build):
                    self.assertEqual(0, helper.scheduled_gradle('bestbuds', '.', [':app:jvmTest']))
                    build_local.assert_not_called()

    def test_queued_work_rechecks_local_capacity_while_the_worker_is_occupied(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary)
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.STATE.mkdir()
            with (helper.STATE / 'worker-gradle.lock').open('a') as worker:
                fcntl.flock(worker, fcntl.LOCK_EX)
                with patch.object(helper, 'local_capacity', side_effect=[(False, 'busy'), (True, 'free')]), \
                     patch.object(helper.time, 'sleep') as pause, \
                     patch.object(helper, 'local_gradle', return_value=0) as local, \
                     patch.object(helper, 'build') as remote:
                    self.assertEqual(0, helper.scheduled_gradle('bestbuds', '.', [':app:jvmTest']))
                    pause.assert_called_once_with(5)
                    self.assertEqual(1, local.call_count)
                    remote.assert_not_called()

    def test_output_publication_waits_for_the_local_compiler(self):
        helper = router()
        with TemporaryDirectory() as temporary, ThreadPoolExecutor(max_workers=1) as pool:
            helper.ROOT = Path(temporary)
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.STATE.mkdir()
            attempted, published = threading.Event(), threading.Event()
            def publish():
                attempted.set()
                with helper.checkout_outputs():
                    published.set()
            with (helper.STATE / 'local-gradle.lock').open('a') as local:
                fcntl.flock(local, fcntl.LOCK_EX)
                future = pool.submit(publish)
                self.assertTrue(attempted.wait(5))
                self.assertFalse(published.wait(0.05))
            future.result(timeout=5)
            self.assertTrue(published.is_set())

    def test_collected_build_releases_worker_before_waiting_to_publish(self):
        helper = router()
        with TemporaryDirectory() as temporary, ThreadPoolExecutor(max_workers=1) as pool:
            helper.ROOT = Path(temporary)
            helper.STATE = helper.ROOT / '.remote-dev'
            (helper.STATE / 'logs').mkdir(parents=True)
            receipt = {'project': 'bestbuds', 'run_id': 'run', 'exit_code': 0,
                       'pending_publications': [('bestbuds', ['artifact.apk'])]}
            waiting = threading.Event()
            checkout = helper.checkout_outputs
            def acquire_checkout():
                waiting.set()
                return checkout()
            with (helper.STATE / 'local-gradle.lock').open('a') as local, \
                 (helper.STATE / 'worker-gradle.lock').open('a') as worker:
                fcntl.flock(local, fcntl.LOCK_EX)
                fcntl.flock(worker, fcntl.LOCK_EX)
                with patch.object(helper, 'checkout_outputs', side_effect=acquire_checkout), \
                     patch.object(helper, 'publish_artifacts') as publish:
                    future = pool.submit(helper.publish_build, receipt, worker)
                    try:
                        self.assertTrue(waiting.wait(5))
                        with (helper.STATE / 'worker-gradle.lock').open('a') as next_worker:
                            fcntl.flock(next_worker, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        publish.assert_not_called()
                    finally:
                        fcntl.flock(local, fcntl.LOCK_UN)
                    self.assertEqual(0, future.result(timeout=5))
                    publish.assert_called_once_with('bestbuds', 'run', ['artifact.apk'], True)
            self.assertNotIn('pending_publications', receipt)
            self.assertIn('finished', receipt)

    def test_interrupted_publication_resumes_staged_outputs_without_reading_worker(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary)
            helper.STATE = helper.ROOT / '.remote-dev'
            (helper.STATE / 'logs').mkdir(parents=True)
            receipt = {'project': 'bestbuds', 'run_id': 'run', 'exit_code': 0,
                       'pending_publications': [('bestbuds', ['artifact.apk'])]}
            saved = helper.STATE / 'logs/run.json'
            saved.write_text(json.dumps(receipt))
            with patch.object(helper, 'publish_artifacts', side_effect=KeyboardInterrupt), \
                 patch.object(helper, 'remote_python') as remote:
                with self.assertRaises(KeyboardInterrupt):
                    helper.finish_build(receipt)
                self.assertNotIn('finished', receipt)
                self.assertIn('pending_publications', receipt)
                remote.assert_not_called()
            receipt = json.loads(saved.read_text())
            self.assertNotIn('finished', receipt)
            with patch.object(helper, 'publish_artifacts') as publish, \
                 patch.object(helper, 'remote_python') as remote:
                self.assertEqual(0, helper.finish_build(receipt))
                publish.assert_called_once_with('bestbuds', 'run', ['artifact.apk'], True)
                remote.assert_not_called()

    def test_storage_cleanup_evicts_a_killed_runs_outputs_and_keeps_a_run_waiting_to_publish(self):
        import workspace_storage
        helper = router()
        with TemporaryDirectory() as temporary, ThreadPoolExecutor(max_workers=1) as pool:
            helper.ROOT = Path(temporary).resolve()
            helper.STATE = helper.ROOT / '.remote-dev'
            (helper.STATE / 'logs').mkdir(parents=True)
            killed = subprocess.Popen(['/bin/sleep', '30'])
            owners = {'killed': workspace_storage.run_owner(killed.pid), 'waiting': workspace_storage.run_owner()}
            killed.kill()
            killed.wait()
            receipts = {}
            for name, owner in owners.items():
                staged = helper.STATE / 'artifacts' / name / 'bestbuds/app.apk'
                staged.parent.mkdir(parents=True)
                staged.write_bytes(b'x' * 5000)
                receipts[name] = {'project': 'bestbuds', 'run_id': name, 'exit_code': 0, 'owner': owner,
                                  'pending_publications': [('bestbuds', ['app.apk'])]}
                (helper.STATE / 'logs' / (name + '.json')).write_text(json.dumps(receipts[name]))
            config = {'remote_limit_bytes': 100, 'tmp_limit_bytes': 10 ** 9, 'minimum_idle_seconds': 900}
            with (helper.STATE / 'local-gradle.lock').open('a') as local, \
                 patch.object(helper, 'publish_artifacts') as publish:
                fcntl.flock(local, fcntl.LOCK_EX)
                waiting = pool.submit(helper.publish_build, receipts['waiting'])
                try:
                    with patch.object(workspace_storage, 'open_paths', return_value=(set(), set())), \
                         patch.object(workspace_storage, 'managed_temps', return_value=([], [], [])), \
                         patch.object(workspace_storage.time, 'time', return_value=time.time() + 3600):
                        report = workspace_storage.cleanup(helper.ROOT, helper.STATE, config, held_locks={'local-gradle'})
                    self.assertFalse(waiting.done())
                finally:
                    fcntl.flock(local, fcntl.LOCK_UN)
                self.assertEqual(0, waiting.result(timeout=5))
                publish.assert_called_once_with('bestbuds', 'waiting', ['app.apk'], True)
            self.assertEqual(['killed'], [entry['run'] for entry in report['remote']['abandoned']])
            self.assertIn(str(helper.STATE / 'artifacts/killed'), [entry['path'] for entry in report['remote']['removed']])
            self.assertFalse((helper.STATE / 'artifacts/killed').exists())
            self.assertTrue((helper.STATE / 'artifacts/waiting/bestbuds/app.apk').exists())
            self.assertIn('finished', json.loads((helper.STATE / 'logs/waiting.json').read_text()))

    def test_model_metadata_belongs_to_the_selected_build(self):
        helper = router()
        for runtime in (True, False):
            for relative, arguments, metadata in (
                    ('.', [':gradle:compileKotlin'], True),
                    ('.', ['--dry-run', ':gradle:compileKotlin'], False),
                    ('.', ['-m', ':gradle:compileKotlin'], False),
                    ('.', ['-p', 'dependeasy', ':gradle:compileKotlin'], False),
                    ('.', ['--project-dir=dependeasy', ':gradle:compileKotlin'], False),
                    ('.', ['-p=dependeasy', ':gradle:compileKotlin'], False),
                    ('dependeasy', [':gradle:compileKotlin'], False),
                    ('dependeasy', ['--project-dir', '..', 'help'], True)):
                with self.subTest(runtime=runtime, relative=relative, arguments=arguments), TemporaryDirectory() as temporary:
                    helper.ROOT = Path(temporary)
                    helper.STATE = helper.ROOT / '.remote-dev'
                    (helper.STATE / 'logs').mkdir(parents=True)
                    root = helper.ROOT / 'renamed-product'
                    (root / 'gradle').mkdir(parents=True)
                    (root / 'gradlew').touch()
                    if runtime:
                        (root / 'gradle/gradle-daemon-jvm.properties').write_text('toolchainVersion=25\n')
                    contract = root / 'build/dependeasy/remote/hosts.json'
                    contract.parent.mkdir(parents=True)
                    contract.write_text(json.dumps({'schema': 2, 'taskFamilies': {':exportBuildModel': 'metadata'}}))
                    helper.CONFIG['java_home'] = '/fixture/java'
                    with patch.dict(os.environ, {}, clear=True), \
                         patch.object(helper, 'cache_environment', return_value={}), \
                         patch.object(helper, 'remote_python') as remote, \
                         patch.object(helper, 'finish_build', return_value=0) as finish:
                        self.assertEqual(0, helper.build('renamed-product', relative, arguments, synchronize=False))
                        receipt = finish.call_args.args[0]
                        self.assertEqual(metadata, ':exportBuildModel' in receipt['remote_tasks'])
                        self.assertEqual(arguments, receipt['tasks'])
                        payload = json.loads(remote.call_args.kwargs['input'])
                        self.assertEqual('/fixture/java' if runtime else '/Users/ovd/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home', payload['JAVA_HOME'])
                        command = remote.call_args.args[3]
                        self.assertEqual(runtime, '-Dorg.gradle.java.home=/fixture/java' in command)
                        self.assertEqual(not runtime, '-Dorg.gradle.jvmargs=-Xmx8g' in command)
                        contract.unlink()
                        self.assertEqual(0, helper.build('renamed-product', '.', ['help'], synchronize=False))
                        self.assertNotIn(':exportBuildModel', finish.call_args.args[0]['remote_tasks'])
                    nested = root / 'dependeasy'
                    nested.mkdir(exist_ok=True)
                    (nested / 'settings.gradle.kts').touch()
                    self.assertFalse(helper.declared_gradle_runtime('renamed-product', '.', ['-p', 'dependeasy']))
                    self.assertEqual(runtime, helper.declared_gradle_runtime('renamed-product', '.', []))

    def test_xcode_environment_comes_from_the_selected_build_contract(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve()
            helper.STATE = helper.ROOT / '.remote-dev'
            (helper.STATE / 'logs').mkdir(parents=True)
            root = helper.ROOT / 'reaktor'
            root.mkdir()
            (root / 'gradlew').touch()
            contract = root / 'build/dependeasy/remote/hosts.json'
            contract.parent.mkdir(parents=True)
            contract.write_text(json.dumps({'schema': 2, 'xcodeEnvironment': ['FIXTURE_XCODE_VALUE'],
                                            'environment': ['FIXTURE_OPTION', 'ORG_GRADLE_PROJECT_fixture.feature', 'DEPENDEASY_OPERATION_RUN_ID']}))
            helper.CONFIG['java_home'] = '/fixture/java'
            helper.CONFIG['forward_env'] = []
            helper.CONFIG['forward_env_prefixes'] = []
            with patch.dict(os.environ, {'FIXTURE_XCODE_VALUE': str(root / 'product'), 'FIXTURE_OPTION': '',
                                         'ORG_GRADLE_PROJECT_fixture.feature': 'true', 'DEPENDEASY_OPERATION_RUN_ID': 'run-fixture',
                                         'UNDECLARED_VALUE': 'excluded'}, clear=True), \
                 patch.object(helper, 'cache_environment', return_value={}), \
                 patch.object(helper, 'remote_python') as remote, \
                 patch.object(helper, 'finish_build', return_value=0):
                self.assertEqual(0, helper.build('reaktor', '.', ['help'], synchronize=False))
                payload = json.loads(remote.call_args.kwargs['input'])
                self.assertEqual(helper.REMOTE + '/reaktor/product', payload['FIXTURE_XCODE_VALUE'])
                self.assertEqual('', payload['FIXTURE_OPTION'])
                self.assertEqual('true', payload['ORG_GRADLE_PROJECT_fixture.feature'])
                self.assertEqual('run-fixture', payload['DEPENDEASY_OPERATION_RUN_ID'])
                self.assertNotIn('UNDECLARED_VALUE', payload)
                contract.write_text(json.dumps({'schema': 2, 'environment': ['invalid key']}))
                with self.assertRaisesRegex(ValueError, 'Invalid build option'):
                    helper.build('reaktor', '.', ['help'], synchronize=False)
                contract.write_text(json.dumps({'schema': 2, 'xcodeEnvironment': ['invalid-key']}))
                with self.assertRaisesRegex(ValueError, 'Invalid Xcode'):
                    helper.build('reaktor', '.', ['help'], synchronize=False)

    def test_declared_local_kernel_is_routed_before_capacity_selection(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve()
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.STATE.mkdir()
            contract = helper.ROOT / 'reaktor/build/dependeasy/remote/hosts.json'
            contract.parent.mkdir(parents=True)
            contract.write_text(json.dumps({'schema': 2, 'taskHosts': {':arbitraryKernel': {'execution': 'Local', 'platform': 'Macos'}}}))
            with patch.object(helper, 'local_capacity', return_value=(False, 'fixture busy')) as capacity, \
                 patch.object(helper, 'local_gradle', return_value=0) as local:
                self.assertEqual(0, helper.route_gradle('reaktor', '.', ['arbitraryKernel']))
                self.assertEqual(1, local.call_count)
                capacity.assert_not_called()

                with patch.object(helper.time, 'sleep', side_effect=AssertionError('Local build waits behind its own ticket')), \
                     patch.object(helper, 'claim_worker_slot', side_effect=AssertionError('Declared local build reached worker admission')):
                    self.assertEqual(0, helper.scheduled_gradle('reaktor', '.', ['arbitraryKernel']))
                self.assertEqual(2, local.call_count)
                self.assertEqual([], list((helper.STATE / 'local-gradle.line').iterdir()))

    def test_app_run_frees_the_local_lease_once_its_app_runs_from_retained_jars(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve()
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.STATE.mkdir()
            launches = helper.ROOT / 'launches'
            launches.mkdir()
            (launches / 'earlier.classpath').write_text('jar')
            contract = helper.ROOT / 'reaktor/build/dependeasy/remote/hosts.json'
            contract.parent.mkdir(parents=True)
            contract.write_text(json.dumps({'schema': 2, 'taskHosts': {
                ':desk:run': {'execution': 'Local'}, ':desk:test': {'execution': 'Local'}}}))

            def free():
                with (helper.STATE / 'local-gradle.lock').open('a') as other:
                    try:
                        fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        return False
                    fcntl.flock(other, fcntl.LOCK_UN)
                    return True

            observed = []

            def gradle(name, relative, args, on_line=None):
                on_line('> Task :desk:compileKotlin\n')
                on_line('> Task :desk:run\n')
                time.sleep(0.8)
                observed.append(free())
                (launches / 'mine.classpath').write_text('jar')
                deadline = time.monotonic() + 5
                while not free() and time.monotonic() < deadline:
                    time.sleep(0.05)
                observed.append(free())
                return 0

            with patch.object(helper, 'DESKTOP_LAUNCHES', launches), patch.object(helper, 'local_gradle', side_effect=gradle):
                self.assertEqual(0, helper.route_gradle('reaktor', '.', ['desk:run']))
            self.assertEqual([False, True], observed)

            with patch.object(helper, 'DESKTOP_LAUNCHES', launches), patch.object(helper, 'local_gradle', return_value=0) as local:
                self.assertEqual(0, helper.route_gradle('reaktor', '.', ['desk:test']))
                self.assertIsNone(local.call_args.kwargs['on_line'])

    def test_request_target_uses_its_declared_host_even_when_the_mac_is_busy(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve()
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.STATE.mkdir()
            root = helper.ROOT / 'reaktor'
            folder = root / 'build/dependeasy/contracts/gradle/requests'
            folder.mkdir(parents=True)
            content = json.dumps({'schema': 1, 'node': {'build': 'reaktor', 'project': ':', 'name': 'chosen'}}).encode()
            file = folder / (hashlib.sha256(content).hexdigest() + '.json')
            file.write_bytes(content)
            contract = root / 'build/dependeasy/remote/hosts.json'
            contract.parent.mkdir(parents=True)
            contract.write_text(json.dumps({'schema': 2, 'builds': {'reaktor': '.'}, 'taskHosts': {':chosen': {'execution': 'Local'}}}))
            with patch.object(helper, 'local_capacity') as capacity, patch.object(helper, 'local_gradle', return_value=0) as local:
                self.assertEqual(0, helper.route_gradle('reaktor', '.', ['bridge', '-Pdependeasy.gradleRequest=' + str(file)]))
                capacity.assert_not_called()
                self.assertEqual(1, local.call_count)
            file.write_bytes(content + b' ')
            with self.assertRaisesRegex(ValueError, 'content changed'):
                helper.route_gradle('reaktor', '.', ['bridge', '-Pdependeasy.gradleRequest=' + str(file)])

    def test_artifact_family_uses_declarations_without_task_name_guesses(self):
        helper = router()
        declared = {':arbitrary': 'apple', ':metadata': 'metadata', ':different': 'android'}
        self.assertEqual('apple', helper.artifact_family(['arbitrary', 'metadata'], declared))
        self.assertEqual('metadata', helper.artifact_family(['metadata'], declared))
        self.assertEqual('all', helper.artifact_family(['arbitrary', 'different'], declared))
        self.assertEqual('all', helper.artifact_family(['compileKotlinIosArm64'], declared))

    def test_excluded_tasks_and_option_values_are_not_requested_tasks(self):
        helper = router()
        declared = {':kernel:agentDist': 'jvm', ':exportBuildModel': 'metadata', ':kernel:test': 'verification'}
        self.assertEqual([':kernel:agentDist'], helper.gradle_tasks([':kernel:agentDist', '-x', ':exportBuildModel']))
        self.assertEqual('jvm', helper.artifact_family([':kernel:agentDist', '-x', ':exportBuildModel'], declared))
        self.assertEqual('jvm', helper.artifact_family([':kernel:agentDist', '--exclude-task', ':exportBuildModel',
                                                        '-P', 'flag=1', '--console', 'plain'], declared))
        options = {':kernel:test': [{'name': 'tests', 'type': 'String', 'multiple': True}],
                   ':arbitrary': [{'name': 'platform', 'type': 'String'}, {'name': 'count', 'type': 'Integer'},
                                  {'name': 'debug', 'type': 'Boolean'}]}
        self.assertEqual('verification', helper.artifact_family([':kernel:test', '--tests', '*ChangeChecksTest*',
                                                                 '--exclude-task=:exportBuildModel'], declared, options))
        declared.update({':arbitrary': 'apple', ':framework:check': 'verification'})
        options[':framework:check'] = options[':kernel:test']
        for arguments in (['--platform', 'ios'], ['--platform=ios'], ['--count', '2'], ['--count=2']):
            self.assertEqual([':arbitrary'], helper.gradle_tasks([':arbitrary', *arguments], options))
            self.assertEqual('apple', helper.artifact_family([':arbitrary', *arguments], declared, options))
        arguments = [':kernel:test', '--tests', ':not:a:task', '--tests=fixture.Two', ':framework:check', '--tests', 'fixture.Three']
        self.assertEqual([':kernel:test', ':framework:check'], helper.gradle_tasks(arguments, options))
        self.assertEqual('verification', helper.artifact_family(arguments, declared, options))
        self.assertEqual([':arbitrary', ':kernel:test'], helper.gradle_tasks([':arbitrary', '--debug', ':kernel:test'], options))
        self.assertEqual('all', helper.artifact_family([':arbitrary', '--unknown', 'ios'], declared, options))
        self.assertEqual('all', helper.artifact_family(['arbit', '--platform', 'ios'], declared, options))
        self.assertEqual('all', helper.artifact_family([':kernel:test', '--platform', 'ios'], declared, options))
        self.assertEqual('all', helper.artifact_family([':kernel:test', ':kernel:agentDist'], declared))

    def test_packaging_and_test_families_return_their_outputs_without_classes_or_libraries(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve() / 'local'
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'bestbuds'
            outputs = {'package': ['modules/kernel/build/agent-dist/lib/kernel.jar',
                                   'targets/reaktorDesktop/build/compose/binaries/main/app/Hangar.app/Contents/Info.plist',
                                   'modules/code/build/distributions/kotlin-semantics.zip'],
                       'results': ['modules/kernel/build/test-results/test/TEST-Kernel.xml',
                                   'modules/kernel/build/reports/tests/test/index.html'],
                       'compiled': ['modules/kernel/build/libs/kernel.jar',
                                    'modules/kernel/build/classes/kotlin/main/Kernel.class',
                                    'modules/kernel/build/resources/main/kernel.properties']}
            for path in sum(outputs.values(), []):
                (base / path).parent.mkdir(parents=True, exist_ok=True)
                (base / path).write_text(path)
            with patch.object(helper, 'remote_python', side_effect=local_worker), patch.object(helper, 'run', side_effect=rsync_from(base)):
                packaged = {file for file in helper.pull('bestbuds', 'jvm-run', family='jvm', publish=False) if not file.endswith('/')}
                tested = {file for file in helper.pull('bestbuds', 'test-run', family='verification', publish=False) if not file.endswith('/')}
            self.assertEqual(set(outputs['package'] + outputs['results']), packaged)
            self.assertEqual(set(outputs['results']), tested)

    def test_declared_jvm_archive_returns_without_unrelated_jars_or_compiled_trees(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve() / 'local'
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'bestbuds'
            archive = 'modules/core/build/libs/core.jar'
            other = 'modules/other/build/libs/other.jar'
            for relative in (archive, other, 'modules/core/build/classes/kotlin/main/Core.class',
                             'modules/core/build/resources/main/core.properties',
                             'modules/core/build/reports/tests/test/index.html'):
                file = base / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(relative)
            with (base / archive).open('ab') as output:
                output.truncate(8 * 1024 * 1024 + 1)
            hosts = base / 'build/dependeasy/remote/hosts.json'
            hosts.parent.mkdir(parents=True)
            hosts.write_text(json.dumps({'schema': 2,
                'taskFamilies': {':core:jar': 'jvm', ':other:jar': 'jvm'},
                'publicOutputs': {':core:jar': [archive], ':other:jar': [other]},
                'outputRoots': {'jvm': ['reports', 'test-results'], 'metadata': []}}))
            with patch.object(helper, 'remote_python', side_effect=local_worker), patch.object(helper, 'run', side_effect=rsync_from(base)):
                files = helper.pull('bestbuds', 'archive-run', family='jvm', metadata=True, publish=False,
                                    observed_tasks=[':core:jar'])
            returned = {file for file in files if not file.endswith('/')}
            self.assertEqual({archive, 'modules/core/build/reports/tests/test/index.html',
                              'build/dependeasy/remote/hosts.json'}, returned)
            self.assertEqual(8 * 1024 * 1024 + 1,
                             (helper.STATE / 'artifacts/archive-run/bestbuds' / archive).stat().st_size)

    def test_private_request_is_bounded_and_only_one_contract_is_synchronized(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve()
            root = helper.ROOT / 'bestbuds'
            folder = root / 'build/dependeasy/contracts/gradle/requests'
            folder.mkdir(parents=True)
            content = json.dumps({'schema': 1, 'node': {'build': 'bestbuds', 'project': ':', 'name': 'checks'}}).encode()
            file = folder / (hashlib.sha256(content).hexdigest() + '.json')
            file.write_bytes(content)
            args = ['planBuildNode', '-Pdependeasy.gradleRequest=' + str(file)]
            with patch.object(helper, 'remote_python') as remote, patch.object(helper, 'run') as copy:
                self.assertEqual([], helper.synchronize_gradle_request('bestbuds', '.', args))
                self.assertEqual(2, remote.call_count)
                self.assertEqual(str(file), copy.call_args.args[0][2])
                file.write_bytes(content + b' ')
                with self.assertRaisesRegex(ValueError, 'content changed'):
                    helper.synchronize_gradle_request('bestbuds', '.', args)
                file.write_bytes(content)
                link = root / 'escape.json'
                link.symlink_to(file)
                with self.assertRaisesRegex(ValueError, 'escapes'):
                    helper.synchronize_gradle_request('bestbuds', '.', ['planBuildNode', '-Pdependeasy.gradleRequest=' + str(link)])
                self.assertEqual(1, copy.call_count)
                node_id = json.loads(content)['node']
                model_file = root / 'build/dependeasy/model.json'
                model = {'schema': 4, 'builds': [{'name': 'bestbuds', 'directory': '.'}], 'nodes': [{
                    'id': node_id, 'invocation': {
                        'type': 'dev.shibasis.dependeasy.model.Invocation.Gradle',
                        'tasks': [':checkApp', ':checkWeb']}}]}
                model_file.write_text(json.dumps(model))
                execute = ['executeBuildNode', '-Pdependeasy.gradleRequest=' + str(file)]
                self.assertEqual([':checkApp', ':checkWeb'], helper.synchronize_gradle_request('bestbuds', '.', execute))
                model['builds'][0]['name'] = 'other'
                model_file.write_text(json.dumps(model))
                self.assertEqual([':bestbuds:checkApp', ':bestbuds:checkWeb'], helper.synchronize_gradle_request('bestbuds', '.', execute))
                for schema in [2, 3, 5]:
                    model['schema'] = schema
                    model_file.write_text(json.dumps(model))
                    with self.assertRaisesRegex(ValueError, 'exportBuildModel'):
                        helper.synchronize_gradle_request('bestbuds', '.', execute)

    def test_failure_returns_public_receipts_and_plans_without_private_requests(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve() / 'local'
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'bestbuds'
            for category in ('plans', 'receipts', 'runs', 'contracts/gradle/requests'):
                file = base / 'build/dependeasy' / category / 'fixture.json'
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('{}')
            def remote(source, *args, **kwargs):
                return subprocess.run([sys.executable, '-c', source, *args], **kwargs, check=True)
            def copy(argv, **kwargs):
                if argv[0] != 'rsync': return
                manifest = next(arg.split('=', 1)[1] for arg in argv if arg.startswith('--files-from='))
                destination = Path(argv[-1])
                for relative in Path(manifest).read_bytes().split(b'\0'):
                    if not relative: continue
                    relative = os.fsdecode(relative)
                    target = destination / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(base / relative, target)
            with patch.object(helper, 'remote_python', side_effect=remote), patch.object(helper, 'run', side_effect=copy):
                files = helper.pull('bestbuds', 'fixture-run', successful=False, family='metadata', metadata=False)
            self.assertEqual({'build/dependeasy/' + category + '/fixture.json' for category in ('plans', 'receipts', 'runs')}, set(files))

    def test_automatic_collection_returns_current_protocol_evidence_without_old_history(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve() / 'local'
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'bestbuds'
            historical = set()
            for category in ('plans', 'receipts', 'runs'):
                for index in range(30):
                    relative = f'build/dependeasy/{category}/old-{index}.json'
                    file = base / relative
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text('{}')
                    historical.add(relative)
            time.sleep(0.05)
            started = time.time()
            time.sleep(0.05)
            current = {f'build/dependeasy/{category}/current.json' for category in ('plans', 'receipts', 'runs')}
            current.add('build/dependeasy/model.json')
            for relative in current:
                (base / relative).write_text('{}')
            hosts = base / 'build/dependeasy/remote/hosts.json'
            hosts.parent.mkdir(parents=True)
            hosts.write_text(json.dumps({'schema': 2, 'outputRoots': {'metadata': []}}))
            current.add('build/dependeasy/remote/hosts.json')
            with patch.object(helper, 'remote_python', side_effect=local_worker), \
                 patch.object(helper, 'run', side_effect=rsync_from(base)):
                for successful in (True, False):
                    files = helper.pull('bestbuds', 'current-' + str(successful), successful=successful,
                                        family='metadata', publish=False, report_since=started)
                    self.assertEqual(current, set(files))
                files = helper.pull('bestbuds', 'manual', family='metadata', publish=False)
                self.assertEqual(current | historical, set(files))
            self.assertTrue(all((base / relative).is_file() for relative in historical))

    def test_planning_never_transfers_or_installs_and_builds_still_do(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.PROJECTS = ['renamed-product', 'framework', 'unused']
            helper.REMOTE = '/worker'
            log = Path(temporary) / 'composite.log'
            log.write_text('> Task :dependeasy:gradle:jar UP-TO-DATE\n> Task :unknown:test\n')
            layout = {'schema': 2, 'builds': {'Product': '.', 'Framework': '../framework',
                      'dependeasy': '../framework/dependeasy', 'outside': '../../outside'},
                      'taskFamilies': {':publish': 'metadata'},
                      'taskOptions': {':publish': [{'name': 'output', 'type': 'String'}]}}
            receipt = {'project': 'renamed-product', 'remote_path': '/worker/renamed-product', 'log': str(log)}
            with patch.object(helper, 'remote_python', return_value=SimpleNamespace(stdout=json.dumps(layout))):
                owners, projects, families, options = helper.composite_artifacts(receipt)
                self.assertEqual({'renamed-product', 'framework'}, set(projects))
                self.assertEqual('framework', owners['dependeasy'])
                self.assertNotIn('outside', owners)
                self.assertEqual({':publish': 'metadata'}, families)
                self.assertEqual(layout['taskOptions'], options)
                log.write_text('> Task :ownTask UP-TO-DATE\n')
                self.assertEqual(['renamed-product'], list(helper.composite_artifacts(receipt)[1]))
                self.assertEqual(owners, {'renamed-product': 'renamed-product',
                                 **helper.declared_build_owners('/worker/renamed-product', layout, '/worker')})
            with self.assertRaisesRegex(ValueError, 'Invalid composite'):
                helper.declared_build_owners('/worker/product', {'schema': 2, 'builds': {'unsafe': '/outside'}}, '/worker')
        for flag in ('--dry-run', '-m', None):
            with self.subTest(flag=flag), TemporaryDirectory() as temporary:
                helper.STATE = Path(temporary)
                (helper.STATE / 'logs').mkdir()
                tasks = [':appAndroid:installRelease']
                receipt = {'project': 'fixture', 'run_id': 'run', 'tasks': tasks,
                           'remote_tasks': tasks + ([flag] if flag else []),
                           'log': str(helper.STATE / 'gradle.log')}
                responses = [SimpleNamespace(stdout=json.dumps(value)) for value in (
                    {'text': '> Task :appAndroid:installRelease SKIPPED\n> Task :release UP-TO-DATE\n> Task :reaktor:publish FROM-CACHE\n> Task :other NO-SOURCE\n', 'offset': 44, 'code': 0},
                    {'text': '', 'offset': 44, 'code': 0})]
                with patch.object(helper, 'remote_python', side_effect=responses), \
                     patch.object(helper, 'composite_artifacts', return_value=({'reaktor': 'reaktor', 'fixture': 'fixture'}, ['fixture', 'reaktor'], {}, {})) as graph, \
                     patch.object(helper, 'pull', return_value=['artifact.apk']) as pull, \
                     patch.object(helper, 'publish_artifacts'), \
                     patch.object(helper, 'pull_xcode_resources', return_value=[]) as xcode:
                    self.assertEqual(0, helper.finish_build(receipt))
                    self.assertEqual(flag is not None, receipt['dry_run'])
                    expected = 0 if flag else 1
                    self.assertEqual(expected, graph.call_count)
                    self.assertEqual(expected * 2, pull.call_count)
                    if expected:
                        self.assertEqual([':release', ':reaktor:publish'], pull.call_args_list[0].kwargs['observed_tasks'])
                        self.assertEqual([':publish'], pull.call_args_list[1].kwargs['observed_tasks'])
                    self.assertEqual(expected, xcode.call_count)
                    saved = json.loads((helper.STATE / 'logs/run.json').read_text())
                    self.assertEqual(expected * 2, saved['copied_files'])

    def test_declared_kotlin_exports_retain_dependency_context_without_copying_installed_packages(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve() / 'local'
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'manna'
            export = Path('modules/app/build/dependeasy/kotlin/library')
            manifests = ('build/js/package.json', 'build/js/packages/Manna-app/package.json',
                         'build/js/packages_imported/graph/1.0/package.json')
            for relative in (*manifests, str(export / 'package.json')):
                file = base / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('{"name":"fixture","dependencies":{"sample":"1.0"}}')
            catalog = base / 'build/dependeasy/kotlin-exports.json'
            catalog.parent.mkdir(parents=True)
            catalog.write_text(json.dumps({'schemaVersion': 1, 'directories': ['modules/undeclared/library']}))
            hosts = base / 'build/dependeasy/remote/hosts.json'
            hosts.parent.mkdir()
            hosts.write_text(json.dumps({'schema': 2, 'kotlinExports': [str(export)]}))
            installed = base / 'build/js/packages/Manna-app/node_modules'
            installed.mkdir()
            (installed / 'do-not-copy.txt').write_text('installed dependency')
            link = base / export.parent / 'node_modules'
            link.symlink_to(os.path.relpath(installed, link.parent))
            receipt = base / export.parent / 'dependencies.properties'
            receipt.write_text('dependency=fixture')
            decoys = ('modules/stale-kt/old.js', 'modules/stale/ts/export/old.js', 'modules/undeclared/library/package.json')
            for relative in decoys:
                file = base / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('undeclared output')

            def remote(source, *args, **kwargs):
                return subprocess.run([sys.executable, '-c', source, *args], **kwargs, check=True)

            def copy(argv, **kwargs):
                if argv[0] == '/bin/cp':
                    shutil.copytree(Path(argv[-2]), Path(argv[-1]), symlinks=True, dirs_exist_ok=True)
                elif argv[0] == 'rsync':
                    command = [arg for arg in argv if not arg.startswith('--rsync-path=')]
                    command[-2] = str(base) + '/'
                    subprocess.run(command, check=True)

            with patch.object(helper, 'remote_python', side_effect=remote), patch.object(helper, 'run', side_effect=copy):
                files = helper.pull('manna', 'context-fixture', family='web')
            local = helper.ROOT / 'manna'
            self.assertTrue((local / export.parent / 'node_modules').is_symlink())
            self.assertEqual(os.readlink(link), os.readlink(local / export.parent / 'node_modules'))
            self.assertEqual(receipt.read_text(), (local / export.parent / receipt.name).read_text())
            for relative in manifests:
                self.assertEqual((base / relative).read_text(), (local / relative).read_text())
            self.assertFalse(any('do-not-copy' in item for item in files))
            self.assertFalse((local / 'build/js/packages/Manna-app/node_modules').exists())
            for relative in decoys:
                self.assertNotIn(relative, files)
                self.assertFalse((local / relative).exists())

    def test_generated_web_contracts_and_package_scripts_return_without_a_merge(self):
        helper = router()
        with TemporaryDirectory() as temporary:
            helper.ROOT = Path(temporary).resolve() / 'local'
            helper.STATE = helper.ROOT / '.remote-dev'
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'bestbuds'
            artifacts = {
                'package.json': {'dependeasy': {'generatedScripts': ['deployWorker'], 'generatedPackages': ['targets/worker/package.json']}},
                'targets/worker/package.json': {'scripts': {'deploy': '../../gradlew :deployWorker'}},
                'build/dependeasy/web/site.json': {'schema': 1, 'component': 'site', 'directory': '../../../targets/worker'},
                'build/dependeasy/web/site.tsconfig.json': {'extends': '../../../base.json'},
                'build/dependeasy/cloudflare/fleet.json': {'schema': 1, 'workers': [{'configurations': {'prod': 'targets/worker/wrangler.dependeasy-prod.json'}}]},
                'targets/worker/wrangler.dependeasy-prod.json': {'name': 'fixture-worker'},
                'build/dependeasy/generated-package-scripts.json': {'obsolete': True},
                'build/dependeasy/apple/host.json': {'schema': 1, 'targets': [{'name': 'fixtureApp'}]},
                'build/dependeasy/release/fixture.json': {'schema': 1, 'product': 'fixture'},
                'build/dependeasy/dagger-reference/pipeline.json': {'schema': 1, 'build': 'fixture', 'targets': []},
                'dagger/build/dependeasy/dagger-reference/pipeline.json': {'schema': 1, 'build': 'fixture', 'targets': []},
                'dagger/build/dependeasy/dagger-reference/other.json': {'unrelated': True},
                'config/apple-host/Bootstrap.swift': 'fixture',
                'config/apple-host/fixtureApp-Debug.xcconfig': 'fixture',
                'config/apple-host/fixtureApp-Release.xcconfig': 'fixture',
                'build/dependeasy/apple/signing-request.json': {'private': True},
                'build/dependeasy/apple/Signing.local.xcconfig': 'private',
            }
            decoys = {'build/dependeasy/generated-package-scripts.json', 'build/dependeasy/apple/signing-request.json',
                      'build/dependeasy/apple/Signing.local.xcconfig', 'dagger/build/dependeasy/dagger-reference/other.json'}
            declared = sorted(set(artifacts) - decoys)
            hosts = base / 'build/dependeasy/remote/hosts.json'
            hosts.parent.mkdir(parents=True)
            policy = {'schema': 2, 'publicOutputs': {':publish': declared}, 'kotlinExports': [], 'outputRoots': {'web': [], 'metadata': []}}
            hosts.write_text(json.dumps(policy))
            for location, value in artifacts.items():
                file = base / location
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(json.dumps(value))
            def remote(source, *args, **kwargs):
                return subprocess.run([sys.executable, '-c', source, *args], **kwargs, check=True)
            def copy(argv, **kwargs):
                if argv[0] == '/bin/cp':
                    shutil.copytree(Path(argv[-2]), Path(argv[-1]), dirs_exist_ok=True)
                    return
                if argv[0] != 'rsync': return
                manifest = next(arg.split('=', 1)[1] for arg in argv if arg.startswith('--files-from='))
                destination = Path(argv[-1])
                for relative in Path(manifest).read_bytes().split(b'\0'):
                    if not relative: continue
                    target = destination / os.fsdecode(relative)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(base / os.fsdecode(relative), target)
            with patch.object(helper, 'remote_python', side_effect=remote), patch.object(helper, 'run', side_effect=copy):
                files = helper.pull('bestbuds', 'web-fixture', family='web', metadata=True)
            self.assertEqual(set(declared) | {'build/dependeasy/remote/hosts.json'}, set(files))
            self.assertEqual(artifacts['targets/worker/package.json'], json.loads((helper.ROOT / 'bestbuds/targets/worker/package.json').read_text()))
            # Transport reads only hosts.json; the owning Dagger consumer validates its content.
            for location in ('../escape.json', '/escape.json'):
                policy['publicOutputs'][':publish'] = [location]
                hosts.write_text(json.dumps(policy))
                with patch.object(helper, 'remote_python', side_effect=remote), self.assertRaises(subprocess.CalledProcessError) as failure:
                    helper.pull('bestbuds', 'invalid-reference', family='metadata')
                self.assertIn('Public output location escapes the workspace', failure.exception.stderr)
            policy['publicOutputs'][':publish'] = declared
            outside = Path(temporary) / 'private.json'
            outside.write_text('{}')
            escape = base / 'escaping-output.json'
            escape.symlink_to(outside)
            policy['publicOutputs'][':publish'] = ['escaping-output.json']
            hosts.write_text(json.dumps(policy))
            with patch.object(helper, 'remote_python', side_effect=remote), self.assertRaises(subprocess.CalledProcessError):
                helper.pull('bestbuds', 'symlink-escape', family='metadata')
            policy['publicOutputs'][':publish'] = declared
            policy['schema'] = 99
            hosts.write_text(json.dumps(policy))
            with patch.object(helper, 'remote_python', side_effect=remote), self.assertRaises(subprocess.CalledProcessError) as failure:
                helper.pull('bestbuds', 'invalid-hosts', family='metadata')
            self.assertIn('Unsupported remote hosts contract', failure.exception.stderr)
            for read in (helper.read_hosts_contract, helper.distribution_markers):
                with self.assertRaisesRegex(ValueError, 'Unsupported remote hosts contract'):
                    read(base)
            for value in ('[]', 'x' * (8 * 1024 * 1024 + 1)):
                hosts.write_text(value)
                with self.assertRaises(ValueError):
                    helper.read_hosts_contract(base)
                with patch.object(helper, 'remote_python', side_effect=remote), self.assertRaises(subprocess.CalledProcessError):
                    helper.pull('bestbuds', 'invalid-hosts', family='metadata')
            hosts.unlink()
            hosts.symlink_to(outside)
            with self.assertRaisesRegex(ValueError, 'Invalid remote hosts contract'):
                helper.read_hosts_contract(base)
            with patch.object(helper, 'remote_python', side_effect=remote), self.assertRaises(subprocess.CalledProcessError):
                helper.pull('bestbuds', 'invalid-hosts', family='metadata')


def local_worker(source, *args, **kwargs):
    return subprocess.run([sys.executable, '-c', source, *args], **kwargs, check=True)


def rsync_from(base):
    def copy(argv, **kwargs):
        if argv[0] != 'rsync': return
        manifest = next(arg.split('=', 1)[1] for arg in argv if arg.startswith('--files-from='))
        for relative in Path(manifest).read_bytes().split(b'\0'):
            if not relative: continue
            source, target = base / os.fsdecode(relative), Path(argv[-1]) / os.fsdecode(relative)
            if source.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
    return copy


class AgentWorktreeTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.helper = helper = router()
        helper.ROOT = self.base / 'local'
        helper.STATE = helper.ROOT / '.remote-dev'
        (helper.STATE / 'worktrees').mkdir(parents=True)
        helper.CONFIG['projects'] = helper.PROJECTS = ['bestbuds', 'reaktor']
        (helper.STATE / 'config.json').write_text(json.dumps(helper.CONFIG))
        self.pair = self.base / 'agents/pair'
        self.identity = 'a' * 64
        roots = []
        for name in helper.PROJECTS:
            source, checkout = helper.ROOT / name, self.pair / name
            source.mkdir()
            subprocess.run(['git', 'init', '-q', str(source)], check=True)
            (source / 'source.txt').write_text(name)
            subprocess.run(['git', '-C', str(source), 'add', '.'], check=True)
            subprocess.run(['git', '-C', str(source), '-c', 'user.name=Test', '-c', 'user.email=test@localhost',
                            'commit', '-qm', 'base'], check=True)
            subprocess.run(['git', '-C', str(source), 'worktree', 'add', '-q', '--detach', str(checkout)], check=True)
            roots.append({'source': str(source), 'path': str(checkout), 'baseline': 'fixture'})
        self.registration = helper.STATE / 'worktrees' / (self.identity + '.json')
        self.registration.write_text(json.dumps({'roots': roots}))

    def test_registered_pair_routes_and_publishes_without_touching_primary_outputs(self):
        helper = self.helper
        primary = helper.ROOT
        self.assertEqual(('bestbuds', Path('modules/kernel')), helper.project_at(self.pair / 'bestbuds/modules/kernel'))
        self.assertEqual(self.pair, helper.ROOT)
        self.assertEqual(['bestbuds', 'reaktor'], helper.PROJECTS)
        own_remote = helper.slot_remote(0)
        self.assertNotEqual(helper.BASE_REMOTE, own_remote)
        self.assertIn('/agent-worktrees/' + self.identity + '/', own_remote)
        with patch.object(helper, 'prepare_slot') as prepare, helper.worker_slot(0):
            self.assertEqual(own_remote + '/.remote-dev', helper.LOCK_ROOT)
            prepare.assert_called_once_with(own_remote)
        main_output = primary / 'bestbuds/build/result.jar'
        main_output.parent.mkdir()
        main_output.write_text('main')
        helper.WORKTREE = None
        helper.record_publications('bestbuds', 'main-newer', ['build/result.jar'], time.time() + 100, set())
        helper.WORKTREE = self.identity
        staged = helper.STATE / 'artifacts/run/bestbuds/build/result.jar'
        staged.parent.mkdir(parents=True)
        staged.write_text('worktree')
        self.assertEqual([], helper.publish_artifacts('bestbuds', 'run', ['build/result.jar'], True, time.time()))
        self.assertEqual('worktree', (self.pair / 'bestbuds/build/result.jar').read_text())
        self.assertEqual('main', main_output.read_text())

    def test_unregistered_and_foreign_repositories_are_refused(self):
        helper = self.helper
        with self.assertRaises(SystemExit):
            helper.project_at(self.base / 'unknown/bestbuds')
        value = json.loads(self.registration.read_text())
        value['roots'][0]['source'] = value['roots'][1]['source']
        self.registration.write_text(json.dumps(value))
        with self.assertRaises(SystemExit):
            helper.project_at(self.pair / 'bestbuds')
        self.assertIsNone(helper.WORKTREE)

    def test_source_sync_targets_only_the_registered_pair_mirror(self):
        helper = self.helper
        primary = helper.ROOT
        helper.project_at(self.pair / 'bestbuds')
        (self.pair / 'bestbuds/source.txt').write_text('agent snapshot')
        commands = []
        def execute(argv, **kwargs):
            commands.append(list(map(str, argv)))
        with helper.worker_slot(1, prepare=False), patch.object(helper, 'run', side_effect=execute), \
             patch.object(helper, 'ssh', return_value=SimpleNamespace(stdout='')) as ssh, \
             patch.object(helper, 'remote_python') as remote:
            helper.sync(helper.PROJECTS)
            target = helper.REMOTE
        copies = [args for args in commands if args[0] == 'rsync']
        self.assertEqual({str(self.pair / name) + '/' for name in helper.PROJECTS}, {args[-2] for args in copies})
        self.assertTrue(all(args[-1].startswith('fixture:' + target + '/') for args in copies))
        self.assertTrue(all(call.args[1] == target for call in remote.call_args_list))
        self.assertEqual('bestbuds', (primary / 'bestbuds/source.txt').read_text())

    def test_resume_reselects_registered_workspace_and_its_slot(self):
        import workspace_storage
        helper = self.helper
        (helper.STATE / 'logs').mkdir()
        remote = helper.BASE_REMOTE + '/.remote-dev/agent-worktrees/' + self.identity + '/slots/job-1'
        receipt = {'run_id': 'run', 'project': 'bestbuds', 'workspace_root': str(self.pair), 'remote_root': remote, 'worker_slot': 1}
        (helper.STATE / 'logs/run.json').write_text(json.dumps(receipt))
        def finish(retained, lock):
            self.assertEqual(self.pair, helper.ROOT)
            self.assertEqual(remote, helper.REMOTE)
            return 0
        with patch.object(sys, 'argv', ['remote-dev', 'resume', 'run']), \
             patch.object(workspace_storage, 'automatic'), patch.object(helper, 'finish_build', side_effect=finish):
            self.assertEqual(0, helper._main())


class WorkerSlotTest(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ)
        environment.start()
        self.addCleanup(environment.stop)
        os.environ.pop('REMOTE_DEV_WORKER_SLOTS', None)
        self.addCleanup(signal.signal, signal.SIGTERM, signal.getsignal(signal.SIGTERM))

    def helper(self, temporary, slots=2):
        helper = router()
        helper.ROOT = Path(temporary).resolve() / 'local'
        helper.STATE = helper.ROOT / '.remote-dev'
        (helper.STATE / 'logs').mkdir(parents=True)
        helper.CONFIG['worker_slots'] = slots
        return helper

    def test_a_second_job_runs_in_a_free_worker_slot_while_the_first_holds_the_worker(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            claimed = []
            def build_in_slot(name, relative, args, index, lock):
                with (helper.STATE / 'worker-slot-1.lock').open('a') as other:
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
                claimed.append(index)
                return 0
            with (helper.STATE / 'worker-gradle.lock').open('a') as first:
                fcntl.flock(first, fcntl.LOCK_EX)
                with patch.object(helper, 'worker_admission', return_value=(True, 'fixture capacity')) as admission, \
                     patch.object(helper, 'slot_build', side_effect=build_in_slot), \
                     patch.object(helper.time, 'sleep') as pause:
                    self.assertEqual(0, helper.queued_worker_gradle('bestbuds', '.', [':engine:jvmTest']))
            self.assertEqual([1], claimed)
            admission.assert_called_once_with(1)
            pause.assert_not_called()
            with (helper.STATE / 'worker-slot-1.lock').open('a') as released:
                fcntl.flock(released, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with patch.object(helper, 'worker_admission', side_effect=[(False, 'unfinished job'), (True, 'fixture capacity')]) as admission, \
                 patch.object(helper, 'slot_build', side_effect=build_in_slot):
                self.assertEqual(0, helper.queued_worker_gradle('bestbuds', '.', [':engine:jvmTest']))
            self.assertEqual([1, 1], claimed)
            self.assertEqual([0, 1], [call.args[0] for call in admission.call_args_list])

    def test_one_slot_keeps_the_single_worker_queue(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary, slots=1)
            answers = iter([None, None, 3])
            output = io.StringIO()
            with (helper.STATE / 'worker-gradle.lock').open('a') as first:
                fcntl.flock(first, fcntl.LOCK_EX)
                with patch.object(helper, 'worker_admission') as admission, \
                     patch.object(helper, 'slot_build') as slot, \
                     patch.object(helper.time, 'sleep') as pause, contextlib.redirect_stdout(output):
                    self.assertEqual(3, helper.queued_worker_gradle('bestbuds', '.', ['help'], lambda: next(answers)))
            admission.assert_not_called()
            slot.assert_not_called()
            self.assertEqual(2, pause.call_count)
            self.assertEqual(1, output.getvalue().count('Both build hosts are occupied'))

    def test_an_unreachable_worker_ends_the_wait_with_its_reason(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            sandboxed = (False, 'worker capacity unavailable (ssh: connect to host m1-worker port 22: Operation not permitted)')
            with patch.object(helper, 'worker_admission', return_value=sandboxed), \
                 patch.object(helper, 'slot_build') as slot, \
                 patch.object(helper.time, 'sleep') as pause, contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as stopped:
                    helper.queued_worker_gradle('bestbuds', '.', [':engine:jvmTest'])
            self.assertIn('sandbox', str(stopped.exception.code))
            slot.assert_not_called()
            pause.assert_not_called()
            clock = iter([0.0, 0.0, 300.0, 700.0])
            with patch.object(helper, 'worker_admission', return_value=(False, 'worker capacity unavailable (Connection timed out)')), \
                 patch.object(helper.time, 'monotonic', side_effect=lambda: next(clock)), \
                 patch.object(helper.time, 'sleep') as pause, contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as stopped:
                    helper.queued_worker_gradle('bestbuds', '.', [':engine:jvmTest'])
            self.assertIn('unreachable for 10 minutes', str(stopped.exception.code))
            self.assertEqual(2, pause.call_count)
            with patch.object(helper, 'worker_admission', side_effect=[(False, 'worker capacity unavailable (Connection timed out)'),
                                                                       (False, 'worker capacity unavailable (Connection timed out)'),
                                                                       (True, 'fixture capacity'), (True, 'fixture capacity')]), \
                 patch.object(helper, 'slot_build', return_value=0), \
                 patch.object(helper.time, 'sleep'), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, helper.queued_worker_gradle('bestbuds', '.', [':engine:jvmTest']))

    def test_worker_admission_reports_why_the_worker_cannot_be_reached(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            refused = subprocess.CalledProcessError(255, 'ssh', stderr='banner\nssh: connect to host m1-worker port 22: Operation not permitted\n')
            with patch.object(helper, 'remote_python', side_effect=refused):
                admitted, reason = helper.worker_admission(1)
            self.assertFalse(admitted)
            self.assertEqual('worker capacity unavailable (ssh: connect to host m1-worker port 22: Operation not permitted)', reason)

    def test_worker_admission_needs_free_memory_disk_and_an_idle_slot(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            gib = 1024 ** 3
            for index, observed, admitted, reason in (
                    (1, f'35 {100 * gib} 0', False, 'memory free'),
                    (1, f'80 {10 * gib} 0', False, 'disk free'),
                    (1, f'80 {100 * gib} 1', False, 'unfinished job'),
                    (1, f'80 {100 * gib} 0', True, '80% worker memory free'),
                    (0, f'35 {10 * gib} 0', True, '35% worker memory free'),
                    (0, f'80 {100 * gib} 1', False, 'unfinished job')):
                with self.subTest(observed=observed), \
                     patch.object(helper, 'remote_python', return_value=SimpleNamespace(stdout=observed)) as remote:
                    result, detail = helper.worker_admission(index)
                    self.assertEqual(admitted, result)
                    self.assertIn(reason, detail)
                    self.assertEqual(helper.slot_remote(index) + '/.remote-dev/gradle.lock',
                                     remote.call_args.args[2])
            with patch.object(helper, 'remote_python', side_effect=subprocess.CalledProcessError(255, 'ssh')):
                self.assertFalse(helper.worker_admission(1)[0])
            with (helper.STATE / 'worker-gradle.lock').open('a') as first:
                fcntl.flock(first, fcntl.LOCK_EX)
                with patch.object(helper, 'worker_admission', return_value=(False, 'fixture memory')):
                    claimed, reason = helper.claim_worker_slot()
                self.assertIsNone(claimed)
                self.assertIn('fixture memory', reason)
                with (helper.STATE / 'worker-slot-1.lock').open('a') as released:
                    fcntl.flock(released, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_slot_job_runs_its_own_wrapper_lock_and_job_folder(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            (helper.ROOT / 'reaktor').mkdir()
            (helper.ROOT / 'reaktor/gradlew').touch()
            helper.CONFIG['java_home'] = '/fixture/java'
            slot = helper.BASE_REMOTE + '/.remote-dev/slots/job-1'
            with patch.dict(os.environ, {}, clear=True), \
                 patch.object(helper, 'cache_environment', return_value={}), \
                 patch.object(helper, 'remote_python') as remote, \
                 patch.object(helper, 'finish_build', return_value=0) as finish:
                with helper.worker_slot(1, 'changed-files', prepare=False):
                    self.assertEqual(0, helper.build('reaktor', '.', ['help'], synchronize=False))
            payload = json.loads(remote.call_args.kwargs['input'])
            command = remote.call_args.args[3]
            receipt = finish.call_args.args[0]
            self.assertEqual(slot + '/.remote-dev', payload['REMOTE_DEV_JOB_ROOT'])
            self.assertEqual(slot + '/.remote-dev', payload['REMOTE_DEV_LOCK_ROOT'])
            self.assertEqual(slot, remote.call_args.args[1])
            self.assertIn(slot + '/.remote-dev/run-gradle', command)
            self.assertIn('--max-workers=6', command)
            self.assertEqual((1, 'changed-files', slot), (receipt['worker_slot'], receipt['publication'], receipt['remote_root']))
            self.assertIsInstance(receipt['source_epoch'], float)
            self.assertEqual((helper.BASE_REMOTE, None, None, None),
                             (helper.REMOTE, helper.LOCK_ROOT, helper.SLOT, helper.PUBLICATION))

    def test_slot_sync_waits_only_for_its_own_unfinished_job(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            with patch.object(helper, 'ssh') as ssh:
                with helper.worker_slot(2, prepare=False):
                    helper.sync([])
                helper.sync([])
            self.assertIn(helper.BASE_REMOTE + '/.remote-dev/slots/job-2/.remote-dev/gradle.lock', ssh.call_args_list[0].args[0])
            self.assertIn(helper.BASE_REMOTE + '/.remote-dev/gradle.lock ', ssh.call_args_list[1].args[0])

    def test_changed_files_pull_takes_this_jobs_outputs_whole_distributions_and_missing_files(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'bestbuds'
            def write(relative):
                file = base / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('worker')
            for relative in ('build/libs/kept.jar', 'build/libs/missing.jar', 'build/agent-dist/lib/stale.jar',
                             'cloud/build/observability-dist/lib/stale.jar',
                             'build/compose/binaries/main/app/Hangar.app/Contents/Info.plist'):
                write(relative)
            hosts = base / 'build/dependeasy/remote/hosts.json'
            hosts.parent.mkdir(parents=True)
            hosts.write_text(json.dumps({'schema': 2, 'distributionDirectories': ['cloud/build/observability-dist']}))
            local = helper.ROOT / 'bestbuds'
            (local / 'build/libs').mkdir(parents=True)
            (local / 'build/libs/kept.jar').write_text('local')
            (local / 'build/compose/binaries/main/app').mkdir(parents=True)
            time.sleep(0.05)
            since = time.time()
            time.sleep(0.05)
            write('build/libs/fresh.jar')
            write('build/agent-dist/lib/fresh.jar')
            write('cloud/build/observability-dist/lib/fresh.jar')
            unverified = {}
            with patch.object(helper, 'remote_python', side_effect=local_worker), \
                 patch.object(helper, 'run', side_effect=rsync_from(base)):
                files = helper.pull('bestbuds', 'slot-run', family='all', agent_distribution=True, publish=False,
                                    since=since, unverified=unverified)
            self.assertEqual({'build/libs/fresh.jar', 'build/libs/missing.jar', 'build/agent-dist/lib/',
                              'build/agent-dist/lib/fresh.jar', 'build/agent-dist/lib/stale.jar',
                              'cloud/build/observability-dist/', 'cloud/build/observability-dist/lib/',
                              'cloud/build/observability-dist/lib/fresh.jar', 'cloud/build/observability-dist/lib/stale.jar',
                              'build/dependeasy/remote/hosts.json'}, set(files))
            self.assertEqual({'bestbuds': ['build/dependeasy/remote/hosts.json', 'build/libs/missing.jar']}, unverified)
            release = 'build/dependeasy/release/fixture.json'
            write(release)
            (local / release).parent.mkdir(parents=True)
            (local / release).write_text('old checkout')
            hosts = base / 'build/dependeasy/remote/hosts.json'
            hosts.parent.mkdir(exist_ok=True)
            hosts.write_text(json.dumps({'schema': 2, 'publicOutputs': {':apple:release': [release]},
                                         'outputRoots': {'metadata': []}, 'kotlinExports': []}))
            time.sleep(0.05)
            since = time.time()
            time.sleep(0.05)
            for observed, successful, expected in (([], True, False), ([':apple:release'], False, False), ([':apple:release'], True, True)):
                with patch.object(helper, 'remote_python', side_effect=local_worker), patch.object(helper, 'run', side_effect=rsync_from(base)):
                    review = {}
                    files = helper.pull('bestbuds', 'public-output', successful=successful, family='metadata',
                                        publish=False, since=since, unverified=review, observed_tasks=observed)
                self.assertEqual(expected, release in files)
                self.assertNotIn(release, review.get('bestbuds', []))
            helper.publish_artifacts('bestbuds', 'public-output', files, True, since)
            self.assertEqual('worker', (local / release).read_text())

    def test_older_sources_never_replace_a_newer_runs_outputs_and_distributions_move_whole(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            def stage(run_id, files):
                contract = helper.STATE / 'artifacts' / run_id / 'bestbuds/build/dependeasy/remote/hosts.json'
                contract.parent.mkdir(parents=True)
                contract.write_text(json.dumps({'schema': 2, 'distributionDirectories': ['cloud/build/custom-runtime']}))
                for relative, text in files.items():
                    file = helper.STATE / 'artifacts' / run_id / 'bestbuds' / relative
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text(text)
                return [*files, 'cloud/build/custom-runtime/']
            newer = stage('newer', {'build/libs/shared.jar': 'newer', 'cloud/build/custom-runtime/lib/a.jar': 'newer'})
            older = stage('older', {'build/libs/shared.jar': 'older', 'build/libs/only-older.jar': 'older',
                                    'cloud/build/custom-runtime/lib/a.jar': 'older', 'cloud/build/custom-runtime/lib/b.jar': 'older'})
            self.assertEqual([], helper.publish_artifacts('bestbuds', 'newer', newer, True, 200.0))
            withheld = helper.publish_artifacts('bestbuds', 'older', older, True, 100.0)
            self.assertEqual({'build/libs/shared.jar', 'cloud/build/custom-runtime/lib/a.jar', 'cloud/build/custom-runtime/lib/b.jar',
                              'cloud/build/custom-runtime/'}, set(withheld))
            local = helper.ROOT / 'bestbuds'
            self.assertEqual('newer', (local / 'build/libs/shared.jar').read_text())
            self.assertEqual('older', (local / 'build/libs/only-older.jar').read_text())
            self.assertEqual(['a.jar'], sorted(path.name for path in (local / 'cloud/build/custom-runtime/lib').iterdir()))
            self.assertEqual('newer', (local / 'cloud/build/custom-runtime/lib/a.jar').read_text())
            replacement = stage('replacement', {'cloud/build/custom-runtime/lib/b.jar': 'replacement'})
            self.assertEqual([], helper.publish_artifacts('bestbuds', 'replacement', replacement, True))
            self.assertEqual(['b.jar'], sorted(path.name for path in (local / 'cloud/build/custom-runtime/lib').iterdir()))

    def test_web_bundles_publish_whole_and_up_to_date_producers_repair_stale_local_chunks(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'bestbuds'
            bundle = 'targets/site/dist'
            contract = base / 'build/dependeasy/remote/hosts.json'
            contract.parent.mkdir(parents=True)
            policy = {'schema': 2, 'bundleDirectories': [bundle],
                      'publicOutputs': {':site:bundle': [bundle]}}
            contract.write_text(json.dumps(policy))
            for relative, content in {'index.html': 'current', 'assets/index-current.js': 'current',
                                      'assets/vendor.js': 'unchanged dependency'}.items():
                file = base / bundle / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(content)
            local = helper.ROOT / 'bestbuds' / bundle
            (local / 'assets').mkdir(parents=True)
            (local / 'assets/index-old.js').write_text('obsolete')
            time.sleep(0.05)
            since = time.time()
            time.sleep(0.05)
            # Every producer file predates this run: the successful UP-TO-DATE task
            # still proves ownership of the complete bundle, including empty dirs.
            with patch.object(helper, 'remote_python', side_effect=local_worker), \
                 patch.object(helper, 'run', side_effect=rsync_from(base)):
                files = helper.pull('bestbuds', 'bundle', family='web', publish=False,
                                    since=since, observed_tasks=[':site:bundle'])
            self.assertIn(bundle + '/', files)
            self.assertIn(bundle + '/assets/vendor.js', files)
            self.assertEqual([], helper.publish_artifacts('bestbuds', 'bundle', files, True, since))
            self.assertFalse((local / 'assets/index-old.js').exists())
            self.assertEqual('current', (local / 'index.html').read_text())
            self.assertEqual(['index-current.js', 'vendor.js'], sorted(p.name for p in (local / 'assets').iterdir()))
            older = helper.STATE / 'artifacts/older/bestbuds'
            shutil.copytree(base, older)
            (older / bundle / 'assets/index-current.js').write_text('old run')
            held = helper.publish_artifacts('bestbuds', 'older', files, True, since - 1)
            self.assertIn(bundle + '/', held)
            self.assertEqual('current', (local / 'assets/index-current.js').read_text())
            time.sleep(0.05)
            next_sync = time.time()
            time.sleep(0.05)
            (local / 'assets/index-new-local.js').write_text('new local build')
            held = helper.publish_artifacts('bestbuds', 'older', files, True, next_sync)
            self.assertIn(bundle + '/', held)
            self.assertEqual('new local build', (local / 'assets/index-new-local.js').read_text())
            for invalid in ('', '.', '../source', '/source'):
                contract.write_text(json.dumps(dict(policy, bundleDirectories=[invalid])))
                with self.assertRaises(ValueError):
                    helper.distribution_markers(base)
                with patch.object(helper, 'remote_python', side_effect=local_worker), self.assertRaises(subprocess.CalledProcessError):
                    helper.pull('bestbuds', 'invalid-bundle', family='web', publish=False)
            (base / 'linked').symlink_to(base / 'targets', target_is_directory=True)
            contract.write_text(json.dumps(dict(policy, bundleDirectories=['linked/site/dist'])))
            with self.assertRaisesRegex(ValueError, 'traverse a link'):
                helper.distribution_markers(base)

    def test_unverified_copy_never_replaces_a_file_the_checkout_gained(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            snapshot = helper.STATE / 'artifacts/run/bestbuds/build/libs'
            snapshot.mkdir(parents=True)
            (snapshot / 'old.jar').write_text('worker')
            (snapshot / 'gone.jar').write_text('worker')
            local = helper.ROOT / 'bestbuds/build/libs'
            local.mkdir(parents=True)
            (local / 'old.jar').write_text('local')
            files = ['build/libs/old.jar', 'build/libs/gone.jar']
            self.assertEqual(['build/libs/old.jar'], helper.publish_artifacts('bestbuds', 'run', files, True, 300.0, files))
            self.assertEqual('local', (local / 'old.jar').read_text())
            self.assertEqual('worker', (local / 'gone.jar').read_text())
            fresh = helper.STATE / 'artifacts/fresh/bestbuds/build/libs'
            fresh.mkdir(parents=True)
            (fresh / 'gone.jar').write_text('fresh')
            self.assertEqual([], helper.publish_artifacts('bestbuds', 'fresh', ['build/libs/gone.jar'], True, 1.0))
            self.assertEqual('fresh', (local / 'gone.jar').read_text())

    def test_copy_back_never_restores_classes_this_checkout_no_longer_has(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.REMOTE) / 'bestbuds'
            removed = 'build/classes/kotlin/jvm/test/dev/shibasis/GraphCanvasMotionTest.class'
            for relative in (removed, 'build/libs/missing.jar'):
                file = base / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('17 Oct')
            (helper.ROOT / 'bestbuds').mkdir(parents=True)
            time.sleep(0.05)
            since = time.time()
            unverified = {}
            with patch.object(helper, 'remote_python', side_effect=local_worker), \
                 patch.object(helper, 'run', side_effect=rsync_from(base)):
                files = helper.pull('bestbuds', 'run', family='all', publish=False, since=since, unverified=unverified)
            self.assertNotIn(removed, files)
            self.assertEqual({'bestbuds': ['build/libs/missing.jar']}, unverified)

    def test_a_worker_run_never_replaces_what_this_mac_wrote_after_its_sources_were_synced(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            library = 'modules/app/build/dependeasy/kotlin/library/Manna-app.mjs'
            local = helper.ROOT / 'manna' / library
            def publish(run_id, text, synced):
                file = helper.STATE / 'artifacts' / run_id / 'manna' / library
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(text)
                return helper.publish_artifacts('manna', run_id, [library], True, synced)
            def moment():
                time.sleep(0.05)
                at = time.time()
                time.sleep(0.05)
                return at
            self.assertEqual([], publish('earlier', 'earlier worker', moment()))
            deploy = moment()
            local.write_text('local build')
            self.assertEqual([library], publish('deploy', 'older worker', deploy))
            self.assertEqual('local build', local.read_text())
            self.assertEqual([], publish('later', 'later worker', moment()))
            self.assertEqual('later worker', local.read_text())
            slow, quick = moment(), moment()
            self.assertEqual([], publish('slow', 'synced first', slow))
            self.assertEqual([], publish('quick', 'synced second', quick))
            self.assertEqual('synced second', local.read_text())
            self.assertEqual([library], publish('stale', 'synced before quick', slow))
            self.assertEqual('synced second', local.read_text())

    def test_slot_collection_uses_the_worker_job_start_and_trims_a_full_disk(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            receipt = {'project': 'bestbuds', 'run_id': 'run', 'tasks': ['help'], 'remote_tasks': ['help'],
                       'log': str(helper.STATE / 'gradle.log'), 'publication': 'changed-files', 'source_epoch': 50.0}
            responses = [SimpleNamespace(stdout=json.dumps({'text': text, 'offset': 1, 'code': 0, 'started': 123.5}))
                         for text in ('x', '')]
            def pull(name, run_id, *args, since=None, unverified=None, **kwargs):
                unverified[name] = ['build/libs/missing.jar']
                return ['build/libs/missing.jar']
            with helper.worker_slot(1, 'changed-files', prepare=False), \
                 patch.object(helper, 'remote_python', side_effect=responses), \
                 patch.object(helper, 'composite_artifacts', return_value=({}, ['bestbuds'], {}, {})), \
                 patch.object(helper, 'pull', side_effect=pull) as collect, \
                 patch.object(helper, 'pull_xcode_resources', return_value=[]), \
                 patch.object(helper, 'trim_worker_slot', return_value=4) as trim, \
                 patch.object(helper, 'publish_artifacts', return_value=[]) as publish:
                self.assertEqual(0, helper.finish_build(receipt))
            self.assertEqual(123.5, collect.call_args.kwargs['since'])
            self.assertEqual(123.5, collect.call_args.kwargs['report_since'])
            trim.assert_called_once_with(helper.BASE_REMOTE + '/.remote-dev/slots/job-1', helper.worker_disk_floor())
            publish.assert_called_once_with('bestbuds', 'run', ['build/libs/missing.jar'], True, 50.0, ['build/libs/missing.jar'])
            saved = json.loads((helper.STATE / 'logs/run.json').read_text())
            self.assertEqual((4, {'bestbuds': 1}), (saved['worker_slot_trimmed'], saved['unverified_copies']))

    def test_resume_returns_to_the_receipts_slot(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            slot = helper.BASE_REMOTE + '/.remote-dev/slots/job-1'
            receipt = {'run_id': 'run', 'project': 'bestbuds', 'remote_root': slot, 'worker_slot': 1,
                       'publication': 'changed-files', 'workspace_root': str(helper.ROOT)}
            (helper.STATE / 'logs/run.json').write_text(json.dumps(receipt))
            seen = []
            def finish(saved, lock):
                with (helper.STATE / 'worker-slot-1.lock').open('a') as other:
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
                seen.append((helper.REMOTE, helper.LOCK_ROOT, helper.SLOT, helper.PUBLICATION))
                return 0
            with patch.object(sys, 'argv', ['remote-dev', 'resume', 'run']), \
                 patch('workspace_storage.automatic'), \
                 patch.object(helper, 'finish_build', side_effect=finish):
                self.assertEqual(0, helper._main())
            self.assertEqual([(slot, slot + '/.remote-dev', 1, 'changed-files')], seen)
            receipt['remote_root'] = helper.BASE_REMOTE
            (helper.STATE / 'logs/run.json').write_text(json.dumps(receipt))
            with patch.object(sys, 'argv', ['remote-dev', 'resume', 'run']), patch('workspace_storage.automatic'), \
                 self.assertRaisesRegex(SystemExit, 'outside its slot'):
                helper._main()

    def test_resume_takes_over_only_a_run_whose_router_exited_with_its_outputs_staged(self):
        import workspace_storage
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            router_process = subprocess.Popen(['/bin/sleep', '30'])
            saved = helper.STATE / 'logs/run.json'
            receipt = {'run_id': 'run', 'project': 'bestbuds', 'remote_root': helper.BASE_REMOTE,
                       'workspace_root': str(helper.ROOT), 'exit_code': 0,
                       'owner': workspace_storage.run_owner(router_process.pid),
                       'pending_publications': [['bestbuds', ['app.apk']]]}
            saved.write_text(json.dumps(receipt))
            def resume():
                with patch.object(sys, 'argv', ['remote-dev', 'resume', 'run']), patch('workspace_storage.automatic'), \
                     patch.object(helper, 'finish_build', return_value=0) as finish:
                    try:
                        return helper._main(), finish.call_args
                    except ValueError as error:
                        return str(error), finish.call_args
            try:
                self.assertEqual(('Run run still belongs to process %d; wait for it instead of resuming' % router_process.pid, None), resume())
            finally:
                router_process.kill()
                router_process.wait()
            saved.write_text(json.dumps(dict(receipt, abandoned='2026-10-24T12:00:00')))
            self.assertEqual(('The staged outputs of run run expired after its router exited; build again', None), resume())
            (helper.STATE / 'artifacts/run/bestbuds').mkdir(parents=True)
            code, call = resume()
            self.assertEqual(0, code)
            claimed = call.args[0]
            self.assertEqual(os.getpid(), claimed['owner']['pid'])
            self.assertNotIn('abandoned', claimed)
            self.assertEqual(claimed, json.loads(saved.read_text()))

    def test_a_worker_receipt_names_the_router_that_owns_it(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            (helper.ROOT / 'reaktor').mkdir()
            (helper.ROOT / 'reaktor/gradlew').touch()
            helper.CONFIG['java_home'] = '/fixture/java'
            with patch.dict(os.environ, {}, clear=True), \
                 patch.object(helper, 'cache_environment', return_value={}), \
                 patch.object(helper, 'remote_python'), \
                 patch.object(helper, 'finish_build', return_value=0) as finish:
                self.assertEqual(0, helper.build('reaktor', '.', ['help'], synchronize=False))
            [saved] = (helper.STATE / 'logs').glob('*.json')
            owner = json.loads(saved.read_text())['owner']
            self.assertEqual(os.getpid(), owner['pid'])
            self.assertTrue(owner['started'])
            self.assertEqual(owner, finish.call_args.args[0]['owner'])

    def test_new_slot_is_seeded_from_worker_sources_without_outputs_or_stale_git_locks(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.BASE_REMOTE = str(Path(temporary).resolve() / 'worker')
            base = Path(helper.BASE_REMOTE)
            project = base / 'reaktor'
            (project / 'src').mkdir(parents=True)
            (project / 'src/Main.kt').write_text('fun main() {}')
            (project / 'build/classes').mkdir(parents=True)
            (project / 'build/classes/Main.class').write_text('output')
            subprocess.run(['git', 'init', '--quiet', str(project)], check=True)
            subprocess.run(['git', '-C', str(project), '-c', 'user.name=fixture', '-c', 'user.email=fixture@example.com',
                            'commit', '--quiet', '--allow-empty', '-m', 'fixture'], check=True)
            (project / '.git/index.lock').write_text('')
            broken = base / 'manna'
            (broken / 'src').mkdir(parents=True)
            subprocess.run(['git', 'init', '--quiet', str(broken)], check=True)
            branch = (broken / '.git/HEAD').read_text().split()[-1]
            (broken / '.git' / branch).write_text('1' * 40 + '\n')
            state = base / '.remote-dev'
            state.mkdir()
            (state / 'run-gradle').write_text('#!/bin/bash\n')
            (state / 'reaktor.source.json').write_text('["src/Main.kt"]')
            helper.PROJECTS = ['reaktor', 'manna']
            slot = base / '.remote-dev/slots/job-1'
            with patch.object(helper, 'remote_python', side_effect=local_worker):
                helper.prepare_slot(str(slot))
                (slot / 'reaktor/src/Main.kt').write_text('changed in slot')
                helper.prepare_slot(str(slot))
            head = lambda path: subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True)
            self.assertEqual(head(project), head(slot / 'reaktor'))
            self.assertEqual('changed in slot', (slot / 'reaktor/src/Main.kt').read_text())
            self.assertFalse((slot / 'reaktor/build').exists())
            self.assertFalse((slot / 'reaktor/.git/index.lock').exists())
            self.assertTrue((slot / 'manna/src').is_dir())
            self.assertFalse((slot / 'manna/.git').exists())
            self.assertEqual('#!/bin/bash\n', (slot / '.remote-dev/run-gradle').read_text())
            self.assertEqual('["src/Main.kt"]', (slot / '.remote-dev/reaktor.source.json').read_text())
            self.assertTrue((slot / '.remote-dev/jobs').is_dir())

    def test_slot_trim_keeps_sources_git_and_unfinished_jobs(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.BASE_REMOTE = str(Path(temporary).resolve() / 'worker')
            slot = Path(helper.BASE_REMOTE) / '.remote-dev/slots/job-1'
            for relative in ('reaktor/src/Main.kt', 'reaktor/build/classes/Main.class', 'reaktor/.gradle/cache.bin',
                             'reaktor/.git/build/keep', '.remote-dev/jobs/run/output.log'):
                (slot / relative).parent.mkdir(parents=True, exist_ok=True)
                (slot / relative).write_text('fixture')
            with patch.object(helper, 'remote_python', side_effect=local_worker):
                (slot / '.remote-dev/gradle.lock').mkdir()
                self.assertEqual(0, helper.trim_worker_slot(str(slot)))
                (slot / '.remote-dev/gradle.lock').rmdir()
                self.assertEqual(0, helper.trim_worker_slot(str(slot), floor=0))
                self.assertEqual(2, helper.trim_worker_slot(str(slot)))
                with self.assertRaises(ValueError):
                    helper.trim_worker_slot(helper.BASE_REMOTE)
            self.assertTrue((slot / 'reaktor/src/Main.kt').exists())
            self.assertTrue((slot / 'reaktor/.git/build/keep').exists())
            self.assertTrue((slot / '.remote-dev/jobs/run/output.log').exists())
            self.assertFalse((slot / 'reaktor/build').exists())
            self.assertFalse((slot / 'reaktor/.gradle').exists())

    def test_run_gradle_holds_the_slot_lock_and_reports_into_the_slot_job(self):
        script = Path(__file__).with_name('run-gradle')
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'jobs/run').mkdir(parents=True)
            environment = dict(os.environ, REMOTE_DEV_LOCK_ROOT=str(root), REMOTE_DEV_JOB_ROOT=str(root),
                               REMOTE_DEV_RUN_ID='run')
            probe = 'test -d "$REMOTE_DEV_LOCK_ROOT/gradle.lock" && exit 3'
            finished = subprocess.run(['/bin/bash', str(script), '/bin/sh', '-c', probe], cwd=root, env=environment)
            self.assertEqual(3, finished.returncode)
            self.assertEqual('3', (root / 'jobs/run/exitcode').read_text().strip())
            self.assertFalse((root / 'gradle.lock').exists())
            (root / 'gradle.lock').mkdir()
            refused = subprocess.run(['/bin/bash', str(script), '/usr/bin/true'], cwd=root, env=environment,
                                     capture_output=True, text=True)
            self.assertEqual(75, refused.returncode)
            self.assertEqual('75', (root / 'jobs/run/exitcode').read_text().strip())
            self.assertTrue((root / 'gradle.lock').is_dir())

            (root / 'gradle.lock').rmdir()
            product = root / 'bestbuds'
            (product / 'config').mkdir(parents=True)
            (product / 'config/release.env').write_text('FIXTURE_OPTION=file\nFIXTURE_EMPTY=file\nFIXTURE_DEFAULT=default\n')
            environment.update(FIXTURE_OPTION='caller "quoted" $(false)', FIXTURE_EMPTY='')
            probe = ('test "$FIXTURE_OPTION" = \'caller "quoted" $(false)\' && '
                     'test "$FIXTURE_EMPTY" = "" && test "$FIXTURE_DEFAULT" = default')
            finished = subprocess.run(['/bin/bash', str(script), '/bin/sh', '-c', probe], cwd=product, env=environment)
            self.assertEqual(0, finished.returncode)
            self.assertEqual('0', (root / 'jobs/run/exitcode').read_text().strip())

    def test_worker_programs_compile_before_dispatch(self):
        tree = ast.parse(Path(__file__).with_name('remote-dev').read_text())
        source = Path(__file__).with_name('hosts_contract.py').read_text()
        programs = [(source + '\n' if node.func.id == 'remote_contract_python' else '') + node.args[0].value for node in ast.walk(tree)
                    if isinstance(node, ast.Call) and getattr(node.func, 'id', None) in {'remote_python', 'remote_contract_python'}
                    and node.args and isinstance(node.args[0], ast.Constant)]
        self.assertGreaterEqual(len(programs), 10)
        for program in programs:
            compile(program, '<worker-program>', 'exec')

    def test_delta_sync_checks_preserved_timestamps_and_removes_deleted_sources(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            remote = Path(helper.REMOTE)
            (remote / '.remote-dev').mkdir(parents=True)
            project = helper.ROOT / 'fixture'; project.mkdir()
            destination = remote / 'fixture'; destination.mkdir()
            for name in ['A.kt', 'B.kt', 'C.kt']:
                (project / name).write_text('old')
            transferred = []
            def copy(argv, **kwargs):
                if argv[0] == 'scp':
                    shutil.copy2(argv[2], Path(argv[-1].split(':', 1)[1]))
                    return
                manifest = next(arg.split('=', 1)[1] for arg in argv if arg.startswith('--files-from='))
                transferred.append((argv[1], Path(manifest).read_bytes().split(b'\0')[:-1]))
                command = [arg for arg in argv[:-1] if not arg.startswith('--rsync-path=')]
                subprocess.run(command + [str(destination) + '/'], check=True)
            with patch.object(helper, 'ssh'), patch.object(helper, 'run', side_effect=copy), \
                    patch.object(helper, 'remote_python', side_effect=local_worker), contextlib.redirect_stdout(io.StringIO()):
                helper.sync(['fixture'])
                source = project / 'A.kt'; before = source.stat()
                source.write_text('new'); os.utime(source, ns=(before.st_atime_ns, before.st_mtime_ns))
                (project / 'C.kt').unlink()
                helper.sync(['fixture'], transfer_files={'fixture': ['A.kt']})
                self.assertEqual(('-ac', [b'A.kt']), transferred[-1])
                self.assertEqual('new', (destination / 'A.kt').read_text())
                self.assertEqual('old', (destination / 'B.kt').read_text())
                self.assertFalse((destination / 'C.kt').exists())
                manifest = json.loads((remote / '.remote-dev/fixture.source.json').read_text())
                self.assertIn('B.kt', manifest)
                self.assertNotIn('C.kt', manifest)

    def test_sync_coalesces_transport_without_losing_git_updates_or_dirty_sources(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.REMOTE = str(Path(temporary).resolve() / 'worker with spaces')
            remote = Path(helper.REMOTE)
            (remote / '.remote-dev').mkdir(parents=True)
            project = helper.ROOT / 'fixture'; project.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(project), *args], text=True).strip()
            git('init', '--quiet')
            source = project / 'source.txt'; source.write_text('committed')
            git('add', '.')
            git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test', 'commit', '--quiet', '-m', 'first')
            first = git('rev-parse', 'HEAD')
            source.write_text('dirty source')
            def execute(argv, **kwargs):
                argv = list(map(str, argv))
                self.assertNotEqual('scp', argv[0])
                argv = [arg.removeprefix('fixture:') for arg in argv if not arg.startswith('--rsync-path=')]
                return subprocess.run(argv, check=True, **kwargs)
            def shell(command, **kwargs):
                return subprocess.run(['/bin/sh', '-c', command], check=True, **kwargs)
            with patch.object(helper, 'ssh', side_effect=shell) as transport, \
                    patch.object(helper, 'run', side_effect=execute), \
                    patch.object(helper, 'remote_python', side_effect=local_worker) as programs, \
                    contextlib.redirect_stdout(io.StringIO()):
                helper.sync(['fixture'])
                destination = remote / 'fixture'
                self.assertEqual(first, subprocess.check_output(['git', '-C', str(destination), 'rev-parse', 'HEAD'], text=True).strip())
                self.assertEqual('dirty source', (destination / 'source.txt').read_text())
                self.assertEqual(3, transport.call_count)  # lease, prepare/head, changed-head reset
                self.assertEqual(1, programs.call_count)  # streamed manifest and deletion reconciliation
                transport.reset_mock(); programs.reset_mock()
                helper.sync(['fixture'])
                self.assertEqual(2, transport.call_count)  # unchanged head needs no reset
                self.assertEqual(1, programs.call_count)
                source.unlink(); (project / 'replacement.txt').write_text('next')
                git('add', '-A')
                git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test', 'commit', '--quiet', '-m', 'second')
                helper.sync(['fixture'])
                self.assertEqual(git('rev-parse', 'HEAD'), subprocess.check_output(['git', '-C', str(destination), 'rev-parse', 'HEAD'], text=True).strip())
                self.assertFalse((destination / 'source.txt').exists())
                self.assertEqual('next', (destination / 'replacement.txt').read_text())

    def test_frozen_sync_keeps_staged_submodules_absent_from_the_parent_commit(self):
        import remote_build
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.PROJECTS = ['fixture']
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            remote = Path(helper.REMOTE)
            (remote / '.remote-dev').mkdir(parents=True)
            project = helper.ROOT / 'fixture'; project.mkdir()
            def git(repository, *args):
                return subprocess.check_output(['git', '-C', str(repository), *args], text=True).strip()
            def commit(repository):
                git(repository, 'add', '.')
                git(repository, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test',
                    'commit', '--quiet', '-m', 'fixture')
            git(project, 'init', '--quiet')
            (project / '.gitignore').write_text('vendor/\n')
            (project / 'root.txt').write_text('root')
            commit(project)
            dependency = project / 'vendor/sdk'; dependency.mkdir(parents=True)
            git(dependency, 'init', '--quiet')
            source = dependency / '.agents/skills.json'; source.parent.mkdir()
            source.write_text('{"frozen":true}')
            commit(dependency)
            head = git(dependency, 'rev-parse', 'HEAD')
            git(project, 'update-index', '--add', '--cacheinfo', f'160000,{head},vendor/sdk')
            with contextlib.redirect_stdout(io.StringIO()):
                identity = remote_build.freeze(helper)
                helper.ROOT = remote_build.materialize(helper, identity, 'worker')
            self.assertNotIn('vendor/sdk/.agents/skills.json', helper.source_files('fixture'))
            def execute(argv, **kwargs):
                argv = [str(arg).removeprefix('fixture:') for arg in argv if not str(arg).startswith('--rsync-path=')]
                return subprocess.run(argv, check=True, **kwargs)
            with patch.object(helper, 'ssh', side_effect=lambda command, **kwargs:
                              subprocess.run(['/bin/sh', '-c', command], check=True, **kwargs)), \
                    patch.object(helper, 'run', side_effect=execute), \
                    patch.object(helper, 'remote_python', side_effect=local_worker), \
                    contextlib.redirect_stdout(io.StringIO()):
                helper.sync(['fixture'], frozen=True)
            self.assertEqual(source.read_text(), (remote / 'fixture/vendor/sdk/.agents/skills.json').read_text())
            self.assertEqual(head, git(remote / 'fixture/vendor/sdk', 'rev-parse', 'HEAD'))
            transferred = json.loads((remote / '.remote-dev/fixture.source.json').read_text())
            self.assertIn('vendor/sdk/.agents/skills.json', transferred)

    def test_sync_keeps_a_matching_local_properties_byte_for_byte(self):
        with TemporaryDirectory() as temporary:
            helper = self.helper(temporary)
            helper.REMOTE = str(Path(temporary).resolve() / 'worker')
            (Path(helper.REMOTE) / '.remote-dev').mkdir(parents=True)
            project = helper.ROOT / 'fixture'
            project.mkdir()
            (project / 'build.gradle.kts').write_text('')
            def copy(argv, **kwargs):
                destination = Path(argv[-1].split(':', 1)[1])
                if argv[0] == 'scp':
                    shutil.copy2(argv[2], destination)
                    return
                manifest = next(arg.split('=', 1)[1] for arg in argv if arg.startswith('--files-from='))
                for relative in map(os.fsdecode, filter(None, Path(manifest).read_bytes().split(b'\0'))):
                    (destination / relative).parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(project / relative, destination / relative)
            synced = Path(helper.REMOTE) / 'fixture/local.properties'
            with patch.object(helper, 'ssh'), patch.object(helper, 'run', side_effect=copy), \
                    patch.object(helper, 'remote_python', side_effect=local_worker), contextlib.redirect_stdout(io.StringIO()):
                matching = 'sdk.dir=/Users/ovd/Library/Android/sdk\nbestbuds.devTools=true\n\n'
                (project / 'local.properties').write_text(matching)
                helper.sync(['fixture'])
                self.assertEqual(matching, synced.read_text())
                (project / 'local.properties').write_text('bestbuds.devTools=true\nsdk.dir=/elsewhere\nkotlin.apple.cocoapods.bin=/opt/pod\n')
                helper.sync(['fixture'])
                self.assertEqual('bestbuds.devTools=true\nsdk.dir=/Users/ovd/Library/Android/sdk\n', synced.read_text())


if __name__ == '__main__':
    unittest.main()
