import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

import remote_build

LEASE = Path(__file__).resolve().parent / 'gradle-lease'


class GradleLeaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.state = Path(self.temporary.name)
        self.environment = dict(os.environ, REMOTE_DEV_STATE=str(self.state))

    def tearDown(self):
        self.temporary.cleanup()

    def lease(self, *command):
        return subprocess.run([sys.executable, str(LEASE), '--', *command], env=self.environment, capture_output=True, text=True, timeout=60)

    def test_the_command_runs_with_the_lease_and_its_exit_code_is_returned(self):
        result = self.lease('sh', '-c', 'echo ran; exit 3')
        self.assertEqual(3, result.returncode)
        self.assertEqual('ran\n', result.stdout)
        self.assertIn('holds the local Gradle lease', result.stderr)
        self.assertEqual([], [entry for entry in (self.state / 'local-gradle.line').iterdir() if not entry.name.startswith('.')])

    def test_a_command_waits_while_another_process_holds_the_lease(self):
        held = threading.Event()
        release = threading.Event()

        def holder():
            with remote_build.local_lease(self.state):
                held.set()
                release.wait(30)

        thread = threading.Thread(target=holder)
        thread.start()
        self.assertTrue(held.wait(10))
        started = time.monotonic()
        timer = threading.Timer(1.0, release.set)
        timer.start()
        result = self.lease('true')
        thread.join(10)
        self.assertEqual(0, result.returncode)
        self.assertGreaterEqual(time.monotonic() - started, 0.9)

    def test_a_gradlew_inside_runs_gradle_here_instead_of_waiting_for_the_lease_it_runs_under(self):
        result = self.lease('sh', '-c', 'echo "$REMOTE_DEV_WORKER"')
        self.assertEqual(0, result.returncode)
        self.assertEqual('1\n', result.stdout)

    def test_no_command_is_a_usage_error(self):
        result = subprocess.run([sys.executable, str(LEASE)], env=self.environment, capture_output=True, text=True, timeout=30)
        self.assertEqual(2, result.returncode)


if __name__ == '__main__':
    unittest.main()
