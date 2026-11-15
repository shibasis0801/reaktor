import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

import remote_build


class BuildAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / 'state'
        self.adapter = Path(__file__).resolve().parent / 'gradle-lease'
        self.environment = dict(os.environ, REMOTE_DEV_STATE=str(self.state),
                                DEPENDEASY_OPERATION_RUN_ID='admission-test')
        self.children = []

    def tearDown(self):
        for child in self.children:
            if child.poll() is None:
                child.terminate()
            try:
                child.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.communicate(timeout=5)
        self.temporary.cleanup()

    def start(self, project, command, environment=None):
        directory = self.root / project
        directory.mkdir(exist_ok=True)
        child = subprocess.Popen([sys.executable, str(self.adapter), '--auto',
                                  '--project', project, '--task', ':heavy', '--', *command],
                                 cwd=directory, env=environment or self.environment,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.children.append(child)
        return child

    def tickets(self):
        line = self.state / 'local-gradle.line'
        return list(line.glob('[!.]*')) if line.exists() else []

    def wait_for(self, predicate):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.02)
        self.fail('Admission condition did not become true')

    def events(self, output):
        return [json.loads(line.removeprefix('@reaktor-build '))
                for line in output.splitlines() if line.startswith('@reaktor-build ')]

    def test_independent_roots_share_the_existing_exclusive_lease(self):
        marker = self.root / 'executing'
        release = self.root / 'release'
        script = ('from pathlib import Path;import sys,time;'
                  'p=Path(sys.argv[1]);p.write_text("held");'
                  '\nwhile not Path(sys.argv[2]).exists():time.sleep(.02)\n')
        first = self.start('one', [sys.executable, '-c', script, str(marker), str(release)])
        self.wait_for(marker.exists)
        second_marker = self.root / 'second'
        second = self.start('two', [sys.executable, '-c',
                                  'from pathlib import Path;import sys;Path(sys.argv[1]).touch()', str(second_marker)])
        self.wait_for(lambda: len(self.tickets()) == 1)
        time.sleep(0.15)
        self.assertFalse(second_marker.exists())
        release.touch()
        self.assertEqual(0, first.wait(timeout=10))
        output, _ = second.communicate(timeout=10)
        self.assertEqual(0, second.returncode)
        self.assertTrue(second_marker.exists())
        self.assertEqual(['Queued', 'Admitted'], [event['phase'] for event in self.events(output)])
        self.assertEqual([], self.tickets())

    def test_queued_metadata_contains_project_task_and_correlation_only(self):
        with remote_build.local_lease(self.state):
            child = self.start('one', ['true', '-Pprivate=not-a-task'])
            self.wait_for(lambda: len(self.tickets()) == 1)
            details = remote_build.ticket_details(self.tickets()[0])
            self.assertEqual({'project': 'one', 'tasks': [':heavy'], 'runId': 'admission-test'}, details)
            child.terminate()
            child.wait(timeout=10)

    def test_queued_cancellation_removes_only_the_callers_ticket(self):
        with remote_build.local_lease(self.state):
            first = self.start('one', ['true'])
            second = self.start('two', ['true'])
            self.wait_for(lambda: len(self.tickets()) == 2)
            second.send_signal(signal.SIGTERM)
            self.assertEqual(143, second.wait(timeout=10))
            self.wait_for(lambda: len(self.tickets()) == 1)
            self.assertEqual('one', remote_build.ticket_details(self.tickets()[0])['project'])
            self.assertIsNone(first.poll())
        self.assertEqual(0, first.wait(timeout=10))
        self.assertEqual([], self.tickets())

    def test_inherited_worker_flag_does_not_bypass_admission(self):
        marker = self.root / 'started'
        with remote_build.local_lease(self.state):
            child = self.start('one', [sys.executable, '-c',
                                     'from pathlib import Path;import sys;Path(sys.argv[1]).touch()', str(marker)],
                               dict(self.environment, REMOTE_DEV_WORKER='1'))
            self.wait_for(lambda: len(self.tickets()) == 1)
            self.assertFalse(marker.exists())
        self.assertEqual(0, child.wait(timeout=10))
        self.assertTrue(marker.exists())

    def test_metadata_survives_standing_aside_and_legacy_tickets_still_work(self):
        details = {'project': 'one', 'tasks': [':heavy'], 'runId': 'admission-test'}
        with remote_build.place_in_line(self.state, details) as ticket:
            remote_build.stand_aside(ticket, True)
            self.assertEqual(dict(details, aside=True), remote_build.ticket_details(ticket))
            remote_build.stand_aside(ticket, False)
            self.assertEqual(dict(details, aside=False), remote_build.ticket_details(ticket))
        with remote_build.place_in_line(self.state) as ticket:
            remote_build.stand_aside(ticket, True)
            self.assertEqual('aside', ticket.read_text())
            remote_build.stand_aside(ticket, False)
            self.assertEqual('', ticket.read_text())

    def test_dead_tickets_are_recovered_without_deleting_a_live_ticket(self):
        with remote_build.place_in_line(self.state, {'project': 'one'}) as ticket:
            stale = ticket.parent / ('0-99999999-dead')
            stale.write_text('{}')
            self.assertTrue(remote_build.first_in_line(ticket))
            self.assertFalse(stale.exists())
            self.assertTrue(ticket.exists())

    def test_untrusted_or_oversized_metadata_is_not_interpreted(self):
        ticket = self.root / 'ticket'
        for text in ('invalid', '[]', 'x' * 32769):
            ticket.write_text(text)
            self.assertEqual({}, remote_build.ticket_details(ticket))

    def test_known_routed_wrapper_delegates_without_acquiring_a_second_lease(self):
        project = self.root / 'one'
        tooling = project / 'reaktor-cluster/tooling'
        tooling.mkdir(parents=True)
        (self.state).mkdir(exist_ok=True)
        (self.state / 'config.json').write_text('{}')
        source = Path(__file__).resolve().parents[2] / 'gradlew'
        header = source.read_text().split('\nfi\n', 1)[0] + '\nfi\n'
        wrapper = project / 'gradlew'
        wrapper.write_text(header)
        wrapper.chmod(0o700)
        router = tooling / 'remote-dev'
        router.write_text('#!/bin/sh\nprintf "routed:%s\\n" "${REMOTE_DEV_WORKER:-clear}"\n')
        router.chmod(0o700)
        (tooling / 'cluster_locations.py').write_text('import sys;sys.exit(0)\n')
        with remote_build.local_lease(self.state):
            child = self.start('one', [str(wrapper)], dict(self.environment, REMOTE_DEV_WORKER='1'))
            output, _ = child.communicate(timeout=5)
            self.assertEqual(0, child.returncode)
            self.assertEqual('routed:clear\n', output)
            self.assertEqual([], self.tickets())

    def test_missing_or_unconfigured_router_keeps_the_existing_local_gate(self):
        source = Path(__file__).resolve().parents[2] / 'gradlew'
        header = source.read_text().split('\nfi\n', 1)[0] + '\nfi\n'
        self.state.mkdir(exist_ok=True)
        (self.state / 'config.json').write_text('{}')
        for scenario in ('missing', 'unconfigured'):
            project = self.root / scenario
            tooling = project / 'reaktor-cluster/tooling'
            tooling.mkdir(parents=True)
            wrapper = project / 'gradlew'
            wrapper.write_text(header + 'exit 0\n')
            wrapper.chmod(0o700)
            if scenario == 'unconfigured':
                router = tooling / 'remote-dev'
                router.write_text('#!/bin/sh\nexit 99\n')
                router.chmod(0o700)
                (tooling / 'cluster_locations.py').write_text('import sys;sys.exit(1)\n')
            with remote_build.local_lease(self.state):
                child = self.start(scenario, [str(wrapper)], dict(self.environment, HOME=str(self.root)))
                self.wait_for(lambda: len(self.tickets()) == 1)
                self.assertIsNone(child.poll())
            self.assertEqual(0, child.wait(timeout=10))
            self.assertEqual([], self.tickets())

    def test_a_router_marker_in_a_comment_does_not_bypass_the_lease(self):
        project = self.root / 'one'
        project.mkdir()
        self.state.mkdir()
        (self.state / 'config.json').write_text('{}')
        wrapper = project / 'gradlew'
        wrapper.write_text('#!/bin/sh\n# remote-dev gradle --project-dir cluster_locations.py configured\nexit 0\n')
        wrapper.chmod(0o700)
        with remote_build.local_lease(self.state):
            child = self.start('one', [str(wrapper)])
            self.wait_for(lambda: len(self.tickets()) == 1)
            self.assertIsNone(child.poll())
        self.assertEqual(0, child.wait(timeout=10))

    def test_uncorrelated_legacy_commands_keep_their_stdout(self):
        environment = dict(self.environment)
        environment.pop('DEPENDEASY_OPERATION_RUN_ID')
        child = self.start('one', [sys.executable, '-c', 'print("done")'], environment)
        output, _ = child.communicate(timeout=10)
        self.assertEqual(0, child.returncode)
        self.assertEqual('done\n', output)


if __name__ == '__main__':
    unittest.main()
