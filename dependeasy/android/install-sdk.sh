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
    curl --http1.1 -fsSL --retry 5 --retry-all-errors --connect-timeout 30 "$url" -o "$staging/android"
    printf '%s  %s\n' "$expected" "$staging/android" | sha256sum --check --status
    chmod 755 "$staging/android"
    mv "$staging" "$tools"
fi
"$tools/android" --no-metrics --sdk="$sdk" sdk install $missing
