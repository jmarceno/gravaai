#!/usr/bin/env bash
#
# Acceptance gate for the ICU4J trim (tools/trim-icu.sh). Runs a set of Intl
# calls through the real extension engine (GraalJS via the polyglot API, the
# same setup as ExtensionHost) on the packaged runtime and fails the build if
# any of them throws. A GraalVM bump that changes the ICU data layout or drops
# a file the trim kept by mistake fails here instead of shipping broken Intl.
#
# The expected results are pinned against the full-ICU jar (before trimming):
# pt-BR full date, pt-BR/en-US currency, pt-BR collation, de-DE date through
# the root fallback, word segmentation and PluralRules availability.
#
# Usage: ./verify-icu-trim.sh --javac <javac> --java <java> --lib-dir <jars dir> --work-dir <scratch dir>
set -euo pipefail

JAVAC=""
JAVA=""
LIB_DIR=""
WORK=""
while [ $# -gt 0 ]; do
	case "$1" in
		--javac) JAVAC="$2"; shift 2 ;;
		--java) JAVA="$2"; shift 2 ;;
		--lib-dir) LIB_DIR="$2"; shift 2 ;;
		--work-dir) WORK="$2"; shift 2 ;;
		*) echo "verify-icu-trim.sh: unknown argument: $1" >&2; exit 64 ;;
	esac
done
for required in JAVAC JAVA LIB_DIR WORK; do
	eval "value=\${$required}"
	if [ -z "$value" ]; then
		echo "verify-icu-trim.sh: --$(echo "$required" | tr 'A-Z_' 'a-z-') is required" >&2
		exit 64
	fi
done
for file in "$JAVAC" "$JAVA"; do
	if [ ! -x "$file" ]; then
		echo "verify-icu-trim.sh: not executable: $file" >&2
		exit 1
	fi
done
polyglot="$(ls "$LIB_DIR"/polyglot-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$polyglot" ]; then
	echo "verify-icu-trim.sh: no polyglot jar in $LIB_DIR" >&2
	exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK"
cat >"$WORK/IntlProbe.java" <<'PROBE'
import org.graalvm.polyglot.Context;
public class IntlProbe {
	public static void main(String[] args) {
		String[] probes = {
			"new Intl.DateTimeFormat('pt-BR',{dateStyle:'full'}).format(new Date('2026-01-31'))",
			"new Intl.NumberFormat('pt-BR',{style:'currency',currency:'BRL'}).format(1234.56)",
			"new Intl.Collator('pt-BR').compare('ação','agua')",
			"new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(1234.56)",
			"new Intl.DateTimeFormat('de-DE',{dateStyle:'long'}).format(new Date('2026-01-31'))",
			"Array.from(new Intl.Segmenter('en',{granularity:'word'}).segment('hello gravaai world')).length",
			"typeof Intl && typeof Intl.PluralRules",
			"'R$ 1.234,56'.normalize('NFC')"
		};
		try (Context ctx = Context.newBuilder("js")
				.option("engine.WarnInterpreterOnly", "false")
				.build()) {
			for (String probe : probes) {
				try {
					System.out.println("OK   " + probe + "  =>  " + ctx.eval("js", "(" + probe + ")"));
				} catch (Exception error) {
					System.out.println("FAIL " + probe + "  =>  "
							+ String.valueOf(error.getMessage()).replaceAll("\\s+", " "));
				}
			}
		}
	}
}
PROBE
classpath="$(find "$LIB_DIR" -maxdepth 1 -name '*.jar' | paste -sd:)"
"$JAVAC" -cp "$classpath" -d "$WORK/classes" "$WORK/IntlProbe.java"
"$JAVA" -cp "$classpath:$WORK/classes" IntlProbe | tee "$WORK/probe.out"

failures="$(grep -c '^FAIL' "$WORK/probe.out" || true)"
passed="$(grep -c '^OK' "$WORK/probe.out" || true)"
if [ "$failures" -ne 0 ] || [ "$passed" -lt 8 ]; then
	echo "verify-icu-trim.sh: Intl probe failed ($failures failures, $passed passes of 8)" >&2
	exit 1
fi
echo "verify-icu-trim.sh: all 8 Intl probes passed against the packaged runtime"
