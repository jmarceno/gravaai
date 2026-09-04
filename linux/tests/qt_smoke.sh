#!/usr/bin/env bash
# Offscreen Qt/QML contract gate. This does not need a tray host because the
# smoke harness deliberately skips the D-Bus worker; the final block verifies
# that a normal UI invocation is refused when no daemon/tray owns the bus.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LINUX="$ROOT/linux"
UI_BIN="${GRAVAAI_UI_BIN:-$LINUX/target/debug/gravaai-ui}"
[[ -x "$UI_BIN" ]] || {
  echo "Qt smoke: missing UI binary $UI_BIN (build with --features ui)" >&2
  exit 1
}

QML_MODULE_ROOT="$(find "$LINUX/target/debug/build" -type d \
  -path '*/out/qt-build-utils/qml_modules' -print -quit)"
[[ -n "$QML_MODULE_ROOT" ]] || {
  echo "Qt smoke: generated CXX-Qt module not found" >&2
  exit 1
}

mapfile -t QML_FILES < <(find "$LINUX/qml" -type f -name '*.qml' -print | sort)
qmllint -I "$QML_MODULE_ROOT" "${QML_FILES[@]}"
SCAN_JSON="$(mktemp)"
qmlimportscanner -rootPath "$LINUX/qml" -importPath "$QML_MODULE_ROOT" > "$SCAN_JSON"
for import_name in QtQuick QtQuick.Controls QtQuick.Layouts QtQuick.Dialogs QtQuick.Window io.github.jmarceno.gravaai; do
  grep -Fq '"name": "'"$import_name"'"' "$SCAN_JSON" || {
    echo "Qt smoke: qmlimportscanner missed $import_name" >&2
    exit 1
  }
done

for spec in 1332:820 960:640; do
  width="${spec%:*}"
  height="${spec#*:}"
  output="$(mktemp)"
  QT_QPA_PLATFORM=offscreen GRAVAAI_QML_SMOKE=1 \
    "$UI_BIN" --smoke-width="$width" --smoke-height="$height" >"$output" 2>&1
  if grep -Eiq 'ReferenceError|TypeError|Binding loop|Cannot assign|not a type|default property|module .* not installed|QML smoke geometry' "$output"; then
    echo "Qt smoke: runtime diagnostic at ${width}x${height}" >&2
    cat "$output" >&2
    exit 1
  fi
done

# A window without the daemon (and therefore without its tray) must fail
# before creating a Qt scene. dbus-run-session gives this check an isolated bus
# and cannot disturb the user's running desktop instance.
set +e
NO_DAEMON_OUTPUT="$(mktemp)"
dbus-run-session -- "$UI_BIN" --window >"$NO_DAEMON_OUTPUT" 2>&1
rc=$?
set -e
if [[ "$rc" -ne 73 ]]; then
  echo "Qt smoke: direct UI invocation returned $rc, expected 73" >&2
  cat "$NO_DAEMON_OUTPUT" >&2
  exit 1
fi

echo "Qt/QML smoke passed (1332x820, 960x640, no-daemon guard)."
