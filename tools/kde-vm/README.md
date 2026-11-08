# KDE VM test harness

Drives the `KubuntuDev-KDE` VM so the KDE/portal capture path can be tested by clicking the
real UI, and reads back where the pointer actually went so a click can be trusted.

Everything here is test scaffolding for the dev VM only. None of it ships.

## Why the obvious approaches fail

The VM runs **KWin on Wayland** (Plasma 6.7.5, `XDG_SESSION_TYPE=wayland`). That combination
defeats every synthetic-input tool that a normal desktop would accept, and the failure mode is
misleading: the move looks like it worked and the click silently does nothing.

| Attempt | What actually happens |
| --- | --- |
| `xdotool` (XWayland XTest) | The pointer moves at the X level, so `xdotool getmouselocation` is happy. KWin does **not** deliver the event to Wayland clients: it raises a modal **"Remote Control — xdotool is asking to control input devices"** prompt instead. That prompt cannot be answered with the very input it is blocking. |
| `ydotool` (uinput) | Detected as a synthetic device and gated by the same Remote Control prompt. Its `mousemove -a` is also only *approximately* absolute: it computes a delta and the result drifts with pointer acceleration. |
| KWin scripting | `workspace.cursorPos` is **read-only**; assigning to it throws or is ignored. Good for monitoring, useless for moving. |
| `wayland` client | A client cannot inject input into itself; no protocol exposes that to normal clients. |

The Remote Control prompt is the real obstacle. Approving it is a chicken-and-egg problem
unless the click comes from something KWin treats as genuine hardware.

## What works

**QEMU's HID Tablet**, driven over the Proxmox QMP socket. From the guest's point of view it is
an ordinary absolute pointing device (`/dev/input/event4`), so KWin's synthetic-input gate never
engages, and there is no prompt to answer. Coordinates are mapped linearly onto the tablet's
`0..0x7fff` axes and land pixel-exact.

Verified: asked `100,100 / 959,539 / 1820,980 / 1919,1079 / 700,400 / 1500,200`, read back all
exactly. The single exception is `0,0`, which KWin clamps to `1,1`.

## Layout

| File | Runs on | Purpose |
| --- | --- | --- |
| `pointer.py` | Proxmox host | Absolute move / click / key through the VM's QMP socket. |
| `cursor-recv.py` | VM, as session user | D-Bus receiver that stores the live pointer position in a file. |
| `kwin-probe.js` | VM | KWin script that pushes `workspace.cursorPos` to the receiver. |
| `harness.sh` | VM | Starts the receiver + probe, reads position, screenshots, stops. |
| `verify-pointer.sh` | local | Round-trip accuracy check: move, read back, compare. |
| `vm.sh` | local | One entry point: click/move/tap/combo, pointer readback, cropped QEMU screenshots. |
| `launch-dev.sh` | VM, as root | Restarts the extracted dev build in `/opt/gvapp` with fresh helpers from `/opt/gvbin`. |
| `pprobe.sh` | VM, as session user | Runs the portal capture helper alone and counts the bytes it streams. |
| `cprobe.sh` | VM, as session user | Runs the KWin cursor helper alone and timestamps every line. |

## Setup

Both machines need the files copied over; the two VMs are reached differently.

```bash
# Proxmox host (192.168.0.11) - pointer.py
scp -i ~/.ssh/proxmox tools/kde-vm/pointer.py root@192.168.0.11:/root/gravaai-pointer.py

# KDE VM (192.168.0.165, VM 2004) - the rest
scp tools/kde-vm/{cursor-recv.py,kwin-probe.js,harness.sh,launch-dev.sh,pprobe.sh,cprobe.sh} \
    root@192.168.0.165:/opt/gravaai-test/
ssh root@192.168.0.165 'chmod +x /opt/gravaai-test/*.sh'
```

Start the cursor readback once per VM boot:

```bash
ssh root@192.168.0.165 /opt/gravaai-test/harness.sh start
```

## Clicking

```bash
# On the Proxmox host. The VM is 1920x1080.
python3 /root/gravaai-pointer.py click 828 988    # move + left click
python3 /root/gravaai-pointer.py move 960 540     # just move
python3 /root/gravaai-pointer.py rclick 960 540   # right click
python3 /root/gravaai-pointer.py tap ret          # key by QEMU key name
python3 /root/gravaai-pointer.py combo ctrl alt r # keys held together (a chord)
```

From your machine, `tools/kde-vm/vm.sh` wraps all of this and reads the pointer back after every
input: `vm.sh click 828 988`, `vm.sh combo ctrl alt r`, `vm.sh shot hud 600 900 700 140`.

