#!/bin/bash
# Smoke test of the release APK (R8-minified) on a running emulator/device: install, open, visit the main screens
# through deep links, then 1 500 pseudo-random UI events (fixed seed, only inside the app), and fail on any crash or
# ANR of the app. Instrumented tests run on the debug build; this catches what minification alone could break.
# usage: android/scripts/release-smoke.sh <release.apk>
set -euo pipefail
report=${SMOKE_REPORT:-/tmp/racha-release-smoke.txt}
: >"$report"
trap 'echo "release smoke failed at line $LINENO: $BASH_COMMAND" >>"$report"' ERR
apk=$1
pkg=io.github.roberbarras.racha
adb=${ADB:-adb}

$adb uninstall "$pkg" >/dev/null 2>&1 || true
# Leave the device as found (the debug build and its tests may run next, possibly with another signature).
trap '$adb uninstall "$pkg" >/dev/null 2>&1 || true' EXIT
# Right after a cold boot the package service may not be up yet (broken pipe, null PackageManagerInternal) or the
# emulator may still be optimising packages: wait until it answers, then install, a few times at most.
package_service_ready() {
  [ "$($adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && $adb shell pm path android >/dev/null 2>&1
}
installed=no
$adb shell pm trim-caches 64G >/dev/null 2>&1 || true
for attempt in 1 2 3 4; do
  for _ in $(seq 60); do package_service_ready && break; sleep 5; done
  $adb shell df -h /data >>"$report" 2>&1 || true
  if $adb install -r "$apk" >>"$report" 2>&1; then installed=yes; break; fi
  echo "install attempt $attempt failed" >>"$report"
  $adb shell pm trim-caches 64G >/dev/null 2>&1 || true
  sleep 20
done
if [ "$installed" != yes ]; then
  # What fills /data (the emulator runs as root on google_apis images).
  { $adb root >/dev/null 2>&1 && sleep 3 && $adb shell 'du -xsk /data/* /data/media/0/* /data/local/tmp 2>/dev/null | sort -rn | head -15'; } >>"$report" 2>&1 || true
  exit 1
fi
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
summary="release smoke: crashes=$crashes anrs=$anrs monkey_crash=$monkey_crash still_running=$running"
echo "$summary"
{
  echo "$summary"
  $adb logcat -d -b crash | grep -A12 "Process: $pkg" | head -40
  $adb logcat -d | grep -A12 "ANR in $pkg" | head -30
} >>"$report"
if [ "$crashes" != 0 ] || [ "$anrs" != 0 ] || [ "$monkey_crash" != 0 ]; then
  cat "$report"
  exit 1
fi
