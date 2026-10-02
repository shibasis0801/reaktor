import argparse
import base64
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent
STATE = ROOT / ".state"
ENV = STATE / "measure.env"
UPSTREAM = ROOT / "measure" / "self-host"
API = "http://127.0.0.1:47180"
DASHBOARD = "http://127.0.0.1:47130"
INGEST = "http://127.0.0.1:47185"


def setup():
    if not (UPSTREAM / "compose.yml").is_file():
        raise RuntimeError("Initialize the Measure checkout: git submodule update --init ecosystem/measure")
    STATE.mkdir(mode=0o700, exist_ok=True)
    STATE.chmod(0o700)
    if ENV.exists():
        return
    temporary = STATE / "measure.env.new"
    temporary.touch(mode=0o600)
    subprocess.run(
        ["bash", "-c", 'source ./config.sh >/dev/null; ENV_FILE="$1"; NAMESPACE=reaktor; write_dev_env', "measure-config", str(temporary)],
        cwd=UPSTREAM, check=True, stdout=subprocess.DEVNULL,
    )
    replacements = {
        "NEXT_PUBLIC_SITE_URL": DASHBOARD,
        "NEXT_PUBLIC_API_BASE_URL": API,
        "NEXT_PUBLIC_INGEST_BASE_URL": INGEST,
        "NEXT_PUBLIC_AGENT_BASE_URL": "http://127.0.0.1:47184",
        "ATTACHMENTS_S3_ORIGIN": "http://127.0.0.1:47190",
        "SESSION_ACCESS_SECRET": secrets.token_hex(32),
        "SESSION_REFRESH_SECRET": secrets.token_hex(32),
        "POSTGRES_PASSWORD": secrets.token_hex(24),
        "MINIO_ROOT_PASSWORD": secrets.token_hex(24),
        "IGGY_PASSWORD": secrets.token_hex(24),
        "SMTP_HOST": "",
        "SMTP_PORT": "",
        "SMTP_USER": "",
        "SMTP_PASSWORD": "",
    }
    replacements["SYMBOLS_SECRET_ACCESS_KEY"] = replacements["MINIO_ROOT_PASSWORD"]
    replacements["ATTACHMENTS_SECRET_ACCESS_KEY"] = replacements["MINIO_ROOT_PASSWORD"]
    for key in ("CLICKHOUSE_PASSWORD", "CLICKHOUSE_ADMIN_PASSWORD", "CLICKHOUSE_OPERATOR_PASSWORD", "CLICKHOUSE_READER_PASSWORD", "CLICKHOUSE_AGENT_SQL_PASSWORD"):
        replacements[key] = secrets.token_hex(24)
    lines = temporary.read_text().splitlines()
    temporary.write_text("\n".join(
        f"{line.split('=', 1)[0]}={replacements[line.split('=', 1)[0]]}"
        if '=' in line and line.split('=', 1)[0] in replacements else line
        for line in lines
    ) + "\n")
    temporary.chmod(0o600)
    temporary.replace(ENV)


def compose_command(*args):
    return [
        "docker", "compose", "--project-name", "reaktor-measure", "--env-file", str(ENV),
        "-f", str(UPSTREAM / "compose.yml"), "-f", str(UPSTREAM / "compose.prod.yml"),
        "-f", str(ROOT / "measure.compose.yml"), "--profile", "migrate", *args,
    ]
