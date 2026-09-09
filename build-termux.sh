#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "== MJ Assistant Termux build =="
echo "Project: $PROJECT_DIR"

if ! command -v gradle >/dev/null 2>&1; then
  echo "ERROR: Gradle is not installed. Install it with: pkg install gradle"
  exit 1
fi

if ! command -v aapt2 >/dev/null 2>&1; then
  echo "ERROR: native AAPT2 is missing."
  echo "Install it with: pkg install aapt2"
  exit 1
fi

AAPT2_BIN="$(command -v aapt2)"
if [ ! -x "$AAPT2_BIN" ]; then
  echo "ERROR: AAPT2 exists but is not executable: $AAPT2_BIN"
  exit 1
fi

# Verify the binary can start before Gradle launches dozens of workers.
if ! "$AAPT2_BIN" version >/dev/null 2>&1; then
  echo "ERROR: Termux AAPT2 cannot start on this device."
  echo "Reinstall/update it with: pkg reinstall aapt2"
  exit 1
fi

export GRADLE_OPTS="${GRADLE_OPTS:-} -Dfile.encoding=UTF-8"
export ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride="$AAPT2_BIN"

echo "AAPT2: $AAPT2_BIN"
echo "Gradle: $(gradle --version | sed -n 's/^Gradle \(.*\)$/\1/p' | head -1)"

# Stop stale daemons so an old AAPT2/Gradle process cannot be reused.
gradle --stop >/dev/null 2>&1 || true

# Clean only build outputs; source/model files are untouched.
gradle --no-daemon clean
gradle --no-daemon :app:assembleDebug --stacktrace

echo
echo "BUILD SUCCESSFUL"
ls -lh app/build/outputs/apk/debug/*.apk
