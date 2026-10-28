#!/usr/bin/env bash
#
# Generates the media fixtures the gravaai-media wrapper tests decode (plan §5.7, §6.1):
#
#   java/parity/fixtures/media/h264-1080p60.mp4       2 s, 1080p60, H.264, faststart
#   java/parity/fixtures/media/vp9-720p30.webm        2 s, 720p30, VP9
#   java/parity/fixtures/media/aac-48k-stereo.m4a     2 s, 48 kHz stereo AAC
#   java/parity/fixtures/media/rotated-phone.mp4      2 s, 1080x1920 sensor, display matrix 90
#   java/parity/fixtures/media/av-sync-2min.mp4       120 s, 720p30, H.264+AAC stereo, 1 s A/V
#                                                     sync markers (red flash + 1 kHz/1.5 kHz tick)
#
# The clips are short, deterministic and small enough to commit. The moving white box makes
# frame content, seek targets and rotation verifiable in the tests.
#
# Which encoder produces what:
#   * H.264 / AAC / the rotated clip: our own bundled FFmpeg CLI
#     (java/native/build/ffmpeg/bin/ffmpeg, java/native/build-ffmpeg.sh).
#   * VP9: the bundled build deliberately has no VP9 encoder (plan §5.7 lists only libx264,
#     aac, png and gif as encoders). The VP9 *decode* path is required because Electron
#     recordings are WebM/VP9, so this one fixture is produced with a host FFmpeg that has
#     libvpx-vp9 (the repo's ffmpeg-static binary, the same source the Electron build used for
#     its flac fixture). Set HOST_FFMPEG to override.
#   * The rotated clip writes its display matrix with our CLI's -display_rotation input option
#     plus a -c copy remux; the display matrix is then read by MediaProbe/VideoDecoder.
#
# Usage: ./make-fixtures.sh [--force]
#   FFMPEG       bundled ffmpeg CLI (default java/native/build/ffmpeg/bin/ffmpeg)
#   HOST_FFMPEG  host ffmpeg with libvpx-vp9 (default: repo node_modules/ffmpeg-static/ffmpeg)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
java_root="$(cd "$here/.." && pwd)"
out="$java_root/parity/fixtures/media"
ffmpeg="${FFMPEG:-$here/build/ffmpeg/bin/ffmpeg}"
host_ffmpeg="${HOST_FFMPEG:-$repo_root/node_modules/ffmpeg-static/ffmpeg}"
force=0
[[ "${1:-}" == "--force" ]] && force=1

say() { printf '[make-fixtures] %s\n' "$*"; }
die() { printf '[make-fixtures] error: %s\n' "$*" >&2; exit 1; }

[[ -x "$ffmpeg" ]] || die "bundled ffmpeg not found: $ffmpeg (run java/native/build-ffmpeg.sh)"
mkdir -p "$out"

should_build() {
	local file="$out/$1"
	[[ "$force" -eq 1 || ! -s "$file" ]]
}

# A dark background with a white 240x160 box that travels left to right over the clip: every
# frame differs, and the box position at time t is (t/2)*(W-240). 2 s duration.
pattern() {
	local width="$1" height="$2" fps="$3"
	printf '%s' "-f lavfi -i color=c=0x101820:s=${width}x${height}:r=$fps:d=2 -f lavfi -i color=c=white:s=240x160:r=$fps:d=2"
}

pattern_filter() {
	local width="$1" height="$2"
	printf '%s' "[0:v][1:v]overlay=x='(t/2)*($width-240)':y='($height-160)/2':shortest=1"
}

if should_build "h264-1080p60.mp4"; then
	say "encoding h264-1080p60.mp4 with the bundled CLI"
	# shellcheck disable=SC2046
	"$ffmpeg" -y -hide_banner -loglevel error \
		$(pattern 1920 1080 60) \
		-filter_complex "$(pattern_filter 1920 1080),format=yuv420p[v]" \
		-map "[v]" -c:v libx264 -preset ultrafast -crf 18 -g 60 \
		-movflags +faststart -an "$out/h264-1080p60.mp4"
fi

if should_build "vp9-720p30.webm"; then
	[[ -x "$host_ffmpeg" ]] || die "host ffmpeg not found: $host_ffmpeg (needed for the VP9 fixture; set HOST_FFMPEG)"
	say "encoding vp9-720p30.webm with the host CLI (no VP9 encoder is bundled)"
	# shellcheck disable=SC2046
	"$host_ffmpeg" -y -hide_banner -loglevel error \
		$(pattern 1280 720 30) \
		-filter_complex "$(pattern_filter 1280 720),format=yuv420p[v]" \
		-map "[v]" -c:v libvpx-vp9 -b:v 2M -deadline good -cpu-used 4 -an \
		"$out/vp9-720p30.webm"
