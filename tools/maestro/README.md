# Maestro Tooling

Reusable mobile-testing tooling lives here. Product-specific flows should stay in the product repo.

Current split:
- `flatInvoker/tools/maestro`: reusable install/run/capture scripts
- product repo `maestro/`: app-specific flows, assertions, and screenshots

## Runner
- `maestro-runner` is the default runner on both Android and iOS
- `MAESTRO_ENGINE` still exists as an escape hatch, but the checked-in setup assumes `maestro-runner`

## Android prerequisites
- `maestro-runner`
- `adb` on `PATH`, or `ADB_BIN` set explicitly
- a connected device visible through `adb devices`

## Run a product flow
From the product repo:

```sh
../flatInvoker/tools/maestro/run-android-flow.sh maestro/android
```

Run a single flow:

```sh
../flatInvoker/tools/maestro/run-android-flow.sh maestro/android/login-screen.yaml
```

Override device or output folder:

```sh
ANDROID_SERIAL=RZCY11GDXHT \
MAESTRO_OUTPUT_DIR=$PWD/tmp/maestro-results/manual \
../flatInvoker/tools/maestro/run-android-flow.sh maestro/android/dev-login-shibasis.yaml
```

## iOS prerequisites
- `maestro-runner` installed, or `MAESTRO_RUNNER_BIN` set explicitly
- Xcode command line tools working
- a signed iOS app build if you want the runner to reinstall the app before testing
- `MAESTRO_TEAM_ID` set for WDA signing

Run an iOS flow:

```sh
MAESTRO_TEAM_ID=YOUR_TEAM_ID \
MAESTRO_APP_FILE=$PWD/tmp/xcode-derived/Build/Products/Debug-iphoneos/iosApp.app \
../flatInvoker/tools/maestro/run-ios-flow.sh maestro/ios/login-screen.yaml
```

If the app is already installed and you do not want to reinstall it:

```sh
MAESTRO_TEAM_ID=YOUR_TEAM_ID \
../flatInvoker/tools/maestro/run-ios-flow.sh maestro/ios/dev-login-shibasis.yaml
```

## Capture current Android UI state

```sh
../flatInvoker/tools/maestro/capture-android-state.sh ai.bestbuds.app login-screen
```

That writes a PNG screenshot plus a pulled `uiautomator` XML hierarchy into `tmp/maestro-captures/<date>/`.
