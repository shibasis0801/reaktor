import base64
import hashlib
import hmac
import io
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.error

import measure_server as server


class LocalMeasureTest(unittest.TestCase):
    def test_bootstrap_is_idempotent_and_mints_expiring_private_dashboard_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            env = state / "measure.env"
            env.write_text("SESSION_ACCESS_SECRET=private-test-secret\n")
            apps = []
            calls = []
            sql = []

            def request(req, timeout):
                calls.append(req)
                if req.data is None:
                    if not apps:
                        raise urllib.error.HTTPError(req.full_url, 404, "No apps", {}, None)
                    body = apps
                else:
                    body = {"id": str(len(apps)), "name": json.loads(req.data)["name"], "api_key": {"key": "sdk-ingest-key"}}
                    apps.append(body)
                return io.BytesIO(json.dumps(body).encode())

            def postgres(command, **kwargs):
                sql.append(kwargs["input"])
                return type("Result", (), {"returncode": 0})()

            with patch.object(server, "STATE", state), patch.object(server, "ENV", env), patch.object(server, "docker_environment", return_value={}), \
                    patch.object(server.subprocess, "run", side_effect=postgres), patch.object(server.urllib.request, "urlopen", side_effect=request):
                server.provision()
                identity = (state / "identity.json").read_text()
                server.provision()
            self.assertEqual(2, len(apps))
            self.assertEqual(identity, (state / "identity.json").read_text())
            self.assertEqual(sql[0], sql[1])
            connection = json.loads((state / "connection.json").read_text())
            header, claims, signature = connection["access_token"].split(".")
            decode = lambda value: base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
            expected = hmac.new(b"private-test-secret", f"{header}.{claims}".encode(), hashlib.sha256).digest()
            self.assertEqual(expected, decode(signature))
            claims = json.loads(decode(claims))
            self.assertEqual("measure", claims["iss"])
            self.assertEqual(json.loads(identity)["user"], claims["sub"])
            self.assertLessEqual(claims["exp"], time.time() + 1800)
            self.assertGreater(claims["exp"], time.time() + 1700)
            self.assertEqual(0o600, (state / "connection.json").stat().st_mode & 0o777)
            self.assertTrue(all(req.headers["Authorization"].startswith("Bearer ") for req in calls))
            self.assertNotEqual(connection["access_token"], connection["apps"][0]["api_key"])
            self.assertEqual(json.loads(identity)["team"], connection["team_id"])
            self.assertEqual(server.DASHBOARD, connection["dashboard_url"])

    def test_invalid_operator_identity_cannot_reach_database(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            (state / "identity.json").write_text(json.dumps({"user": "bad'identity", "team": "bad", "session": "bad"}))
            with patch.object(server, "STATE", state), patch.object(server.subprocess, "run") as run:
                with self.assertRaises(ValueError):
                    server.provision()
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
