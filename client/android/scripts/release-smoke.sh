#!/bin/bash
# Smoke test of the release APK (R8-minified) on a running emulator/device: install, open, visit the main screens
# through deep links, then 1 500 pseudo-random UI events (fixed seed, only inside the app), and fail on any crash or
# ANR of the app. Instrumented tests run on the debug build; this catches what minification alone could break.
# usage: android/scripts/release-smoke.sh <release.apk>
set -euo pipefail
apk=$1
pkg=io.github.roberbarras.racha
adb=${ADB:-adb}

$adb uninstall "$pkg" >/dev/null 2>&1 || true
$adb install -r "$apk" >/dev/null
$adb logcat -c
$adb shell am start -W -n "$pkg/io.github.roberbarras.racha.android.MainActivity" >/dev/null
sleep 5
for link in racha://stats racha://settings racha://account racha://nonsense; do
  $adb shell am start -W -a android.intent.action.VIEW -d "$link" "$pkg" >/dev/null
  sleep 2
done
# Monkey stays inside the app (-p), no system-level keys that could leave it or change settings.
$adb shell monkey -p "$pkg" -s 4242 --throttle 60 --pct-syskeys 0 --pct-appswitch 0 --ignore-security-exceptions -v 1500 \
  >/tmp/racha-monkey.log 2>&1 || true
sleep 2
crashes=$($adb logcat -d -b crash | grep -c "Process: $pkg" || true)
anrs=$($adb logcat -d | grep -c "ANR in $pkg" || true)
monkey_crash=$(grep -c "CRASH: $pkg" /tmp/racha-monkey.log || true)
running=$($adb shell pidof "$pkg" >/dev/null && echo yes || echo no)
echo "release smoke: crashes=$crashes anrs=$anrs monkey_crash=$monkey_crash still_running=$running"
if [ "$crashes" != 0 ] || [ "$anrs" != 0 ] || [ "$monkey_crash" != 0 ]; then
  $adb logcat -d -b crash | tail -40
  exit 1
fi
