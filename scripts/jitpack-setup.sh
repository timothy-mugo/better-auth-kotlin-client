#!/usr/bin/env bash
# Best-effort Android SDK preparation for JitPack builds (run from jitpack.yml before the build).
# JitPack provides ANDROID_HOME; this accepts licenses and installs the platform this project compiles against.
# It never fails the build: if sdkmanager is missing, Gradle's own SDK download takes over.
set -uo pipefail

platform="android-36"
build_tools="36.0.0"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
  echo "jitpack-setup: ANDROID_HOME is not set; skipping Android SDK preparation"
  exit 0
fi

sdkmanager="$(ls "$sdk"/cmdline-tools/*/bin/sdkmanager "$sdk"/tools/bin/sdkmanager 2>/dev/null | head -1)"
if [ -z "$sdkmanager" ]; then
  echo "jitpack-setup: no sdkmanager under $sdk; skipping (Gradle will try to download missing SDK parts)"
  exit 0
fi

echo "jitpack-setup: using $sdkmanager"
yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true
"$sdkmanager" "platforms;$platform" "build-tools;$build_tools" || echo "jitpack-setup: sdkmanager install failed; continuing"
exit 0
