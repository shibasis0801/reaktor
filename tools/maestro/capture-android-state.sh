#!/bin/zsh
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <app-id> [capture-name] [output-root]" >&2
  exit 1
fi

APP_ID="$1"
CAPTURE_NAME="${2:-screen}"
OUTPUT_ROOT="${3:-$(pwd)/tmp/maestro-captures/$(date +%Y-%m-%d)}"

ADB_BIN="${ADB_BIN:-${ANDROID_HOME:-}/platform-tools/adb}"
if [[ -z "${ADB_BIN}" || ! -x "${ADB_BIN}" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi

if [[ -z "${ADB_BIN}" ]]; then
  echo "adb not found. Install Android platform-tools or set ADB_BIN." >&2
  exit 1
fi

DEVICE_ID="${ANDROID_SERIAL:-$(${ADB_BIN} devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')}"
if [[ -z "${DEVICE_ID}" ]]; then
  echo "No connected Android device found." >&2
  exit 1
fi

mkdir -p "${OUTPUT_ROOT}"
PNG_PATH="${OUTPUT_ROOT}/${CAPTURE_NAME}.png"
XML_PATH="${OUTPUT_ROOT}/${CAPTURE_NAME}.xml"

${ADB_BIN} -s "${DEVICE_ID}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
${ADB_BIN} -s "${DEVICE_ID}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
${ADB_BIN} -s "${DEVICE_ID}" shell input swipe 540 1800 540 400 200 >/dev/null 2>&1 || true
${ADB_BIN} -s "${DEVICE_ID}" shell monkey -p "${APP_ID}" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
${ADB_BIN} -s "${DEVICE_ID}" exec-out screencap -p > "${PNG_PATH}"
${ADB_BIN} -s "${DEVICE_ID}" shell uiautomator dump /sdcard/${CAPTURE_NAME}.xml >/dev/null
${ADB_BIN} -s "${DEVICE_ID}" pull /sdcard/${CAPTURE_NAME}.xml "${XML_PATH}" >/dev/null

echo "Captured Android screen to ${PNG_PATH}"
echo "Captured Android hierarchy to ${XML_PATH}"
