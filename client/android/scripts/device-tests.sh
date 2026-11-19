#!/bin/bash
# Instrumented tests on the CI emulator. adb sometimes loses the emulator right after boot ("No connected devices!"):
# restart adb and try once more. On failure, say why as annotations (the job's logs need a login): Gradle's error,
# the runner image, Android's crash buffer and the system log's errors around it.
# usage: android/scripts/device-tests.sh
set -uo pipefail
log=${RUNNER_TEMP:-/tmp}/android-device-tests.log
oneline() { tr '\n' ' ' | tr -s ' ' | cut -c1-"$1"; }

tests() { ./gradlew :android:connectedDebugAndroidTest --console=plain 2>&1 | tee -a "$log"; }

report() {
  echo "::error title=Gradle::$(grep -A10 'What went wrong' "$log" | head -12 | oneline 900)"
  echo "::notice title=Runner::image ${ImageOS:-?} ${ImageVersion:-?}, kernel $(uname -r), emulator $(${ANDROID_HOME:-}/emulator/emulator -version 2>/dev/null | head -1)"
  echo "::error title=Android crash buffer::$(adb logcat -d -b crash 2>&1 | grep -v $'^\s*at \|\tat ' | tail -30 | oneline 3500)"
  echo "::error title=Android system errors::$(adb logcat -d -b main,system,events 2>&1 | grep -E ' [EF] |lowmemorykiller|lmkd|am_crash|watchdog|SIGSEGV|SIGABRT|Out of memory' | tail -40 | oneline 3500)"
  echo "::error title=App exceptions::$(adb logcat -d 2>&1 | grep -E 'AndroidRuntime|Exception|Error:|FATAL' | grep -iE 'racha|AndroidRuntime|webview|chromium' | grep -v $'^\s*at \|\tat ' | tail -40 | oneline 3500)"
  echo "::error title=WebView::$(adb shell dumpsys webviewupdate 2>&1 | head -12 | oneline 900) / $(adb logcat -d 2>&1 | grep -iE 'cr_|chromium|WebViewFactory' | grep -E ' [EWF] ' | tail -15 | oneline 2000)"
  echo "::error title=Kernel::$(adb shell dmesg 2>&1 | grep -iE 'oom|killed|panic|segfault|error' | tail -15 | oneline 2000)"
}

tests && exit 0
adb kill-server; adb start-server; adb wait-for-device
tests && exit 0
report
exit 1
