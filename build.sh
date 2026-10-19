#!/usr/bin/env bash
# Builds Dynamic Island Lab into a debug APK (installable on any phone, no keystore needed).
#
#   ./build.sh             build  ->  dist/DynamicIslandLab-debug.apk
#   ./build.sh --install   build, then adb-install to a USB-connected phone
#   ./build.sh --clean     clean first, then build
#
# Needs: JDK 17+ and the Android SDK (Android Studio installs both). Internet on first run.
set -euo pipefail
cd "$(dirname "$0")"

INSTALL=0; CLEAN=0
for a in "$@"; do
  case "$a" in
    --install) INSTALL=1 ;;
    --clean)   CLEAN=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "Unknown option: $a" >&2; exit 2 ;;
  esac
done

die() { echo "ERROR: $*" >&2; exit 1; }

# ---- 1. Java 17+ -------------------------------------------------------------------------
JAVA_BIN="java"
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && JAVA_BIN="$JAVA_HOME/bin/java"
# Fall back to the JDK bundled with Android Studio if none is on PATH.
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  for jbr in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
             "/opt/android-studio/jbr" "$HOME/android-studio/jbr"; do
    if [ -x "$jbr/bin/java" ]; then export JAVA_HOME="$jbr"; JAVA_BIN="$jbr/bin/java"; break; fi
  done
fi
command -v "$JAVA_BIN" >/dev/null 2>&1 || die "Java not found. Install JDK 17+ (or Android Studio) and retry."
JV=$("$JAVA_BIN" -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)?.*/\1/')
[ "${JV:-0}" -ge 17 ] 2>/dev/null || die "JDK 17+ required (found ${JV:-unknown})."
echo "Java $JV OK"

# ---- 2. Android SDK ----------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/opt/android-sdk" "/usr/lib/android-sdk"; do
    [ -d "$c" ] && SDK="$c" && break
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "Android SDK not found. Install Android Studio, or set ANDROID_HOME to your SDK folder."
export ANDROID_HOME="$SDK"
echo "sdk.dir=$SDK" > local.properties
echo "Android SDK: $SDK"

# Accept licences so Gradle can auto-download platform 34 / build-tools on first build.
SDKMGR="$(command -v sdkmanager || true)"
[ -z "$SDKMGR" ] && SDKMGR="$(ls "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | tail -n1 || true)"
if [ -n "$SDKMGR" ]; then
  yes 2>/dev/null | "$SDKMGR" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
else
  echo "Note: sdkmanager not found. If the build stops on a licence prompt, open Android Studio once and accept the SDK licences."
fi

# ---- 3. Build ----------------------------------------------------------------------------
chmod +x ./gradlew
[ "$CLEAN" -eq 1 ] && ./gradlew clean
./gradlew assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "Build finished but $APK is missing."
mkdir -p dist
cp "$APK" dist/DynamicIslandLab-debug.apk
echo
echo "APK ready: $(pwd)/dist/DynamicIslandLab-debug.apk ($(du -h dist/DynamicIslandLab-debug.apk | cut -f1))"

# ---- 4. Optional install -----------------------------------------------------------------
ADB="$SDK/platform-tools/adb"; [ -x "$ADB" ] || ADB="$(command -v adb || true)"
if [ "$INSTALL" -eq 1 ]; then
  [ -n "$ADB" ] || die "adb not found. Install 'Android SDK Platform-Tools'."
  "$ADB" devices | sed -n '2,$p' | grep -q 'device$' \
    || die "No phone detected. Enable USB debugging, plug in, accept the prompt on the phone, retry."
  "$ADB" install -r dist/DynamicIslandLab-debug.apk
  "$ADB" shell am start -n com.tara.dynamicisland/.MainActivity >/dev/null
  echo "Installed and launched on your phone."
else
  echo "To install: ./build.sh --install   (USB debugging on), or copy the APK to your phone and open it."
fi
