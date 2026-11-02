#!/usr/bin/env python3
"""Ground-truth pointer position receiver for the KDE test VM.

The KWin probe script pushes workspace.cursorPos here over D-Bus, so every move we inject
with ydotool can be read back and compared with what we asked for.
"""
import os
import sys

from gi.repository import Gio, GLib

out_path = sys.argv[1] if len(sys.argv) > 1 else "/run/gravaai-test/cursor"

XML = """<node>
  <interface name='org.gravaai.CursorTest'>
    <method name='Position'>
      <arg type='s' name='tag' direction='in'/>
      <arg type='s' name='xy' direction='in'/>
    </method>
    <method name='Pid'>
      <arg type='i' name='pid' direction='out'/>
    </method>
  </interface>
</node>"""


def on_call(conn, sender, path, iface, method, params, invocation):
    if method == "Position":
        tag, xy = params.unpack()
        with open(out_path, "w") as f:
            f.write(f"{xy}\t{tag}\n")
    elif method == "Pid":
        invocation.return_value(GLib.Variant("(i)", (os.getpid(),)))
        return
    invocation.return_value(None)


def main() -> None:
    node = Gio.DBusNodeInfo.new_for_xml(XML)
    conn = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    conn.register_object("/CursorTest", node.interfaces[0], on_call, None, None)
    Gio.bus_own_name_on_connection(
        conn, "org.gravaai.CursorTest", Gio.BusNameOwnerFlags.NONE, None, None
    )
    print("ready", flush=True)
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()
