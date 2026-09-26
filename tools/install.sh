#!/bin/sh
# Build, install (keeping app data and models), re-enable accessibility.
set -e
cd "$(dirname "$0")/.."
./gradlew :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
sh tools/enable_a11y.sh
