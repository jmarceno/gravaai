#!/usr/bin/env bash
# Runs inside the builder container at /workspace: native libraries, then the AppImage.
# Usage: build-release.sh <version>
set -euo pipefail
version="${1:?version required}"
export GRAVAAI_NATIVE_CACHE=/var/cache/gravaai-native
export JOBS="${JOBS:-4}"
mkdir -p "$GRAVAAI_NATIVE_CACHE"
cd java
native/build-native-helpers.sh
native/build-ffmpeg.sh
native/build-whisper.sh
# Stamp the release version into every pom (the packaged app reports it) and McpMain's fallback.
old="$(sed -nE '0,/<version>([^<]+)<\/version>/s//\1/p' pom.xml | head -n1 | tr -d '[:space:]')"
if [[ "$old" != "$version" ]]; then
  find . -name pom.xml -not -path './native/*' -exec sed -i "s#<version>$old</version>#<version>$version</version>#" {} +
  sed -i "s#\"$old\"#\"$version\"#" gravaai-app/src/main/java/dev/gravaai/app/mcp/McpMain.java
fi
./mvnw -B -T1 -DskipTests -Pdist package \
  -Dgravaai.jdk=/opt/toolchain/jdk -Dgravaai.javafx.jmods=/opt/toolchain/javafx-jmods \
  -Dgravaai.build.root=/tmp/gravaai-targets
