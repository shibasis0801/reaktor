#!/bin/sh
set -eu
contract=$1
name=$2
case "$name" in ''|*[!a-zA-Z0-9_]*) echo "Invalid toolchain constant: $name" >&2; exit 1 ;; esac
value=$(sed -n "s/^[[:space:]]*const val $name = \"\([^\"]*\)\"[[:space:]]*$/\1/p" "$contract")
test -n "$value" && test "$(printf '%s\n' "$value" | wc -l | tr -d ' ')" = 1 || {
    echo "Expected one literal toolchain constant: $name" >&2
    exit 1
}
printf '%s\n' "$value"
