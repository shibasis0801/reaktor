# Reaktor Maestro Tooling

This folder contains reusable Maestro runner scripts shared by product repos.

Product-specific flows belong in the product repo. For BestBuds, see:
- [bestbuds/maestro](https://github.com/shibasis0801/bestbuds/blob/main/maestro/README.md)

## What lives here

- Android flow runner
- iOS flow runner
- shared environment detection and output handling
- optional step-by-step screenshot instrumentation

## Common commands

From a product repo:

```sh
../reaktor/tools/maestro/run-android-flow.sh maestro/android
../reaktor/tools/maestro/run-ios-flow.sh maestro/ios/login-screen.yaml
```

## Screenshot modes

Default behavior:
- screenshots are kept when flows explicitly call `takeScreenshot`
- successful runs are exported into the product repo's `maestro/screenshots/...` folder

Step-by-step capture mode:
- set `MAESTRO_EVERY_STEP_SCREENSHOT=true`
- or use the product-level screenshot npm commands if they exist

## iOS requirements

- `maestro-runner`
- Xcode command line tools
- signed WDA configuration
- `MAESTRO_TEAM_ID`
- optional `MAESTRO_APP_FILE` if the wrapper should reinstall the app before the run

## Android requirements

- `maestro-runner`
- `adb`
- connected Android device visible to `adb devices`
