#!/usr/bin/env bash
# Runs inside the builder container at /workspace: native libraries, then the AppImage.
# Usage: build-release.sh <version>
set -euo pipefail
version="${1:?version required}"
export GRAVAAI_NATIVE_CACHE=/var/cache/gravaai-native
export JOBS="${JOBS:-4}"
mkdir -p "$GRAVAAI_NATIVE_CACHE/out"
cd java
# The native scripts stage their output in java/native/build and skip work when a matching build
# is already there. The checkout is fresh every run, so restore that directory from the cache
# volume and save it back: FFmpeg and whisper.cpp then build once and are reused until their pins
# change. (A symlink would not do: package-dist.sh walks it with find, which does not follow it.)
mkdir -p native/build
cp -a "$GRAVAAI_NATIVE_CACHE/out/." native/build/
step() { local t=$SECONDS; "$@"; echo "[build-release] $(basename "$1") took $((SECONDS - t))s"; }
step native/build-native-helpers.sh
step ../.gitea/scripts/fetch-ffmpeg.sh native/build   # prebuilt GPU-enabled FFmpeg, not compiled
step native/build-whisper.sh
# ONNX Runtime and the bundled segmentation/detection models. Without this step the release payload
# only carries them when the cache volume happens to already contain a previous staging.
step native/build-onnxruntime.sh
rm -rf "$GRAVAAI_NATIVE_CACHE/out" && cp -a native/build "$GRAVAAI_NATIVE_CACHE/out"
# Stamp the release version into every pom (the packaged app reports it) and McpMain's fallback.
old="$(sed -nE '0,/<version>([^<]+)<\/version>/s//\1/p' pom.xml | head -n1 | tr -d '[:space:]')"
if [[ "$old" != "$version" ]]; then
  find . -name pom.xml -not -path './native/*' -exec sed -i "s#<version>$old</version>#<version>$version</version>#" {} +
  sed -i "s#\"$old\"#\"$version\"#" gravaai-app/src/main/java/dev/gravaai/app/mcp/McpMain.java
fi
# package-dist.sh trains the AOT cache by starting the real app for a few seconds: give it an X
# display (Xvfb, software GL) - the container has no desktop.
step xvfb-run -a -s "-screen 0 1920x1080x24" ./mvnw -B -T1 -DskipTests -Pdist package \
  -Dgravaai.jdk=/opt/toolchain/jdk -Dgravaai.javafx.jmods=/opt/toolchain/javafx-jmods \
  -Dgravaai.build.root=/tmp/gravaai-targets
