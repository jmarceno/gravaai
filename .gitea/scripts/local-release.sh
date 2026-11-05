#!/usr/bin/env bash
# Runs the release workflow's build steps locally in Docker, the way the Gitea runner does:
# clean checkout of HEAD, builder image, container-build.sh, asset collection. Publishing
# is not simulated. Output lands in $OUT (default: ./dist-local) and logs in $LOG.
#
# Usage: .gitea/scripts/local-release.sh [version]     (default: pom version)
# Env:   MEMORY=12g  CPUS=4  JOBS=4  LIMIT=40m  OUT=...  WORK=...  LOG=...
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="${1:-$(sed -nE '0,/<version>([^<]+)<\/version>/s//\1/p' "$repo/java/pom.xml" | head -n1 | tr -d '[:space:]')}"
WORK="${WORK:-/tmp/gravaai-local-release}"
OUT="${OUT:-$repo/dist-local}"
LOG="${LOG:-$WORK/build.log}"
MEMORY="${MEMORY:-12g}"
CPUS="${CPUS:-4}"
export JOBS="${JOBS:-4}"
LIMIT="${LIMIT:-40m}"   # hard cap for the whole build; a first run (cold caches) needs the most

rm -rf "$WORK/checkout"
mkdir -p "$WORK/checkout" "$OUT"
git -C "$repo" archive HEAD | tar -x -C "$WORK/checkout"   # committed files only, like the runner
cd "$WORK/checkout"

image=gravaai-release-builder:22.04
timeout --foreground "$LIMIT" docker build -t "$image" -f .gitea/builder/Containerfile .gitea/builder
timeout --foreground "$LIMIT" .gitea/scripts/container-build.sh "$image" java/dist \
  --memory "$MEMORY" --memory-swap "$MEMORY" --cpus "$CPUS" -e JOBS \
  -v gravaai-release-native:/var/cache/gravaai-native \
  -v gravaai-release-m2:/root/.m2 \
  -- .gitea/scripts/build-release.sh "$version" 2>&1 | tee "$LOG"

test -s java/dist/Gravaai-linux-x64.AppImage
cp java/dist/Gravaai-linux-x64.AppImage "$OUT/Gravaai-$version-linux-x64.AppImage"
(cd "$OUT" && sha256sum ./*.AppImage > SHA256SUMS)
echo "local-release: ok -> $OUT"