fi

if should_build "aac-48k-stereo.m4a"; then
	say "encoding aac-48k-stereo.m4a with the bundled CLI"
	"$ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i "sine=frequency=440:sample_rate=48000:duration=2" \
		-f lavfi -i "sine=frequency=660:sample_rate=48000:duration=2" \
		-filter_complex "[0:a][1:a]amix=inputs=2:duration=longest:normalize=0[a]" \
		-map "[a]" -ac 2 -c:a aac -b:a 192k "$out/aac-48k-stereo.m4a"
fi

# The manual P2 A/V-sync gate fixture: 120.000 s of testsrc2 with a red flash for the first 50 ms
# of every second and a matching stereo audio tick (1 kHz left, 1.5 kHz right). Watching the flash
# and hearing the tick at 0/60/120 s (and after pause/seek/scrub/speed changes) detects drift.
if should_build "av-sync-2min.mp4"; then
	say "encoding av-sync-2min.mp4 with the bundled CLI"
	# The bundled CLI only ships the filters the wrapper tests need (no testsrc/drawbox), so the
	# moving box and the 1 Hz red flash are overlays and the audio tick is a volume gate.
	"$ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i "color=c=0x101820:s=1280x720:r=30:d=120" \
		-f lavfi -i "color=c=white:s=240x160:r=30:d=120" \
		-f lavfi -i "color=c=red:s=1280x720:r=30:d=120" \
		-f lavfi -i "sine=frequency=1000:sample_rate=48000:duration=120" \
		-f lavfi -i "sine=frequency=1500:sample_rate=48000:duration=120" \
		-filter_complex "[0:v][1:v]overlay=x='(t/120)*(1280-240)':y='(720-160)/2':shortest=1[base];[base][2:v]overlay=x=0:y=0:shortest=1:enable='lt(mod(t,1),0.05)'[v];[3:a][4:a]amerge=inputs=2,volume=volume='if(lt(mod(t,1),0.05),1,0)':eval=frame[a]" \
		-map "[v]" -map "[a]" -c:v libx264 -preset veryfast -crf 30 -g 60 -pix_fmt yuv420p \
		-c:a aac -b:a 128k -t 120 -movflags +faststart "$out/av-sync-2min.mp4"
fi

# Auto-captions fixture: known speech for the real-whisper gate (services/captions). espeak-ng is a
# host tool; the fixture is committed, so this only runs when it is missing.
if should_build "caption-speech.mp4"; then
	say "encoding caption-speech.mp4 with espeak-ng + the bundled CLI"
	tmp="$(mktemp -d)"
	espeak-ng -v en-us -s 150 -w "$tmp/speech.wav" \
		"This is a test of automatic captions. The quick brown fox jumps over the lazy dog."
	"$ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i "color=c=0x101820:s=1280x720:r=30:d=8" \
		-i "$tmp/speech.wav" \
		-map 0:v -map 1:a -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p \
		-c:a aac -b:a 128k -shortest -movflags +faststart "$out/caption-speech.mp4"
	rm -rf "$tmp"
fi

if should_build "rotated-phone.mp4"; then
	say "encoding rotated-phone.mp4 with the bundled CLI"
	tmp="$(mktemp -d)"
	trap 'rm -rf "$tmp"' EXIT
	# Sensor orientation is portrait 1080x1920; the display matrix says "rotate 90", so players
	# show it as landscape 1920x1080. The box moves down the portrait sensor.
	"$ffmpeg" -y -hide_banner -loglevel error \
		-f lavfi -i "color=c=0x101820:s=1080x1920:r=30:d=2" \
		-f lavfi -i "color=c=white:s=240x160:r=30:d=2" \
		-filter_complex "[0:v][1:v]overlay=x='(1080-240)/2':y='(t/2)*(1920-160)':shortest=1,format=yuv420p[v]" \
		-map "[v]" -c:v libx264 -preset ultrafast -crf 18 -g 30 -an "$tmp/portrait.mp4"
	"$ffmpeg" -y -hide_banner -loglevel error -display_rotation 90 \
		-i "$tmp/portrait.mp4" -c copy -movflags +faststart "$out/rotated-phone.mp4"
fi

say "fixtures in $out:"
sha256sum "$out"/*.mp4 "$out"/*.webm "$out"/*.m4a
ls -l "$out"
