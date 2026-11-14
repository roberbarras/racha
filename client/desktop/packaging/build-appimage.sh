#!/bin/bash
# Builds Racha-<version>-x86_64.AppImage from the Compose app image (bundled JRE, jpackage launcher).
# usage: desktop/packaging/build-appimage.sh <appimagetool> <runtime-x86_64> [version]   (run from client/)
# appimagetool and its type2 runtime come from github.com/AppImage (appimagetool / type2-runtime releases).
# libmpv is taken from the system for now; bundling it is part of the release packaging (ADR-0009, H12).
set -euo pipefail
tool=$1; runtime=$2; version=${3:-0.0.1}
client_dir=$(cd "$(dirname "$0")/../.." && pwd)
build="$client_dir/desktop/build"
appdir="$build/appimage/Racha.AppDir"

"$client_dir/gradlew" -q -p "$client_dir" :desktop:packageAppImage :desktop:testClasses
rm -rf "$appdir"
mkdir -p "$appdir/usr/lib"
cp -a "$build/compose/binaries/main/app/racha" "$appdir/usr/lib/racha"

classpath=$("$client_dir/gradlew" -q --console=plain -p "$client_dir" :desktop:printTestRuntimeClasspath | tail -1)
java -cp "$classpath" io.github.roberbarras.racha.desktop.tools.RenderIconKt \
  "$client_dir/../design/brand/racha-icon.svg" "$appdir/racha.png" 256 2>/dev/null
ln -s racha.png "$appdir/.DirIcon"
install -m 755 "$client_dir/desktop/packaging/AppRun" "$appdir/AppRun"
install -m 644 "$client_dir/desktop/packaging/racha.desktop" "$appdir/racha.desktop"

ARCH=x86_64 "$tool" --appimage-extract-and-run --no-appstream --runtime-file "$runtime" \
  "$appdir" "$build/appimage/Racha-$version-x86_64.AppImage"
echo "$build/appimage/Racha-$version-x86_64.AppImage"
