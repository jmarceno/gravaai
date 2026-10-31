#!/usr/bin/env bash
#
# Trim the GraalJS ICU4J jar to the locales Gravaai ships: root (ICU fallback),
# en and pt. The jar the polyglot dependency pulls in carries CLDR data for
# every locale (~18 MB stored, almost all of it under impl/data/icudata); the
# app UI is English and Intl consumers (user extensions) can only be expected
# to use the languages the app itself ships. Data outside en/pt falls back to
# root at run time — Intl keeps working, it formats with root/English data.
#
# What is kept, verified after the trim and gated again by
# tools/verify-icu-trim.sh during packaging:
#   - everything at the top of icudata/ (main date/number bundles per locale,
#     plus the shared singletons: zoneinfo64, supplementalData, likelySubtags,
#     numberingSystems, plurals, metaZones, ...)
#   - inside every data subdirectory: root.res, en.res (with en_001, en_150,
#     en_US), pt.res (with pt_PT), and the shared tables: res_index.res,
#     pool.res, ucadata.icu (collation), supplementalData.res (currency meta
#     lives in curr/), break-iteration rules (*.brk)
#   - dropped: every other locale's subdirectory data and the CJK/Southeast
#     Asian segmenter dictionaries (*.dict) — Intl.Segmenter degrades for
#     those scripts, everything else falls back to root
#
# Usage: ./trim-icu.sh --jar <icu4j-*.jar> --work-dir <scratch dir>
set -euo pipefail

JAR=""
WORK=""
while [ $# -gt 0 ]; do
	case "$1" in
		--jar) JAR="$2"; shift 2 ;;
		--work-dir) WORK="$2"; shift 2 ;;
		*) echo "trim-icu.sh: unknown argument: $1" >&2; exit 64 ;;
	esac
done
for required in JAR WORK; do
	eval "value=\${$required}"
	if [ -z "$value" ]; then
		echo "trim-icu.sh: --$(echo "$required" | tr 'A-Z_' 'a-z-') is required" >&2
		exit 64
	fi
done
if [ ! -f "$JAR" ]; then
	echo "trim-icu.sh: ICU4J jar not found: $JAR" >&2
	exit 1
fi

DATA_PREFIX="org/graalvm/shadowed/com/ibm/icu/impl/data/icudata"
MARKER="META-INF/gravaai-icu-trimmed"

if unzip -l "$JAR" 2>/dev/null | grep -q "$MARKER"; then
	echo "trim-icu.sh: $JAR is already trimmed — nothing to do"
	exit 0
fi

before="$(stat -c %s "$JAR")"
rm -rf "$WORK"
mkdir -p "$WORK/stage"
unzip -q "$JAR" -d "$WORK/stage"
DATA="$WORK/stage/$DATA_PREFIX"
if [ ! -d "$DATA" ]; then
	echo "trim-icu.sh: unexpected ICU layout (no $DATA_PREFIX) — did the GraalVM version change?" >&2
	exit 1
fi

# Snapshot every file the keep-rule promises to retain, so the post-trim
# check verifies the rule against this jar's real layout instead of a
# hard-coded list that the next ICU build may not match.
keep_snapshot="$(cd "$DATA" && find . -type f \( \
	-name 'root.res' -o -name 'res_index.res' -o -name 'pool.res' -o \
	-name 'ucadata.icu' -o -name 'supplementalData.res' -o \
	-name 'en.res' -o -name 'en_001.res' -o -name 'en_150.res' -o -name 'en_US.res' -o \
	-name 'pt.res' -o -name 'pt_BR.res' -o -name 'pt_PT.res' \) -printf '%P\n' | sort)"
shared_singletons="res_index.res zoneinfo64.res supplementalData.res numberingSystems.res plurals.res metaZones.res"
for singleton in $shared_singletons; do
	if [ ! -f "$DATA/$singleton" ]; then
		echo "trim-icu.sh: expected shared singleton not in the jar: $DATA_PREFIX/$singleton — did the GraalVM version change?" >&2
		exit 1
	fi
done

# Top-level files stay (main bundles + shared singletons); only data inside
# the per-feature subdirectories is locale-specific enough to trim.
while IFS= read -r -d '' file; do
	base="$(basename "$file")"
	case "$base" in
		root.res|en.res|en_001.res|en_150.res|en_US.res|pt.res|pt_BR.res|pt_PT.res|res_index.res|pool.res|ucadata.icu|supplementalData.res) ;;
		*.dict) rm -f "$file" ;;
		*.res) rm -f "$file" ;;
		# break-iteration rules (*.brk) and any other shared artifact stay
	esac
done < <(find "$DATA" -mindepth 2 -type f -print0)

fail=0
# Every file the rule keeps must still be there.
while IFS= read -r kept; do
	if [ ! -f "$DATA/$kept" ]; then
		echo "trim-icu.sh: keep-rule file lost during trim: $DATA_PREFIX/$kept" >&2
		fail=1
	fi
done <<<"$keep_snapshot"
# A few known per-locale artifacts must be gone, so a layout change cannot
# silently turn the trim into a no-op.
for gone in coll/zh.res brkitr/cjdict.dict zone/de.res; do
	if [ -f "$DATA_PREFIX/$gone" ]; then
		echo "trim-icu.sh: per-locale data survived the trim: $DATA_PREFIX/$gone" >&2
		fail=1
	fi
done
if [ "$fail" -ne 0 ]; then
	exit 1
fi

date -u +"trimmed=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$WORK/stage/$MARKER"
( cd "$WORK/stage" && zip -q -r -X "$WORK/icu4j-trimmed.jar" . )
mode="$(stat -c %a "$JAR")"
mv "$WORK/icu4j-trimmed.jar" "$JAR"
chmod "$mode" "$JAR"
rm -rf "$WORK"

after="$(stat -c %s "$JAR")"
echo "trim-icu.sh: ICU4J locale data trimmed: $(numfmt --to=iec "$before") -> $(numfmt --to=iec "$after")"
