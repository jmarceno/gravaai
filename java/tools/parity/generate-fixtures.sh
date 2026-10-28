#!/usr/bin/env bash
#
# Generates the P2.1 golden-frame fixtures (plan §6.2, §7.2 P2.1).
#
#   java/tools/parity/generate-fixtures.sh
#
# Writes java/parity/fixtures/projects/*.gravaai, the cursor/keystroke telemetry sidecars and the
# image-annotation PNG next to the shared media, and java/parity/fixtures/capture-plan.json.
# The generator uses the production project normaliser/serialiser (gravaai-core), so every fixture
# loads in both the Electron reference app and the Java app.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
java_dir=$(cd "$here/../.." && pwd)
repo=$(cd "$java_dir/.." && pwd)

jdk=${GRAVAAI_JAVA_HOME:-}
if [ -z "$jdk" ]; then
	jdk=$(sed -n 's:.*<gravaai.jdk>\([^<]*\)</gravaai.jdk>.*:\1:p' "$java_dir/pom.xml" | head -1)
fi
if [ ! -x "$jdk/bin/javac" ]; then
	echo "generate-fixtures: JDK 27 not found at '$jdk' (set GRAVAAI_JAVA_HOME)" >&2
	exit 1
fi

core_classes="${GRAVAAI_CORE_CLASSES:-/media/jmarceno/scratch/java-targets/gravaai-core/classes}"
if [ ! -d "$core_classes" ]; then
	echo "generate-fixtures: compiling gravaai-core first (missing $core_classes)"
	(cd "$java_dir" && ./tools/mvn-locked.sh -q -B -DskipTests -pl gravaai-core -am package)
fi

m2="${HOME}/.m2/repository"
classpath="$core_classes"
while IFS= read -r jar; do
	classpath="$classpath:$jar"
done < <(find "$m2/tools/jackson" "$m2/com/fasterxml/jackson" -name '*.jar' 2>/dev/null | sort)
if ! printf '%s' "$classpath" | grep -q 'jackson-databind'; then
	echo "generate-fixtures: Jackson jars missing from $m2; run a Maven build first" >&2
	exit 1
fi

tool_dir="${GRAVAAI_FIXTURE_TOOL_DIR:-/media/jmarceno/scratch/java-targets/parity-fixtures}"
mkdir -p "$tool_dir/classes"
"$jdk/bin/javac" -cp "$classpath" -d "$tool_dir/classes" "$here/fixtures/GenerateFixtures.java"
"$jdk/bin/java" -cp "$tool_dir/classes:$classpath" GenerateFixtures "$repo"
