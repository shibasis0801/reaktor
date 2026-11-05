"""Locate shared cluster assets separately from a workspace's durable state."""
import argparse
from pathlib import Path


def workspace_root(source=__file__):
    assets = Path(source).resolve().parent
    if assets.name == 'tooling' and assets.parent.name == 'reaktor-cluster':
        return assets.parents[2]
    return assets.parent


def state_root(workspace=None):
    workspace = Path(workspace if workspace is not None else workspace_root()).resolve()
    candidates = [workspace / '.remote-dev', workspace.parent / 'other/.remote-dev']
    for path in candidates:
        if (path / 'config.json').is_file():
            return path
    return next((path for path in candidates if path.is_dir()), candidates[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['state-root', 'configured'])
    parser.add_argument('--workspace', type=Path, default=workspace_root())
    args = parser.parse_args()
    state = state_root(args.workspace)
    if args.command == 'configured':
        return 0 if (state / 'config.json').is_file() else 1
    print(state)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
