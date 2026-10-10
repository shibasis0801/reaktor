#!/bin/sh
# Provision a Linux SDK from Dependeasy's pinned contract; retain existing SDK packages.
set -eu
contract=$1
sdk=$2
value() { sh "$(dirname "$0")/../toolchain/read.sh" "$contract" "$1"; }
platform="platforms/android-$(value AndroidSdkPlatform)"
build_tools="build-tools/$(value AndroidBuildTools)"
ndk="ndk/$(value Ndk)"
cmake="cmake/$(value Cmake)"
missing=""
for package in platform-tools "$platform" "$build_tools" "$ndk" "$cmake"; do
    [ -f "$sdk/$package/package.xml" ] || missing="$missing $package"
done
if [ -z "$missing" ]; then
    echo "Pinned Android SDK packages are already installed"
    exit 0
fi
version=$(value AndroidCommandLineTools)
tools="$sdk/cmdline-tools/$version"
if [ ! -x "$tools/bin/android" ]; then
    mkdir -p "$sdk/cmdline-tools"
    staging=$(mktemp -d "$sdk/cmdline-tools/.install-XXXXXX")
    trap 'rm -rf "$staging"' EXIT HUP INT TERM
    curl -fsSL --retry 5 --retry-all-errors --connect-timeout 30 \
        "https://dl.google.com/android/repository/$(value AndroidCommandLineToolsArchive)" \
        -o "$staging/tools.zip"
    printf '%s  %s\n' "$(value AndroidCommandLineToolsSha256)" "$staging/tools.zip" | sha256sum --check --status
    unzip -q "$staging/tools.zip" -d "$staging"
    mv "$staging/cmdline-tools" "$tools"
fi
# SDK package names are fixed, whitespace-free values from the Kotlin contract.
"$tools/bin/android" --no-metrics --sdk="$sdk" sdk install $missing
