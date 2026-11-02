#!/bin/bash
# Local driver for the KubuntuDev-KDE VM (see README.md). Runs on your machine, reaches the VM
# over SSH and the Proxmox host's QMP socket for input and screenshots.
#
#   vm.sh click|move|rclick X Y     QEMU tablet input, then prints where KWin says the pointer is
#   vm.sh tap KEY...                keys one after another (QEMU key names: ret, esc, a, f9 ...)
#   vm.sh combo KEY...              a chord: ctrl alt r
#   vm.sh shot NAME [X Y W H]       QEMU screendump (optionally cropped) -> $VM_SHOT_DIR/NAME.png
#   vm.sh pos                       pointer position as KWin reports it
#   vm.sh sh CMD...                 run a command on the VM as root
set -u
VM=${VM:-root@192.168.0.145}
PVE=${PVE:-root@192.168.0.11}
VMID=${VMID:-2002}
OUT=${VM_SHOT_DIR:-$PWD}
cmd=${1:-help}; shift || true
case $cmd in
  click|move|rclick|tap|combo)
    ssh -o BatchMode=yes "$PVE" python3 /root/gravaai-pointer.py "$cmd" "$@"
    sleep 0.3
    ssh "$VM" /opt/gravaai-test/harness.sh pos ;;
  pos) ssh "$VM" /opt/gravaai-test/harness.sh pos ;;
  shot)
    name=${1:-shot}; shift || true
    crop=""
    [ $# -eq 4 ] && crop="pamcut $1 $2 $3 $4 /tmp/vm.ppm |"
    ssh "$PVE" "qm monitor $VMID <<< 'screendump /tmp/vm.ppm' >/dev/null 2>&1; ${crop:-cat /tmp/vm.ppm |} pnmtopng > /tmp/vm.png"
    scp -q "$PVE:/tmp/vm.png" "$OUT/$name.png" && echo "$OUT/$name.png" ;;
  sh) ssh "$VM" "$@" ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