Read back where the pointer landed, and grab a screenshot:

```bash
ssh root@192.168.0.165 /opt/gravaai-test/harness.sh pos    # e.g. 828,988	move
ssh root@192.168.0.165 /opt/gravaai-test/harness.sh shot   # -> /run/gravaai-test/shot.png
```

## Gotchas

- **Both axes in one `input-send-event` call.** The tablet coalesces per-call axis updates, so
  sending x and y as separate calls drops the y movement. `pointer.py` already does this.
- **The QMP socket is root-only**: `/var/run/qemu-server/<vmid>.qmp`, reachable only from the
  Proxmox host. Do not look for it inside the guest.
- **`mousemove` at `0,0`** lands on `1,1`; aim one pixel in.
- **Prefer a screenshot over assumptions.** The failure mode of both blocked tools is a move
  that reports success and a click that never arrives. Confirm state visually before concluding
  an action worked.
- **KWin script `start` takes no argument.** It starts every loaded script. The id returned by
  `loadScript` cannot be passed to it (the introspection signature is misleading).
- **The receiver must run as the session user** (`jmarceno`) with that user's
  `DBUS_SESSION_BUS_ADDRESS`. Running it as root fails with "Error sending credentials".
- **Screenshots need the session env**: `spectacle -b -n -o` with `XDG_RUNTIME_DIR`,
  `DBUS_SESSION_BUS_ADDRESS` and `WAYLAND_DISPLAY` set. Under the wrong env it writes nothing
  or fails silently.

- **Screenshots: prefer QEMU's screendump** (`vm.sh shot`). It needs no session env, cannot be
  blocked by a modal, and `vm.sh shot name X Y W H` crops on the Proxmox host so a check costs a
  small image instead of the full 1920x1080 frame.
- **The first click on an unfocused window only focuses it.** If a click "did nothing", take a
  screenshot and click again before suspecting the app.
- **Chords need `combo`, not `tap`.** `tap ctrl alt r` presses the three keys one after another;
  `combo ctrl alt r` holds them together, which is what a global shortcut needs.
- **`/tmp` on the VM is a 3.9 GB tmpfs.** Raw frame dumps fill it and then the compiler fails with
  "No space left on device". Probe scripts count bytes instead of keeping frames.
- **`pgrep -f`/`pkill -f` over SSH can match the SSH command itself** and kill your own shell
  (exit 255). Match on `pgrep -x <comm>` or by PID.
- **Wait with a bound, never forever.** Every wait in these scripts has a timeout; a hung portal
  dialog must not stall the session.

## App under test

`harness.sh` does not launch the app. Two ways to run it inside the user's systemd session
(`systemd-run --user`, so it inherits the live KDE environment):

- **The AppImage as shipped**: the VM's `/home/jmarceno/launch.sh` (unit `gravaai-test`).
- **A dev build without repackaging**: extract an AppImage once to `/opt/gvapp`
  (`--appimage-extract`), then copy freshly built jars from
  `/media/jmarceno/scratch/java-targets/<module>/` into `/opt/gvapp/usr/lib/app/` and native
  helpers into `/opt/gvbin/`, and run `/opt/gravaai-test/launch-dev.sh [--fresh]` (unit
  `gravaai-dev`). `--fresh` wipes the test profile.

The native helpers are built **on the VM**, which has the PipeWire and D-Bus dev packages:
sync `java/native` to `/opt/gvsrc` and run
`GRAVAAI_NATIVE_OUT=/opt/gvbin bash build-native-helpers.sh` there. Copy `gravaai-portal-capture`
and `gravaai-kwin-cursor` back into `java/native/build/` before packaging; they need GLIBC 2.38,
the same as the COSMIC helpers built on Pop!_OS 24.04.

The portal remembers the shared screen in
`~/.local/state/gravaai/portal-restore-token`; delete it to see the first-run dialog again.

### Verified on this VM (Plasma 6.7.5, 2026-11-02)

| Flow | Result |
| --- | --- |
| First recording | KDE's "Share screen" dialog, then frames; restore token saved |
| Later recordings | No dialog; ~29 fps at 30, ~24 fps at 60 (encoder-bound on 4 vCPU) |
| Recording length | 50.64 s video for 50.7 s wall clock |
| Cursor telemetry | Pixel-exact positions, clicks attributed to the right position |
| Second pointer in video | None (`cursor_mode=1`) |
| KDE indicator "End" | Helper exits, recording saved, editor opens |
| Global shortcuts | KDE confirmation dialog once; Ctrl+Alt+R starts and stops recording |
