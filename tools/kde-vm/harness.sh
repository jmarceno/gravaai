#!/bin/bash
# KDE VM test harness: pointer ground truth via a KWin script, injected input via QMP tablet.
set -u
RUNDIR=/run/gravaai-test
CURSOR=$RUNDIR/cursor

user() {
  su - jmarceno -c "XDG_RUNTIME_DIR=/run/user/1000 \
DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus \
WAYLAND_DISPLAY=wayland-0 DISPLAY=:0 HOME=/home/jmarceno $*"
}

kwin() {
  user "gdbus call --session --dest org.kde.KWin --object-path /Scripting $*"
}

case "${1:-help}" in
start)
  mkdir -p $RUNDIR; chown jmarceno:jmarceno $RUNDIR; chmod 777 $RUNDIR
  pkill -f 'cursor-recv' 2>/dev/null
  sleep 0.5
  setsid su - jmarceno -c "XDG_RUNTIME_DIR=/run/user/1000 \
DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus \
exec python3 /opt/gravaai-test/cursor-recv.py $CURSOR" </dev/null >$RUNDIR/recv.log 2>&1 &
  sleep 2
  kwin --method org.kde.kwin.Scripting.unloadScript gravaai-probe >/dev/null 2>&1
  sleep 0.4
  kwin --method org.kde.kwin.Scripting.loadScript /opt/gravaai-test/kwin-probe.js gravaai-probe >/dev/null 2>&1
  kwin --method org.kde.kwin.Scripting.start 2>&1
  sleep 1.5
  echo "receiver: $(cat $RUNDIR/recv.log)"
  echo "cursor  : $(cat $CURSOR 2>/dev/null || echo none)"
  ;;
pos)
  cat $CURSOR 2>/dev/null || echo none
  ;;
stop)
  kwin --method org.kde.kwin.Scripting.unloadScript gravaai-probe >/dev/null 2>&1
  pkill -f 'cursor-recv' 2>/dev/null
  echo stopped
  ;;
shot)
  user "spectacle -b -n -o /tmp/gshot.png" >/dev/null 2>&1
  cp /tmp/gshot.png /run/gravaai-test/shot.png
  echo /run/gravaai-test/shot.png
  ;;
*) echo "usage: $0 {start|pos|stop|shot}" >&2; exit 2 ;;
esac
