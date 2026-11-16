#!/bin/bash
# Builds Racha-<version>-x86_64.AppImage: the Compose app image (jlink'd JRE 25, jpackage launcher) plus libmpv and
# the libraries it needs, collected by linuxdeploy (which leaves out what must come from the host: glibc, GL/Vulkan
# drivers, X11/Wayland, ALSA… — the AppImage project's exclude list). ADR-0009.
#
# usage: desktop/packaging/build-appimage.sh <appimagetool> <runtime-x86_64> <linuxdeploy> [version]   (from client/)
# The tools come from github.com/AppImage (appimagetool, type2-runtime) and github.com/linuxdeploy/linuxdeploy.
# libmpv is the build machine's (`libmpv.so.2`, or MPV_LIB=/path/to/libmpv.so.2): CI builds on Ubuntu 24.04.
set -euo pipefail
tool=$1; runtime=$2; linuxdeploy=$3; version=${4:-0.0.1}
client_dir=$(cd "$(dirname "$0")/../.." && pwd)
build="$client_dir/desktop/build"
appdir="$build/appimage/Racha.AppDir"
mpv_lib=${MPV_LIB:-$("$(command -v ldconfig || echo /sbin/ldconfig)" -p | awk '/libmpv\.so\.2 / && !found { print $NF; found = 1 }')}
[ -f "$mpv_lib" ] || { echo "libmpv.so.2 not found (install libmpv2 or set MPV_LIB)" >&2; exit 2; }

"$client_dir/gradlew" -q -p "$client_dir" :desktop:packageAppImage :desktop:testClasses
rm -rf "$appdir"
mkdir -p "$appdir/usr/lib"
cp -a "$build/compose/binaries/main/app/racha" "$appdir/usr/lib/racha"

# libmpv and its dependencies, with an $ORIGIN run path so they find each other. Collected in a separate AppDir
# (linuxdeploy would also try to process the bundled JRE) and copied next to the app.
mpvdir="$build/appimage/mpv.AppDir"
rm -rf "$mpvdir"
"$linuxdeploy" --appimage-extract-and-run --appdir "$mpvdir" --library "$mpv_lib" >"$build/appimage/linuxdeploy.log" 2>&1 \
  || { tail -30 "$build/appimage/linuxdeploy.log" >&2; exit 3; }
cp -a "$mpvdir/usr/lib/." "$appdir/usr/lib/"
[ -f "$appdir/usr/lib/libmpv.so.2" ] || { echo "libmpv was not bundled" >&2; exit 3; }

classpath=$("$client_dir/gradlew" -q --console=plain -p "$client_dir" :desktop:printTestRuntimeClasspath | tail -1)
java -cp "$classpath" io.github.roberbarras.racha.desktop.tools.RenderIconKt \
  "$client_dir/../design/brand/racha-icon.svg" "$appdir/racha.png" 256 2>/dev/null
ln -sf racha.png "$appdir/.DirIcon"
install -m 755 "$client_dir/desktop/packaging/AppRun" "$appdir/AppRun"
install -m 644 "$client_dir/desktop/packaging/racha.desktop" "$appdir/racha.desktop"

ARCH=x86_64 "$tool" --appimage-extract-and-run --no-appstream --runtime-file "$runtime" \
  "$appdir" "$build/appimage/Racha-$version-x86_64.AppImage"
echo "$build/appimage/Racha-$version-x86_64.AppImage"
