#!/bin/bash
# Runs one spike JVM with guard rails. A GPU test once froze a developer desktop (see docs/spikes/h1-linux.md),
# so GUI experiments must go through this script:
#   - virtual display (Xvfb, no GPU) by default; "real" only after asking the desktop owner
#   - the JVM is started directly (no Gradle-forked child) inside a systemd scope with memory and CPU caps
#   - hard wall-clock limit: SIGTERM after <limitSeconds>, SIGKILL 5 s later; nothing may be left running
#
# usage: spikes/safe-run.sh <virtual|real> <limitSeconds> <module> <args...>
#   e.g. spikes/safe-run.sh virtual 60 linux-video embed "/path/video.mp4" 30 1280 720 no x11
set -u
target=$1; limit=$2; module=$3; shift 3
client_dir=$(cd "$(dirname "$0")/.." && pwd)
case $module in
  linux-video) main=io.github.roberbarras.racha.spike.video.MainKt ;;
  linux-webview) main=io.github.roberbarras.racha.spike.webview.MainKt ;;
  *) echo "unknown module $module" >&2; exit 2 ;;
esac
classpath=$("$client_dir/gradlew" -q --console=plain -p "$client_dir" ":spikes:$module:printRuntimeClasspath" | tail -1)

if [ "$target" = virtual ]; then
  Xvfb :97 -screen 0 1600x900x24 >/dev/null 2>&1 &
  xvfb=$!
  sleep 1
  export DISPLAY=:97
  unset XAUTHORITY
else
  read -r xdisplay xauth < <(pgrep -a Xwayland | head -1 | awk '{for(i=1;i<=NF;i++){if($i ~ /^:[0-9]+$/)d=$i; if($i=="-auth")a=$(i+1)}; print d, a}')
  export DISPLAY=$xdisplay XAUTHORITY=$xauth
fi
unset WAYLAND_DISPLAY

systemd-run --user --scope --quiet -p MemoryMax=1536M -p MemorySwapMax=0 -p CPUQuota=300% -- \
  timeout -s TERM -k 5 "$limit" \
  java -Xmx512m --enable-native-access=ALL-UNNAMED \
    --add-exports=java.desktop/sun.awt=ALL-UNNAMED --add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED \
    -cp "$classpath" "$main" "$@"
status=$?
[ "$target" = virtual ] && kill "$xvfb" 2>/dev/null

for pid in $(pgrep -x java); do
  if tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "$main"; then
    echo "LEFTOVER $pid killed" >&2
    kill -9 "$pid"
  fi
done
[ "$status" = 124 ] && echo "TIMEOUT: the run was killed; stop and investigate before running anything else" >&2
exit "$status"
