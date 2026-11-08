#!/usr/bin/env python3
"""Install the workspace's compatibility commands from reaktor-cluster."""
import argparse
import os
from pathlib import Path

from cluster_locations import state_root, workspace_root


ASSETS = (
    'remote-dev', 'remote_build.py', 'remote_retention.py', 'hosts_contract.py', 'build_control.py',
    'workspace_storage.py', 'mobile_worker.py', 'atlas-worker.py', 'gradle-lease',
    'dev-storage', 'run-gradle', 'physical-devices.gradle', 'build-cache.gradle',
    'storage-limits.json', 'setup-mobile-worker.py', 'mobile-device-hook.py',
    'setup-build-cache.py', 'cluster_locations.py',
    'test_remote_router.py', 'test_remote_build.py', 'test_remote_retention.py',
    'test_workspace_storage.py', 'test_mobile_worker.py', 'test_atlas_worker.py',
    'test_gradle_lease.py', 'test_noindex_roots.py', 'test_cluster_locations.py', 'test_build_control.py',
)


def install(root, check=False):
    assets = Path(__file__).resolve().parent
    tools = state_root(root).parent / 'tools'
    replace = []
    for name in ASSETS:
        target, link = assets / name, tools / name
        if link.is_symlink() and link.resolve() == target:
            continue
        if link.is_symlink() and os.readlink(link) == '../reaktor/reaktor-cluster/tooling/' + name:
            if check:
                raise ValueError(f'Moved cluster compatibility link needs repair: {link}')
            replace.append(link)
            continue
        if link.exists() or link.is_symlink():
            raise ValueError(f'{link} is not a cluster compatibility link; compare it before replacing it')
        if check:
            raise ValueError(f'Missing cluster compatibility link: {link}')
    if not check:
        tools.mkdir(parents=True, exist_ok=True)
        for name in ASSETS:
            link = tools / name
            if link in replace:
                link.unlink()
            if not link.is_symlink():
                link.symlink_to(assets / name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=workspace_root())
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        install(args.root, check=args.check)
    except ValueError as error:
        parser.exit(1, str(error) + '\n')
    print('Cluster compatibility links verified' if args.check else 'Cluster compatibility links installed')


if __name__ == '__main__':
    main()
