import json
import ast
import fcntl
import subprocess
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
import threading
import time
from unittest.mock import patch

import remote_build as build


class FrozenBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        project = self.root / 'fixture'
        project.mkdir()
        subprocess.run(['git', 'init', '-q', str(project)], check=True)
        (project / 'source.txt').write_text('committed')
        subprocess.run(['git', '-C', str(project), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(project), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                        'commit', '-qm', 'fixture'], check=True)
        (project / 'source.txt').write_text('dirty')
        (project / 'untracked.txt').write_text('untracked')
        self.helper = SimpleNamespace(ROOT=self.root, STATE=self.root / '.state', PROJECTS=['fixture'],
            git_repositories=lambda _: [Path('.')],
            source_files=lambda name: subprocess.check_output(['git', '-C', str(self.root / name),
                'ls-files', '-z', '--cached', '--others', '--exclude-standard']).decode().strip('\0').split('\0'),
            run=lambda command: subprocess.run(command, check=True))

    def tearDown(self):
        self.temporary.cleanup()

    def test_frozen_dirty_and_untracked_sources_do_not_follow_checkout_edits(self):
        identity = build.freeze(self.helper)
        frozen = self.helper.STATE / 'snapshots' / identity / 'workspace/fixture'
        (self.root / 'fixture/source.txt').write_text('new editor content')
        self.assertEqual('dirty', (frozen / 'source.txt').read_text())
        self.assertEqual('untracked', (frozen / 'untracked.txt').read_text())
        slot = build.materialize(self.helper, identity, 'local')
        (slot / 'fixture/build').mkdir()
        (slot / 'fixture/build/cached').write_text('incremental state')
        (self.root / 'fixture/untracked.txt').unlink()
        newer = build.freeze(self.helper)
        build.materialize(self.helper, newer, 'local')
        self.assertFalse((slot / 'fixture/untracked.txt').exists())
        self.assertEqual('incremental state', (slot / 'fixture/build/cached').read_text())
        self.assertEqual('new editor content', (slot / 'fixture/source.txt').read_text())

    def test_modified_snapshot_is_rejected(self):
        identity = build.freeze(self.helper)
        source = self.helper.STATE / 'snapshots' / identity / 'workspace/fixture/source.txt'
        source.write_text('corrupted')
        with self.assertRaisesRegex(ValueError, 'source mismatch'):
            build.materialize(self.helper, identity, 'local')

    def test_editor_change_during_copy_retries_capture(self):
        copy = build.copy_source
        changed = False
        def racing_copy(source, destination):
            nonlocal changed
            result = copy(source, destination)
            if source == self.root / 'fixture/source.txt' and not changed:
                source.write_text('edited during capture')
                changed = True
            return result
        with patch.object(build, 'copy_source', racing_copy):
            identity = build.freeze(self.helper)
        manifest = json.loads((self.helper.STATE / 'snapshots' / identity / 'manifest.json').read_text())
        self.assertEqual(build.source_manifest(self.helper), manifest)


class LocalLeaseLineTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.state = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def test_a_waiter_that_polls_keeps_its_turn_before_later_blocking_waiters(self):
        order, holding = [], threading.Event()
        def blocking():
            with build.local_lease(self.state, wait=0.01):
                order.append('blocking')
        with build.place_in_line(self.state) as poller, (self.state / 'local-gradle.lock').open('a') as current:
            fcntl.flock(current, fcntl.LOCK_EX)
            waiter = threading.Thread(target=blocking)
            waiter.start()
            time.sleep(0.1)
            fcntl.flock(current, fcntl.LOCK_UN)
            time.sleep(0.1)
            self.assertEqual([], order, 'the later blocking waiter does not overtake the poller')
            self.assertTrue(build.first_in_line(poller))
            with (self.state / 'local-gradle.lock').open('a') as mine:
                self.assertTrue(build.try_lock(mine))
                order.append('poller')
                poller.unlink()
        waiter.join(5)
        self.assertEqual(['poller', 'blocking'], order)

    def test_a_poller_that_stands_aside_lets_later_waiters_through(self):
        with build.place_in_line(self.state) as poller:
            build.stand_aside(poller, True)
            with build.local_lease(self.state, wait=0.01):
                self.assertEqual('aside', poller.read_text())
            build.stand_aside(poller, False)
            with build.place_in_line(self.state) as later:
                self.assertFalse(build.first_in_line(later))
                self.assertTrue(build.first_in_line(poller))

    def test_the_ticket_of_a_waiter_that_exited_is_removed(self):
        line = self.state / 'local-gradle.line'
        line.mkdir()
        (line / '00000000000000000001-1-dead').write_text('')
        with build.place_in_line(self.state) as mine:
            self.assertTrue(build.first_in_line(mine))
        self.assertEqual([], [p.name for p in line.iterdir() if not p.name.startswith('.')])


class ExecutorReceiptTest(unittest.TestCase):
    def test_local_disk_admission_rejects_before_materialization(self):
        helper = SimpleNamespace(STATE=Path('/fixture'), CONFIG={})
        with patch.object(build.shutil, 'disk_usage', return_value=SimpleNamespace(free=512 * 1048576)):
            with self.assertRaisesRegex(ValueError, 'Insufficient local disk headroom'):
                build.disk_admission(helper, 'local')
        with patch.object(build.shutil, 'disk_usage', return_value=SimpleNamespace(free=2048 * 1048576)):
            self.assertEqual(1024 * 1048576, build.disk_admission(helper, 'local')['minimumFreeBytes'])

    def test_worker_admission_checks_both_evidence_and_execution_filesystems(self):
        helper = SimpleNamespace(STATE=Path('/fixture'), REMOTE='/worker', CONFIG={'minimum_free_disk_mib': 2048},
            remote_python=lambda *a, **kw: SimpleNamespace(stdout=str(1024 * 1048576)))
        with patch.object(build.shutil, 'disk_usage', return_value=SimpleNamespace(free=4096 * 1048576)):
            with self.assertRaisesRegex(ValueError, 'Insufficient worker disk headroom'):
                build.disk_admission(helper, 'worker')
            helper.remote_python = lambda *a, **kw: SimpleNamespace(stdout=str(8192 * 1048576))
            self.assertEqual(8192 * 1048576, build.disk_admission(helper, 'worker')['workerFreeBytes'])

    def test_remote_python_programs_compile_before_dispatch(self):
        tree = ast.parse(Path(build.__file__).read_text())
        programs = [node.args[0].value for node in ast.walk(tree)
                    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and node.func.attr == 'remote_python' and node.args
                    and isinstance(node.args[0], ast.Constant)]
        self.assertGreaterEqual(len(programs), 2)
        for program in programs:
            compile(program, '<worker-program>', 'exec')



if __name__ == '__main__':
    unittest.main()
