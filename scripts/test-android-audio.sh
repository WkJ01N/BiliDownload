#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/android"
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r audio/build/outputs/apk/debug/audio-debug.apk
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
report="$(adb -e shell am instrument -e verifyFiles true -w io.github.bilidownload.test/androidx.test.runner.AndroidJUnitRunner)"
printf '%s\n' "$report"
grep -Eq 'OK \([1-9][0-9]* tests?\)' <<< "$report"
python "$root/scripts/verify-android-audio.py" app/build/generated/audioFixtures
