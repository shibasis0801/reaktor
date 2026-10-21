#!/bin/sh
set -eu
sdk=$1
version=$2
url=$3
expected=$4
shift 4
missing=""
for package in "$@"; do
    [ -f "$sdk/$package/package.xml" ] || missing="$missing $package"
done
if [ -z "$missing" ]; then
    echo "Pinned Android SDK packages are already installed"
    exit 0
fi
tools="$sdk/android-cli/$version"
if [ ! -x "$tools/android" ]; then
    mkdir -p "$sdk/android-cli"
    staging=$(mktemp -d "$sdk/android-cli/.install-XXXXXX")
    trap 'rm -rf "$staging"' EXIT HUP INT TERM
    for attempt in 1 2 3 4 5 6; do
        if curl --http1.1 -fsSL --continue-at - --connect-timeout 30 --speed-limit 1024 --speed-time 60 --max-time 300 "$url" -o "$staging/android"; then
            break
        fi
        [ "$attempt" -lt 6 ] || exit 1
    done
    printf '%s  %s\n' "$expected" "$staging/android" | sha256sum --check --status
    chmod 755 "$staging/android"
    mv "$staging" "$tools"
fi
for attempt in 1 2 3; do
    if "$tools/android" --no-metrics --sdk="$sdk" sdk install $missing; then
        exit 0
    else
        status=$?
    fi
    [ "$attempt" -lt 3 ] || exit "$status"
    echo "Android SDK install failed; retrying pinned packages (attempt $((attempt + 1))/3)" >&2
done
