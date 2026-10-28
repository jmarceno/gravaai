#!/usr/bin/env bash
#
# Builds the whisper.cpp runtime that the Java app bundles for auto-captions (plan §5.13,
# §7.1 P1.5). This is the bash port of scripts/build-whisper-runtime.mjs, scoped to the
# Linux x64 target the Java app ships on.
#
# Output layout (what -Pdist copies to <appimage>/lib/native/linux-x64/, plan §5.19.3):
#
#   java/native/build/linux-x64/whisper/whisper-cli
#   java/native/build/linux-x64/whisper/whisper-runtime.json   (version/platform/arch manifest)
#   java/native/build/linux-x64/whisper/<runtime shared libs if any>
#
# The same pin as the Electron build (whisper.cpp v1.8.4), Release build, tests and server
# off, static libs so the CLI is self-contained. No GPU: ggml's CUDA/Vulkan/Metal backends
# are not enabled on this Linux build.
#
# Usage: ./build-whisper.sh [--clean]
#   JOBS=4                 parallel build jobs (default 4)
#   GRAVAAI_NATIVE_CACHE   scratch work/download directory
#                          (default /media/jmarceno/scratch/gravaai-native)
set -euo pipefail

WHISPER_VERSION="v1.8.4"
ARCH="x64"
SOURCE_URL="https://github.com/ggml-org/whisper.cpp/archive/refs/tags/$WHISPER_VERSION.tar.gz"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cache="${GRAVAAI_NATIVE_CACHE:-/media/jmarceno/scratch/gravaai-native}"
downloads="$cache/downloads"
src="$cache/src"
out="$here/build/whisper"
logs="$cache/logs"
jobs="${JOBS:-4}"
archive="$downloads/whisper.cpp-$WHISPER_VERSION.tar.gz"
extract="$src/whisper.cpp-$WHISPER_VERSION"
build="$cache/build/whisper-linux-$ARCH"

say() { printf '[build-whisper] %s\n' "$*"; }
die() { printf '[build-whisper] error: %s\n' "$*" >&2; exit 1; }
elapsed() { printf '%dm%02ds' "$((SECONDS / 60))" "$((SECONDS % 60))"; }

run() {
	local name="$1"; shift
	if ! "$@" >>"$logs/$name.log" 2>&1; then
		printf '[build-whisper] FAILED: %s\n' "$*" >&2
		tail -n 40 "$logs/$name.log" >&2 || true
		exit 1
	fi
}

if [[ "${1:-}" == "--clean" ]]; then
	say "removing $build and $out"
	rm -rf "$build" "$out"
	shift
fi

for tool in cmake curl tar sha256sum; do
	command -v "$tool" >/dev/null 2>&1 || die "missing tool: $tool"
done
mkdir -p "$logs" "$downloads" "$src" "$out"

# Port of shouldSkipBuild(): manifest version+arch and the binary must agree.
manifest="$out/whisper-runtime.json"
if [[ -f "$manifest" && -x "$out/whisper-cli" ]]; then
	if grep -q "\"version\": \"$WHISPER_VERSION\"" "$manifest" \
		&& grep -q "\"arch\": \"$ARCH\"" "$manifest"; then
		say "whisper runtime $WHISPER_VERSION is already staged — nothing to do"
		exit 0
	fi
fi

# Port of ensureSourceTree().
if [[ ! -f "$extract/CMakeLists.txt" ]]; then
	if [[ ! -f "$archive" ]]; then
		say "downloading whisper.cpp $WHISPER_VERSION source"
		curl -fsSL --retry 3 --max-time 1800 -o "$archive.part" "$SOURCE_URL"
		mv "$archive.part" "$archive"
	fi
	say "source archive sha256: $(sha256sum "$archive" | cut -d' ' -f1)"
	rm -rf "$extract"
	mkdir -p "$extract"
	tar xzf "$archive" -C "$extract" --strip-components=1
fi
[[ -f "$extract/CMakeLists.txt" ]] || die "whisper.cpp source not found at $extract"

say "configuring whisper.cpp $WHISPER_VERSION for linux-$ARCH (-j$jobs)"
run configure cmake -S "$extract" -B "$build" \
	-DWHISPER_BUILD_TESTS=OFF \
	-DWHISPER_BUILD_SERVER=OFF \
	-DBUILD_SHARED_LIBS=OFF \
	-DCMAKE_BUILD_TYPE=Release \
	-DGGML_NATIVE=ON

say "building whisper-cli"
run build nice -n 10 cmake --build "$build" --config Release --parallel "$jobs"

# Port of findRuntimeArtifacts()/stageRuntimeArtifacts(): keep the whisper/ggml runtime
# artifacts from build/bin (static build: whisper-cli, plus libs if a backend is shared).
candidates=()
[[ -d "$build/bin" ]] && candidates+=("$build/bin")
(("${#candidates[@]}" > 0)) || die "built whisper runtime artifacts were not found (no $build/bin)"
staged=0
for dir in "${candidates[@]}"; do
	while IFS= read -r -d '' entry; do
		base="$(basename "$entry")"
		cp -f "$entry" "$out/$base"
		chmod 755 "$out/$base" 2>/dev/null || true
		staged=$((staged + 1))
	done < <(find "$dir" -maxdepth 1 -type f \( -iname 'whisper*' -o -iname 'ggml*' -o -iname 'libwhisper*' -o -iname 'libggml*' \) -print0)
done
((staged > 0)) || die "no whisper/ggml runtime artifact staged from $build/bin"
[[ -x "$out/whisper-cli" ]] || die "whisper-cli was not staged"

# Manifest shape ported from the mjs script.
cat >"$manifest" <<EOF
{
  "version": "$WHISPER_VERSION",
  "platform": "linux",
  "arch": "$ARCH",
  "binary": "whisper-cli"
}
EOF
{
	printf 'whisper_version=%s\n' "$WHISPER_VERSION"
	printf 'jobs=%s\n' "$jobs"
	printf 'build_time=%s\n' "$(elapsed)"
} >"$out/.build-info"

# Smoke: the CLI must start and print its usage. (No model is downloaded here.)
if ! "$out/whisper-cli" --help >"$logs/whisper-smoke.log" 2>&1; then
	if ! grep -qi 'usage' "$logs/whisper-smoke.log"; then
		tail -n 20 "$logs/whisper-smoke.log" >&2
		die "whisper-cli --help failed"
	fi
fi
say "whisper-cli staged -> $out/whisper-cli"
say "done in $(elapsed)"
ls -l "$out"
