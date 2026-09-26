#!/bin/sh
# Build, install (keeping app data and models), re-enable accessibility.
set -e
cd "$(dirname "$0")/.."
./gradlew :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in RECORD_AUDIO CALL_PHONE SEND_SMS READ_CONTACTS READ_SMS; do adb shell pm grant com.hackathon.assistant android.permission.$p; done
adb shell appops set --uid com.hackathon.assistant MANAGE_EXTERNAL_STORAGE allow
sh tools/enable_a11y.sh
