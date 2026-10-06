# Source before building or probing binaries from an IDE shell.
sanitize_host_env() {
  local cleaned="" part rest="${LD_LIBRARY_PATH:-}"
  while [[ -n "$rest" ]]; do
    part="${rest%%:*}"
    if [[ "$rest" == *:* ]]; then rest="${rest#*:}"; else rest=""; fi
    case "$part" in ""|/tmp/.mount_*) continue ;; esac
    cleaned="${cleaned:+$cleaned:}$part"
  done
  if [[ -n "$cleaned" ]]; then export LD_LIBRARY_PATH="$cleaned"; else unset LD_LIBRARY_PATH; fi
  unset QT_PLUGIN_PATH QT_QPA_PLATFORM_PLUGIN_PATH QML2_IMPORT_PATH QML_IMPORT_PATH
}
