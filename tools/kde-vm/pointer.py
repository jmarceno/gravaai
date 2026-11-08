#!/usr/bin/env python3
"""Absolute pointer + keyboard control for the KDE test VM, through QEMU's QMP socket.

The guest attaches a QEMU HID Tablet, which the kernel exposes as an ordinary absolute
pointer. Because that path looks like real hardware, KWin's "Remote Control" portal gate
(which intercepts and prompts for xdotool/ydotool synthetic input) never applies, and a
prompt cannot be dismissed by the very input it is blocking.
"""
import json
import socket
import sys
import time

SOCK = "/var/run/qemu-server/2004.qmp"
SCREEN_W, SCREEN_H = 1920, 1080
# The HID tablet reports 0..0x7fff across the whole framebuffer.
AXIS_MAX = 0x7FFF


class Qmp:
    """One-shot QMP connection. Each CLI invocation opens and closes its own."""

    def __init__(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(SOCK)
        self.fh = self.sock.makefile("rwb")
        self.fh.readline()  # server greeting
        self.cmd("qmp_capabilities")

    def cmd(self, name, **arguments):
        msg = {"execute": name}
        if arguments:
            msg["arguments"] = arguments
        self.fh.write((json.dumps(msg) + "\n").encode())
        self.fh.flush()
        while True:
            line = self.fh.readline()
            if not line:
                raise RuntimeError("QMP connection closed")
            event = json.loads(line)
            if "event" in event:
                continue  # async notification, not our reply
            if "error" in event:
                raise RuntimeError(event["error"]["desc"])
            return event.get("return")

    def move(self, x, y):
        # Both axes must travel in a single input-send-event call: the tablet coalesces
        # per-event axis updates, so splitting them drops the y move.
        self.cmd("input-send-event", events=[
            {"type": "abs", "data": {"axis": "x", "value": round(x * AXIS_MAX / SCREEN_W)}},
            {"type": "abs", "data": {"axis": "y", "value": round(y * AXIS_MAX / SCREEN_H)}},
        ])

    def button(self, down, button="left"):
        self.cmd("input-send-event", events=[
            {"type": "btn", "data": {"down": down, "button": button}},
        ])

    def click(self, button="left"):
        self.button(True, button)
        self.button(False, button)

    def key(self, qcode, down):
        self.cmd("input-send-event", events=[
            {"type": "key", "data": {"down": down, "key": {"type": "qcode", "data": qcode}}},
        ])

    def tap(self, *qcodes):
        for code in qcodes:
            self.key(code, True)
            self.key(code, False)

    def combo(self, *qcodes):
        """Holds every key down together, then releases them in reverse: a real chord."""
        for code in qcodes:
            self.key(code, True)
        time.sleep(0.05)
        for code in reversed(qcodes):
            self.key(code, False)


def main(argv):
    q = Qmp()
    op = argv[1]
    if op == "move":
        q.move(int(argv[2]), int(argv[3]))
    elif op == "click":
        q.move(int(argv[2]), int(argv[3]))
        q.click()
    elif op == "rclick":
        q.move(int(argv[2]), int(argv[3]))
        q.click("right")
    elif op == "tap":
        q.tap(*argv[2:])
    elif op == "combo":
        q.combo(*argv[2:])
    elif op == "raw":
        q.cmd(argv[2], **json.loads(argv[3] if len(argv) > 3 else "{}"))
    else:
        raise SystemExit(f"unknown op {op}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
