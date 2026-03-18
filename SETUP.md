# flatInvoker / reaktor — Setup Guide

This guide covers everything needed to build the project from scratch on macOS (Apple Silicon / arm64).

---

## Prerequisites

### 1. Xcode

Install Xcode from the App Store (version 16+ recommended). Then install the iOS platform for the Simulator:

```bash
xcodebuild -downloadPlatform iOS
```

> This downloads ~8 GB. The build will fail with `iOS X.X is not installed` without this step.

Accept the Xcode license if prompted:
```bash
sudo xcodebuild -license accept
```

---

### 2. Java JDK 21

The project requires **Java 21**. Amazon Corretto is recommended:

```bash
# macOS Apple Silicon (arm64)
curl -L "https://corretto.aws/downloads/latest/amazon-corretto-21-aarch64-macos-jdk.tar.gz" -o /tmp/jdk21.tar.gz
mkdir -p ~/local/jdk
tar -xzf /tmp/jdk21.tar.gz -C ~/local/jdk/
```

Set `JAVA_HOME` permanently by adding to your `~/.zshrc`:

```bash
echo 'export JAVA_HOME=~/local/jdk/amazon-corretto-21.jdk/Contents/Home' >> ~/.zshrc
source ~/.zshrc
```

Verify:
```bash
java -version   # should say OpenJDK 21
```

---

### 3. CMake

The build compiles native C++ dependencies (flatbuffers, hermes) using CMake. It expects CMake at `/usr/local/bin/cmake`.

**Option A — via Homebrew (recommended if you have it):**
```bash
brew install cmake
sudo ln -sf $(which cmake) /usr/local/bin/cmake
```

**Option B — manual install (no Homebrew):**
```bash
curl -L "https://github.com/Kitware/CMake/releases/download/v3.31.6/cmake-3.31.6-macos-universal.tar.gz" -o /tmp/cmake.tar.gz
mkdir -p ~/local/bin ~/local/share
tar -xzf /tmp/cmake.tar.gz -C /tmp/
cp /tmp/cmake-3.31.6-macos-universal/CMake.app/Contents/bin/cmake ~/local/bin/cmake
cp -r /tmp/cmake-3.31.6-macos-universal/CMake.app/Contents/share ~/local/

sudo mkdir -p /usr/local/bin
sudo ln -sf ~/local/bin/cmake /usr/local/bin/cmake
```

---

### 4. Ninja

Ninja is used to build Hermes. It is expected at `/opt/homebrew/bin/ninja`.

**Option A — via Homebrew:**
```bash
brew install ninja
```

**Option B — manual install:**
```bash
curl -L "https://github.com/ninja-build/ninja/releases/download/v1.12.1/ninja-mac.zip" -o /tmp/ninja.zip
mkdir -p ~/local/bin
unzip -o /tmp/ninja.zip -d ~/local/bin/
chmod +x ~/local/bin/ninja

sudo mkdir -p /opt/homebrew/bin
sudo ln -sf ~/local/bin/ninja /opt/homebrew/bin/ninja
```

---

### 5. CocoaPods

Required for iOS dependencies (GoogleSignIn, etc.). Needs Ruby 3.x.

**Option A — via Homebrew (recommended):**
```bash
brew install ruby cocoapods
```

**Option B — via rbenv (no Homebrew):**
```bash
curl -fsSL https://github.com/rbenv/rbenv-installer/raw/HEAD/bin/rbenv-installer | bash
export PATH="$HOME/.rbenv/bin:$PATH"
eval "$(rbenv init -)"

rbenv install 3.3.7
rbenv global 3.3.7

gem install cocoapods
```

Add to `~/.zshrc` to persist:
```bash
echo 'export PATH="$HOME/.rbenv/bin:$HOME/.rbenv/shims:$PATH"' >> ~/.zshrc
echo 'eval "$(rbenv init -)"' >> ~/.zshrc
echo 'export LANG=en_US.UTF-8' >> ~/.zshrc
source ~/.zshrc
```

---

### 6. Android SDK

Install [Android Studio](https://developer.android.com/studio) and ensure the Android SDK is available at `~/Library/Android/sdk` (the default location).

---

## Project Configuration

### local.properties

Create or update `local.properties` in the project root with:

```properties
sdk.dir=/Users/<your-username>/Library/Android/sdk
kotlin.apple.cocoapods.bin=/Users/<your-username>/.rbenv/shims/pod
```

> Replace `<your-username>` with your macOS username.
> If you installed CocoaPods via Homebrew, run `which pod` to get the correct path.

---

## Building

```bash
cd flatInvoker
export JAVA_HOME=~/local/jdk/amazon-corretto-21.jdk/Contents/Home   # skip if set in .zshrc
./gradlew build
```

### First-time build notes

- The first build clones and compiles native dependencies (flatbuffers) — this takes several minutes.
- CocoaPods will install pod dependencies and compile iOS frameworks during the build.
- If cinterop tasks fail with `module 'X' not found`, run:
  ```bash
  ./gradlew :reaktor-auth:podSetupBuildGoogleSignInIos --rerun-tasks
  ./gradlew build
  ```
  This forces Gradle to recapture correct framework paths (can happen after installing the iOS platform).

---

## Environment Summary

| Tool        | Required Version | Expected Path                        |
|-------------|-----------------|--------------------------------------|
| Java        | 21              | `~/local/jdk/amazon-corretto-21.jdk` |
| CMake       | 3.x+            | `/usr/local/bin/cmake`               |
| Ninja       | 1.x+            | `/opt/homebrew/bin/ninja`            |
| CocoaPods   | 1.x+            | via rbenv or Homebrew                |
| Android SDK | latest          | `~/Library/Android/sdk`              |
| Xcode       | 16+             | `/Applications/Xcode.app`           |
| iOS Platform| latest          | installed via `xcodebuild -downloadPlatform iOS` |
