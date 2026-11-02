**ALWAYS USE MODERN JAVA**
**NULL IS FORBIDDEN WITHOUT A WELL JUSTIFIED REASON**
**THIS IS A LINUX ONLY APPLICATION**

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
