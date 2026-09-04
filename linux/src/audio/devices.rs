//! PulseAudio/PipeWire device queries via pactl.

use std::process::Command;
use std::time::Duration;

use crate::utils::exe::runtime_program;

fn run_pactl(args: &[&str]) -> anyhow::Result<String> {
    // pactl is instantaneous under normal conditions; time it out so a hung
    // PipeWire/PulseAudio can't stall the caller for long.
    let mut child = Command::new(runtime_program("pactl"))
        .args(args)
        .stdout(std::process::Stdio::piped())
        .spawn()?;
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    loop {
        match child.try_wait()? {
            Some(status) => {
                let out = child.wait_with_output()?;
                if !status.success() {
                    anyhow::bail!("pactl {} failed", args.join(" "));
                }
                return Ok(String::from_utf8_lossy(&out.stdout).into_owned());
            }
            None => {
                if std::time::Instant::now() > deadline {
                    // A timed-out query is still a child owned by this
                    // process.  Ask it to exit and reap it asynchronously so
                    // the caller is not forced to use SIGKILL.
                    request_term(child.id());
                    let _ = std::thread::Builder::new()
                        .name("pactl-timeout-reaper".into())
                        .spawn(move || {
                            let mut child = child;
                            let _ = child.wait();
                        });
                    anyhow::bail!("pactl {} timed out", args.join(" "));
                }
                std::thread::sleep(Duration::from_millis(50));
            }
        }
    }
}

#[cfg(unix)]
fn request_term(pid: u32) {
    // SAFETY: pid belongs to the child spawned by run_pactl.
    unsafe extern "C" {
        fn kill(pid: i32, sig: i32) -> i32;
    }
    unsafe {
        let _ = kill(pid as i32, 15);
    }
}

#[cfg(not(unix))]
fn request_term(_pid: u32) {}

/// Default PulseAudio source (microphone), if any.
pub fn get_default_source() -> Option<String> {
    match run_pactl(&["get-default-source"]) {
        Ok(o) => {
            let s = o.trim().to_string();
            if s.is_empty() {
                None
            } else {
                Some(s)
            }
        }
        Err(e) => {
            log::warn!("Could not get default source: {e:#}");
            None
        }
    }
}

/// Default PulseAudio sink, if any.
pub fn get_default_sink() -> Option<String> {
    match run_pactl(&["get-default-sink"]) {
        Ok(o) => {
            let s = o.trim().to_string();
            if s.is_empty() {
                None
            } else {
                Some(s)
            }
        }
        Err(e) => {
            log::warn!("Could not get default sink: {e:#}");
            None
        }
    }
}

/// Monitor source for a sink (loopback recording). PipeWire/PulseAudio creates
/// a virtual `<sink>.monitor` source for every sink — no extra configuration.
pub fn monitor_of_sink(sink_name: &str) -> String {
    format!("{sink_name}.monitor")
}

/// Validate that required audio devices exist.
pub fn validate_devices() -> Result<(), String> {
    if get_default_source().is_none() {
        return Err("No microphone (audio source) found.".to_string());
    }
    if get_default_sink().is_none() {
        return Err("No audio output device (sink) found.".to_string());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn monitor_name() {
        assert_eq!(
            monitor_of_sink("alsa_output.pci"),
            "alsa_output.pci.monitor"
        );
    }
}
