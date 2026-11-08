#!/bin/bash
# Verify absolute pointer accuracy: ask for points, read back via the KWin probe.
set -u
fail=0
for xy in "0 0" "100 100" "959 539" "1820 980" "1919 1079" "700 400" "1500 200"; do
  set -- $xy
  ssh -i ~/.ssh/proxmox -o StrictHostKeyChecking=no -o ConnectTimeout=5 root@192.168.0.11 \
    "python3 /root/gravaai-pointer.py move $1 $2" >/dev/null 2>&1
  sleep 0.45
  got=$(ssh -o StrictHostKeyChecking=no root@192.168.0.165 'cut -f1 /run/gravaai-test/cursor')
  if [ "$got" = "$1,$2" ]; then
    echo "OK   asked $1,$2 got $got"
  else
    echo "FAIL asked $1,$2 got $got"
    fail=$((fail+1))
  fi
done
echo "failures=$fail"
