**ALWAYS USE MODERN JAVA**
**NULL IS FORBIDDEN WITHOUT A WELL JUSTIFIED REASON**
**THIS IS A LINUX ONLY APPLICATION**
**TESTING ON KDE/GNOME/OTHERS SHOULD ONLY BE DONE WHEN REQUESTED BY THE USER EXPLICITLY***

When done, always commit the changes, compile a new version and bundle a new app image, except if you were explicitly said not to do so.

## GPU / hardware video

Hardware encoding is a supported path, not a forbidden one. This machine is the baseline: its
Radeon RX 560 (Polaris, VCE 3) is the weakest GPU the app is expected to run on, so a feature that
works here works anywhere. Hardware encode **must** be exercised here whenever the kernel carries
the VCE fix.

The VCE fix is `2ee9836545e6` ("drm/amdgpu: Fix VCE 3 ring align_mask", VM ring `align_mask = 0x1f`),
which landed in 7.3 and was backported to the 7.2 stable line. **Check the running kernel first:**
7.2.15 carries the fix, so VA-API encode is safe here and must be tested. The old "no VCE encode
test" rule is obsolete — it only ever applied to a kernel without the fix, and it never meant
hardware encoding was a non-goal.

Testing still climbs a ladder, one rung at a time, stopping at the first sign of trouble:
read-only capability queries → tiny decode → tiny encode (few frames, low resolution, low-latency
settings), each GPU-touching rung under `timeout -s KILL` with a memory cap. Tell the user before a
rung that touches the GPU so they can save their work. Never patch or replace the kernel.

Measured on this machine (RX 560, kernel 7.2.15, 10 s of 1080p `testsrc2`), because the policy
depends on it:

| encoder | wall | CPU | SSIM vs source |
|---|---|---|---|
| libx264 `medium` CRF 20 | 2.31 s | 762% | 0.9976 |
| `h264_vaapi` q8 VBR | 3.45 s | 39% | 0.9975 |
| `h264_vaapi` q4 VBR | 8.49 s | 39% | 0.9981 |

VA-API is 1.2-1.7x **slower in wall-clock** here at every size (the gap shrinks as resolution
grows) while using ~20x less CPU, so the app's policy is: **recording always prefers hardware**
(it only has to keep up with capture, and the freed CPU keeps compositing smooth), **export uses
hardware from 4K up** where the wall-clock gap is smallest, and stays on libx264 below that
(`VideoEncoderProbe.EXPORT_HARDWARE_MIN_PIXELS`). Re-measure and update that constant if the card,
the driver or the ffmpeg build changes.

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
