#!/bin/bash
# Runs as root on the VM. Restarts the extracted dev build (/opt/gvapp) inside the user's systemd session.
# Usage: launch-dev.sh [--fresh]
export XDG_RUNTIME_DIR=/run/user/1000 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus
U="su jmarceno -c"
PROFILE=/home/jmarceno/Gravaai-Test/profile
$U "XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS=$DBUS_SESSION_BUS_ADDRESS systemctl --user stop gravaai-test.service gravaai-dev.service" 2>/dev/null
sleep 1
for p in $(pgrep -x java) $(pgrep -f '^/opt/gvapp'); do kill -9 $p 2>/dev/null; done
/opt/gravaai-test/cleanup.sh >/dev/null
cp /opt/gvbin/gravaai-* /opt/gvapp/usr/lib/native/linux-x64/
[ "${1:-}" = "--fresh" ] && rm -rf $PROFILE
$U "XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS=$DBUS_SESSION_BUS_ADDRESS systemctl --user reset-failed gravaai-dev.service" 2>/dev/null
$U "XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS=$DBUS_SESSION_BUS_ADDRESS systemd-run --user --unit=gravaai-dev.service --collect \
  -p Environment=GRAVAAI_USER_DATA=$PROFILE -p Environment=APPDIR=/opt/gvapp -p Environment=GRAVAAI_NO_DESKTOP_INTEGRATION=1 \
  /opt/gvapp/AppRun"
for i in $(seq 1 40); do [ -S /run/user/1000/gravaai/control.sock ] && break; sleep 0.5; done
ls /run/user/1000/gravaai/ 2>&1
