#!/bin/bash
# Runs as jmarceno inside the user session: pprobe.sh <binary> <label> <seconds>
BIN=$1; L=$2; SECS=${3:-20}
D=/tmp/pp-$L; rm -rf $D; mkdir -p $D
( sleep $SECS; echo stop ) | "$BIN" Virtual-1 30 > >(wc -c > $D/bytes) 2> $D/err
echo "exit=$?" > $D/exit
