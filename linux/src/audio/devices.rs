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

/// One recordable PulseAudio/PipeWire source: a microphone, a USB interface,
/// or a sink monitor (system audio loopback).
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct AudioSource {
    pub name: String,
    pub description: String,
    pub is_monitor: bool,
}

/// Parse `pactl list sources` output into [`AudioSource`]s.
///
/// Pure and unit-tested: blocks start at `Source #…` lines, `Name:` /
/// `Description:` / `Monitor of Sink:` values are tab-indented. A source is a
/// monitor unless its monitor-of-sink reads `n/a`.
pub fn parse_pactl_list_sources(output: &str) -> Vec<AudioSource> {
    let mut sources = Vec::new();
    let mut name: Option<String> = None;
    let mut description = String::new();
    let mut is_monitor = false;
    let mut in_block = false;
    let flush = |name: &mut Option<String>,
                 description: &mut String,
                 is_monitor: &mut bool,
                 out: &mut Vec<AudioSource>| {
        if let Some(n) = name.take() {
            if !n.is_empty() {
                out.push(AudioSource {
                    name: n,
                    description: std::mem::take(description),
                    is_monitor: std::mem::replace(is_monitor, false),
                });
            }
        }
        description.clear();
        *is_monitor = false;
    };
    for line in output.lines() {
        if line.starts_with("Source #") {
            if in_block {
                flush(&mut name, &mut description, &mut is_monitor, &mut sources);
            }
            in_block = true;
            continue;
        }
        if !in_block {
            continue;
        }
        if line.is_empty() {
            flush(&mut name, &mut description, &mut is_monitor, &mut sources);
            in_block = false;
            continue;
        }
        let trimmed = line.trim_start();
        if let Some(value) = trimmed.strip_prefix("Name:") {
            name = Some(value.trim().to_string());
        } else if let Some(value) = trimmed.strip_prefix("Description:") {
            description = value.trim().to_string();
        } else if let Some(value) = trimmed.strip_prefix("Monitor of Sink:") {
            is_monitor = value.trim() != "n/a";
        }
    }
    if in_block {
        flush(&mut name, &mut description, &mut is_monitor, &mut sources);
    }
    sources
}

/// List every recordable audio source via `pactl list sources`.
///
/// Never fails: a broken/missing pactl yields an empty list (and a warning)
/// so callers can decide whether to proceed unverified or abort.
pub fn list_sources() -> Vec<AudioSource> {
    match run_pactl(&["list", "sources"]) {
        Ok(out) => parse_pactl_list_sources(&out),
        Err(e) => {
            log::warn!("Could not list audio sources: {e:#}");
            Vec::new()
        }
    }
}

/// [`list_sources`] serialized as JSON for the window process.
pub fn list_sources_json() -> String {
    serde_json::to_string(&list_sources()).unwrap_or_else(|_| "[]".into())
}

/// Names in `selected` that are absent from `available` (order-preserving).
/// Pure so the recorder's fail-fast check is unit-testable.
pub fn missing_sources(selected: &[String], available: &[AudioSource]) -> Vec<String> {
    selected
        .iter()
        .filter(|s| !available.iter().any(|a| &a.name == *s))
        .cloned()
        .collect()
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

    const SAMPLE: &str = "Source #1\n\tState: SUSPENDED\n\tName: alsa_input.usb-Elgato.mono-fallback\n\tDescription: Elgato Wave 1 Mono\n\tMonitor of Sink: n/a\n\nSource #2\n\tState: IDLE\n\tName: alsa_output.pci.analog-stereo.monitor\n\tDescription: Monitor of Starship HD Audio\n\tMonitor of Sink: alsa_output.pci.analog-stereo\n\nSource #3\n\tName: alsa_input.pci.analog-stereo\n\tDescription: Starship HD Audio\n\tMonitor of Sink: n/a\n";

    #[test]
    fn parses_every_source_with_monitor_flags() {
        let sources = parse_pactl_list_sources(SAMPLE);
        assert_eq!(sources.len(), 3);
        assert_eq!(
            sources[0],
            AudioSource {
                name: "alsa_input.usb-Elgato.mono-fallback".into(),
                description: "Elgato Wave 1 Mono".into(),
                is_monitor: false,
            }
        );
        assert_eq!(
            sources[1],
            AudioSource {
                name: "alsa_output.pci.analog-stereo.monitor".into(),
                description: "Monitor of Starship HD Audio".into(),
                is_monitor: true,
            }
        );
        assert!(!sources[2].is_monitor);
    }

    #[test]
    fn parse_tolerates_missing_description_and_trailing_block() {
        let out = "Source #7\n\tName: bare_source\n\tMonitor of Sink: n/a\n";
        let sources = parse_pactl_list_sources(out);
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].description, "");
        assert!(!sources[0].is_monitor);
    }

    #[test]
    fn parse_ignores_garbage_and_empty_output() {
        assert!(parse_pactl_list_sources("").is_empty());
        assert!(parse_pactl_list_sources("Connection failure: refused\n").is_empty());
        // A block without a name carries no recordable device.
        assert!(parse_pactl_list_sources("Source #9\n\tDescription: nameless\n").is_empty());
    }

    #[test]
    fn missing_sources_reports_unknown_names_in_order() {
        let available = parse_pactl_list_sources(SAMPLE);
        let selected = vec![
            "alsa_input.usb-Elgato.mono-fallback".to_string(),
            "nope".to_string(),
            "alsgone".to_string(),
        ];
        assert_eq!(
            missing_sources(&selected, &available),
            vec!["nope", "alsgone"]
        );
        assert!(missing_sources(&selected[..1], &available).is_empty());
    }

    #[test]
    fn sources_json_is_an_array() {
        // Shape check only — the host list itself depends on local hardware.
        let json: serde_json::Value = serde_json::from_str(&list_sources_json()).unwrap();
        assert!(json.is_array());
    }
}
