import json
from pathlib import Path


def read_hosts_contract(project):
    root = Path(project).resolve()
    contract = root / 'build/dependeasy/remote/hosts.json'
    if not contract.is_file():
        return {}
    if contract.is_symlink() or not contract.resolve().is_relative_to(root) or contract.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Invalid remote hosts contract')
    policy = json.loads(contract.read_text())
    if not isinstance(policy, dict) or policy.get('schema') != 2:
        raise ValueError('Unsupported remote hosts contract')
    return policy


def bundle_directories(project, policy):
    root = Path(project).resolve()
    locations = policy.get('bundleDirectories', [])
    if not isinstance(locations, list):
        raise ValueError('Invalid bundle declarations')
    paths = []
    for location in locations:
        path = Path(location) if isinstance(location, str) else None
        if path is None or path == Path('.') or path.is_absolute() or '..' in path.parts or not (root / path).resolve().is_relative_to(root):
            raise ValueError('Bundle is not an owned workspace output')
        if any((root / parent).is_symlink() for parent in (path, *path.parents)):
            raise ValueError('Bundle output must not traverse a link')
        paths.append(path)
    return paths
