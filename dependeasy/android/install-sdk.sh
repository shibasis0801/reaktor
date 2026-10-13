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
tools="$sdk/cmdline-tools/$version"
if [ ! -x "$tools/bin/android" ]; then
    mkdir -p "$sdk/cmdline-tools"
    staging=$(mktemp -d "$sdk/cmdline-tools/.install-XXXXXX")
    trap 'rm -rf "$staging"' EXIT HUP INT TERM
    curl -fsSL --retry 5 --retry-all-errors --connect-timeout 30 "$url" -o "$staging/tools.zip"
    printf '%s  %s\n' "$expected" "$staging/tools.zip" | sha256sum --check --status
    unzip -q "$staging/tools.zip" -d "$staging"
    mv "$staging/cmdline-tools" "$tools"
fi
"$tools/bin/android" --no-metrics --sdk="$sdk" sdk install $missing
