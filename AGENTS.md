**ALWAYS USE MODERN JAVA**
**NULL IS FORBIDDEN WITHOUT A WELL JUSTIFIED REASON**
**THIS IS A LINUX ONLY APPLICATION**
**TESTING ON KDE/GNOME/OTHERS SHOULD ONLY BE DONE WHEN REQUESTED BY THE USER EXPLICITLY***

When done, always commit the changes, compile a new version and bundle a new app image, except if you were explicitly said not to do so.

## Testing on KDE 

KDE compatibility is tested by clicking the real app in the `KubuntuDev-KDE` VM, never by
reasoning about what should happen. The harness lives in `tools/kde-vm/`; read its README
before touching the VM. The short version:

- **Click with the QEMU tablet over QMP**, from the Proxmox host:
  `python3 /root/gravaai-pointer.py click <x> <y>` (VM is 1920x1080).
- **Never use `xdotool` or `ydotool` to drive this VM.** KWin gates synthetic input behind a
  modal "Remote Control" prompt, which cannot be dismissed by the input it is blocking. Both
  tools report success while the click is silently dropped, so a passing command proves
  nothing.
- **Read the pointer back** with `/opt/gravaai-test/harness.sh pos` and confirm visual state
  with `harness.sh shot` before believing an action landed.
- **The portal's own screen-sharing dialog is the picker on KDE.** If a capture flow waits for
  a click on a monitor instead, that is the bug.

## Switching sessions (KDE <-> GNOME)

The same VM also carries a GNOME 51 (Wayland) session for GNOME compat work; pick it from the
SDDM session picker after logging out (autologin still boots Plasma). The fix — run this
whenever switching KDE<->GNOME:

```bash
ssh root@192.168.0.145 'systemctl stop user@1000.service; systemctl restart sddm'
```

Without it the old session lingers in `State=closing` holding tty1: SDDM crash-loops with
`HELPER_TTY_ERROR`, and the next session's `gnome-session-init-worker` aborts with
`Failed to watch systemd session: open failed` — the wedged user state, not the upstream bug.
`vm.sh pos` is stale under GNOME (the KWin cursor probe does not run there); confirm clicks
with `vm.sh shot` instead.
