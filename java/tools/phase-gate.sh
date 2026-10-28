#!/usr/bin/env bash
#
# Phase gate runner (plan §7.4, §6): one command that produces the evidence a phase report needs.
#
#   java/tools/phase-gate.sh [--skip-tests] [--runs N]
#
# 1. full reactor test suite (one JVM at a time, capped heap - plan §1.1.2)
# 2. -Pdist package: jlink runtime, jpackage app image, AOT cache, AppImage
# 3. startup budget check against the AppImage (5 runs; stop other builds first - numbers are only
#    meaningful on an idle machine, plan R8)
# 4. JavaFX screenshots into java/parity/ui-java (cosmic-screenshot) and the parity gallery
#
# Deliberately never touches GPU/hardware video (plan §1.1.1) and never runs Electron at the same
# time as the Java app (plan §7).
set -euo pipefail

REPO="$(cd "$(dirname "\${BASH_SOURCE[0]}")/../.." && pwd)"
RUNS=5
TESTS=1
while [ $# -gt 0 ]; do
	case "$1" in
		--skip-tests) TESTS=0; shift ;;
		--runs) RUNS="$2"; shift 2 ;;
		*) echo "phase-gate.sh: unknown argument $1" >&2; exit 64 ;;
	esac
done

cd "$REPO/java"
START="$(date +%s)"

if [ "$TESTS" = 1 ]; then
	echo "== full test suite =="
	./tools/mvn-locked.sh -B package > /tmp/gravaai-gate-tests.log 2>&1
	tail -25 /tmp/gravaai-gate-tests.log | sed -n '/Tests run/p'
	echo "tests: ok ($(grep -c 'Tests run' /tmp/gravaai-gate-tests.log) modules reported)"
fi

echo "== dist package =="
./tools/mvn-locked.sh -q -B -DskipTests -Pdist package > /tmp/gravaai-gate-dist.log 2>&1
grep -E 'package-dist.sh: (app image|AppImage)' /tmp/gravaai-gate-dist.log || true

echo "== startup budget =="
./tools/startup-check.sh --runs "$RUNS" /media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage | tail -8

echo "== java UI screenshots + gallery =="
./tools/parity/capture-java-ui.sh --binary /media/jmarceno/scratch/java-targets/gravaai-app/dist/gravaai/bin/gravaai || true
node tools/parity/gallery.mjs 1

echo
echo "phase-gate: done in $(( $(date +%s) - START ))s"
echo "  app image : /media/jmarceno/scratch/java-targets/gravaai-app/dist/gravaai"
echo "  AppImage  : /media/jmarceno/scratch/java-targets/gravaai-app/dist/Gravaai-linux-x64.AppImage"
echo "  gallery   : docs/java-rewrite/phase-1-ui.html"
echo "  report    : docs/java-rewrite/phase-1-report.md"