def docker_environment():
    config = STATE / "docker"
    config.mkdir(mode=0o700, exist_ok=True)
    original = Path(os.environ.get("DOCKER_CONFIG", str(Path.home() / ".docker")))
    (config / "config.json").write_text(json.dumps({"cliPluginsExtraDirs": [str(original / "cli-plugins")]}))
    environment = dict(os.environ, DOCKER_CONFIG=str(config), COMPOSE_BAKE="false", COMPOSE_PARALLEL_LIMIT="1")
    environment.pop("DOCKER_DEFAULT_PLATFORM", None)
    if "DOCKER_HOST" not in environment:
        result = subprocess.run(["docker", "context", "inspect"], capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise RuntimeError("Could not resolve the active Docker context")
        environment["DOCKER_HOST"] = json.loads(result.stdout)[0]["Endpoints"]["docker"]["Host"]
    return environment


def compose(*args, timeout=1200):
    command = compose_command(*args)
    with (STATE / "measure-server.log").open("a") as log:
        result = subprocess.run(command, cwd=UPSTREAM, env=docker_environment(), stdout=log, stderr=log, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Measure Compose failed. See {STATE / 'measure-server.log'}")


def provision():
    identity_path = STATE / "identity.json"
    if identity_path.exists():
        identity = json.loads(identity_path.read_text())
    else:
        identity = {key: str(uuid.uuid4()) for key in ("user", "team", "session")}
        identity_path.write_text(json.dumps(identity))
        identity_path.chmod(0o600)
    for value in identity.values():
        uuid.UUID(value)
    sql = f"""
    BEGIN;
    INSERT INTO measure.users(id,name,email,confirmed_at,last_sign_in_at,created_at,updated_at)
    VALUES ('{identity['user']}','Reaktor local operator','reaktor-local@localhost',now(),now(),now(),now()) ON CONFLICT(id) DO NOTHING;
    INSERT INTO measure.teams(id,name,updated_at) VALUES ('{identity['team']}','Reaktor local',now()) ON CONFLICT(id) DO NOTHING;
    INSERT INTO measure.team_membership(team_id,user_id,role,role_updated_at)
    SELECT '{identity['team']}','{identity['user']}','owner',now()
    WHERE NOT EXISTS(SELECT 1 FROM measure.team_membership WHERE team_id='{identity['team']}' AND user_id='{identity['user']}');
    INSERT INTO measure.auth_sessions(id,user_id,oauth_provider,user_metadata,at_expiry_at,rt_expiry_at)
    VALUES ('{identity['session']}','{identity['user']}','reaktor-local','{{}}',now()+interval '30 minutes',now()+interval '7 days')
    ON CONFLICT(id) DO UPDATE SET at_expiry_at=excluded.at_expiry_at,rt_expiry_at=excluded.rt_expiry_at;
    COMMIT;
    """
    result = subprocess.run(compose_command("exec", "--no-TTY", "postgres", "psql", "-U", "postgres", "-d", "measure", "-v", "ON_ERROR_STOP=1"),
                            cwd=UPSTREAM, env=docker_environment(), input=sql, text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Could not provision the local Measure operator after database migrations")
    values = dict(line.split("=", 1) for line in ENV.read_text().splitlines() if line and not line.startswith("#") and "=" in line)
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=")
    def token(secret, claims):
        body = encode(b'{"alg":"HS256","typ":"JWT"}') + b"." + encode(json.dumps(claims, separators=(",", ":")).encode())
        return (body + b"." + encode(hmac.new(secret.encode(), body, hashlib.sha256).digest())).decode()
    access = token(values["SESSION_ACCESS_SECRET"], {"iat": int(time.time()), "exp": int(time.time()) + 1800,
                   "sub": identity["user"], "jti": identity["session"], "iss": "measure"})
    def request(path, body=None):
        req = urllib.request.Request(API + path, data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": "Bearer " + access, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    path = f"/teams/{identity['team']}/apps"
    try:
        apps = request(path)
    except urllib.error.HTTPError as failure:
        if failure.code != 404:
            raise
        apps = []
    for platform in ("Android", "iOS"):
        name = f"Reaktor {platform}"
        if not any(app["name"] == name for app in apps):
            apps.append(request(path, {"name": name}))
    connection = {"api_url": API, "ingest_url": INGEST, "dashboard_url": DASHBOARD, "team_id": identity["team"],
                  "access_token": access, "expires_at": int(time.time()) + 1800,
                  "apps": [{"id": app["id"], "name": app["name"], "api_key": app["api_key"]["key"]} for app in apps]}
    target = STATE / "connection.json"
    temporary = STATE / "connection.json.new"
    temporary.write_text(json.dumps(connection, indent=2) + "\n")
    temporary.chmod(0o600)
    temporary.replace(target)


def healthy():
    try:
        for url in (API + "/ping", INGEST + "/ping", DASHBOARD):
            with urllib.request.urlopen(url, timeout=3) as response:
                if response.status != 200:
                    return False
        return True
    except (OSError, ValueError):
        return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("up", "stop", "status", "config", "connect"), default="up", nargs="?")
    command = parser.parse_args().command
    if command == "status":
        ready = healthy()
        print("Measure is ready" if ready else "Measure is not ready")
        return 0 if ready else 1
    STATE.mkdir(mode=0o700, exist_ok=True)
    lock = (STATE / "startup.lock").open("w")
    fcntl.flock(lock, fcntl.LOCK_EX)
    setup()
    if command == "config":
        compose("config", "--quiet")
        print("Measure configuration is valid")
    elif command == "stop":
        compose("stop", timeout=90)
        print("Measure stopped; recordings and database volumes are preserved")
    elif command == "connect":
        if not healthy():
            raise RuntimeError("Start local Measure before connecting")
        provision()
        print("Local Measure connection refreshed")
    else:
        if not healthy():
            try:
                result = subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}"], capture_output=True, timeout=15)
            except subprocess.TimeoutExpired:
                raise RuntimeError("Docker is not responding. Start or repair Docker Desktop, then retry.")
            if result.returncode:
                raise RuntimeError("Docker is unavailable. Start Docker Desktop, then retry.")
            for service in ("api", "ingest", "ingest-worker", "cleanup", "alerts", "symboloader", "migrator", "agent", "dashboard"):
                compose("build", service)
            compose("up", "--no-build", "--detach", "--wait", "--wait-timeout", "240")
        if not healthy():
            raise RuntimeError("Measure started but its dashboard, API or ingest health check failed")
        provision()
        print(f"Measure ready: dashboard {DASHBOARD}, API {API}, ingest {INGEST}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, subprocess.SubprocessError, OSError) as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
