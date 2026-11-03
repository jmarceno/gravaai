// Polls the global pointer position and reports changes to the gravaai cursor helper
// (org.gravaai.GnomeCursor) over D-Bus - the GNOME counterpart of the KWin cursor
// bridge script. global.get_pointer() is the only way code inside gnome-shell can read
// the global position; no signal carries it. One tick per frame at 60 Hz: clicks come
// from evdev in the helper, so a slower poll attributed a quick move-then-click to the
// position before the move. An unchanged position is resent once a second only, so a
// helper started under a still pointer still gets its first position (and READY).

import GLib from 'gi://GLib';
import Gio from 'gi://Gio';

const POLL_MS = 16;
const RESEND_TICKS = 60;

export default class GravaaiCursorBridgeExtension {
    enable() {
        this._lastX = -1;
        this._lastY = -1;
        this._idleTicks = 0;
        this._dbus = Gio.DBus.session;
        this._timer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, POLL_MS, () => this._push());
        this._push();
    }

    disable() {
        if (this._timer !== null) {
            GLib.source_remove(this._timer);
            this._timer = null;
        }
    }

    _push() {
        const [x, y] = global.get_pointer();
        this._idleTicks++;
        if (x !== this._lastX || y !== this._lastY || this._idleTicks >= RESEND_TICKS) {
            this._idleTicks = 0;
            this._lastX = x;
            this._lastY = y;
            // Send without waiting for the reply. A synchronous call would block this
            // timer for a whole round trip per frame, and the helper only needs the
            // position - it never uses the reply for anything.
            this._dbus.call('org.gravaai.GnomeCursor', '/org/gravaai/GnomeCursor',
                'org.gravaai.GnomeCursor', 'Position',
                new GLib.Variant('(ss)', [String(x), String(y)]),
                null, Gio.DBusCallFlags.NO_AUTO_START, 100, null,
                (source, result) => {
                    try {
                        source.call_finish(result);
                    } catch (error) {
                        // The helper is gone (recording stopped or crashed): stay quiet
                        // and keep polling, so the next recording picks up again.
                    }
                });
        }
        return GLib.SOURCE_CONTINUE;
    }
}
