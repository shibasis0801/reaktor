#!/usr/bin/env python3
"""Provision private pilot credentials; Compose owns the single cache service."""
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
from cluster_locations import state_root, workspace_root

ROOT = workspace_root(__file__)
STATE = state_root(ROOT)
configuration = json.loads((STATE / 'config.json').read_text())
directory = STATE / 'build-cache'
directory.mkdir(mode=0o700, exist_ok=True)
client = directory / 'client.json'
image = 'ghcr.io/chrisjenx/silo:0.2.0@sha256:02b7d72fe159002746529a32334993baedd89e1d6070161f1cdd8ac18c4392f0'
if not client.exists():
    accounts = {role: {'username': 'snapshot-' + role, 'password': secrets.token_urlsafe(32)}
                for role in ('reader', 'writer')}
    users = []
    for role, account in accounts.items():
        result = subprocess.run(['docker', 'run', '--rm', '-i', '--platform', 'linux/arm64', '--entrypoint', 'java',
                                 image, '-jar', '/app/silo.jar', 'hash-password'],
                                input=account['password'] + '\n', capture_output=True, text=True, check=True)
        password_hash = next(line.strip() for line in result.stdout.splitlines() if line.startswith('$2'))
        users.append({'username': account['username'], 'password-hash': password_hash,
                      'roles': ['read', 'write'] if role == 'writer' else ['read']})
    # JSON is valid HOCON. Only hashes are mounted; plaintext remains private on this Mac.
    users_file = directory / 'users.conf'
    users_file.write_text(json.dumps({'silo': {'users': users}}, indent=2) + '\n')
    users_file.chmod(0o444)
    descriptor = os.open(client, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(dict(url='http://127.0.0.1:18085/cache/', **accounts), output, indent=2)
target = configuration['remote_root'] + '/.remote-dev/build-cache'
subprocess.run(['ssh', '-o', 'BatchMode=yes', configuration['host'], shlex.join(['mkdir', '-p', target])], check=True)
subprocess.run(['ssh', '-o', 'BatchMode=yes', configuration['host'], shlex.join(['chmod', '700', target])], check=True)
subprocess.run(['scp', '-q', str(directory / 'users.conf'), configuration['host'] + ':' + target + '/users.conf'], check=True)
print('Pilot cache credentials provisioned; cache access remains explicitly opt-in.')
