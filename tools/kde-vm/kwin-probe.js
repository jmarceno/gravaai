// Pushes pointer position changes to the D-Bus receiver in /opt/gravaai-test.
// A KWin script can read workspace.cursorPos but cannot write it, so this is a monitor only.
var timer = new QTimer();
var ticks = 0;
var lastX = -1, lastY = -1;

function push() {
    ticks++;
    var p;
    try {
        p = workspace.cursorPos;
    } catch (e) {
        if (ticks % 50 === 0) {
            callDBus("org.gravaai.CursorTest", "/CursorTest", "org.gravaai.CursorTest",
                     "Position", "err:" + e, "-1,-1");
        }
        return;
    }
    if (ticks % 50 === 0) {
        // Heartbeat proves the timer is alive even when the pointer does not move.
        callDBus("org.gravaai.CursorTest", "/CursorTest", "org.gravaai.CursorTest",
                 "Position", "tick" + ticks, String(p.x) + "," + String(p.y));
    }
    if (p.x === lastX && p.y === lastY) return;
    lastX = p.x;
    lastY = p.y;
    callDBus("org.gravaai.CursorTest", "/CursorTest", "org.gravaai.CursorTest",
             "Position", "move", String(p.x) + "," + String(p.y));
}

timer.interval = 40;
timer.timeout.connect(push);
timer.start();
push();
