#!/bin/bash
# cprobe.sh <seconds>: runs the KWin cursor helper for <seconds>, timestamping each line.
( sleep ${1:-8} ) | /opt/gvbin/gravaai-kwin-cursor 2> /tmp/cprobe.err | while read -r l; do echo "$(date +%T.%N | cut -c1-12) $l"; done > /tmp/cprobe.out
