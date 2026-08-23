#!/data/data/com.termux/files/usr/bin/bash
set -e
echo "== MJ Assistant build =="
java -version
gradle --version
gradle --no-daemon clean
gradle --no-daemon assembleDebug
echo "APK:"
find app/build/outputs/apk -type f -name "*.apk" -print
