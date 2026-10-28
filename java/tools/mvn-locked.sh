#!/usr/bin/env bash
#
# Serialised Maven runner. Plan §1.1.2: never run more than one build or test JVM at a time, and
# cap memory. Every build in this repository must go through this script (or hold the same lock).
#
#   java/tools/mvn-locked.sh -q -DskipTests package
#   java/tools/mvn-locked.sh -pl gravaai-core -am test
#
set -euo pipefail
cd "$(dirname "$0")/.."
LOCK="${GRAVAAI_BUILD_LOCK:-/tmp/gravaai-maven.lock}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}"
exec flock "$LOCK" nice -n 10 ./mvnw -T1 "$@"
