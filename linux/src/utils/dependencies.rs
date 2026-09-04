//! Required third-party programs, reported — never installed.
//!
//! The app is not tied to any distro and never installs system packages.
//! Instead it tells the user exactly which programs are missing and what
//! breaks without them: once as a notification at daemon startup, and again
//! as the recorder's own actionable error at the moment they are needed.

pub struct Dependency {
    pub program: &'static str,
    pub purpose: &'static str,
    pub required: bool,
}

pub const DEPENDENCIES: &[Dependency] = &[
    Dependency {
        program: "ffmpeg",
        purpose: "audio recording",
        required: true,
    },
    Dependency {
        program: "ffprobe",
        purpose: "recording duration and library metadata",
        required: true,
    },
    Dependency {
        program: "pactl",
        purpose: "audio device detection (PipeWire/PulseAudio)",
        required: true,
    },
];

/// Missing programs, using the injected lookup (pure and unit-testable).
pub fn check_missing(which: &dyn Fn(&str) -> Option<String>) -> Vec<&'static Dependency> {
    DEPENDENCIES
        .iter()
        .filter(|d| which(d.program).is_none())
        .collect()
}

/// Human-readable summary of the missing *required* programs, or None when
/// everything needed is present. Tells the user what to install (their
/// distro's packages) instead of installing anything.
pub fn describe_missing_required() -> Option<String> {
    describe_missing_required_with(&|b| {
        let path = crate::utils::exe::runtime_program(b);
        path.is_file().then(|| path.to_string_lossy().into_owned())
    })
}

fn describe_missing_required_with(which: &dyn Fn(&str) -> Option<String>) -> Option<String> {
    let missing: Vec<&Dependency> = check_missing(which)
        .into_iter()
        .filter(|d| d.required)
        .collect();
    if missing.is_empty() {
        return None;
    }
    let parts: Vec<String> = missing
        .iter()
        .map(|d| format!("{} ({})", d.program, d.purpose))
        .collect();
    Some(format!(
        "Missing required programs: {}. Install them with your distro's packages and restart the app.",
        parts.join(", ")
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_present_means_no_report() {
        let all = |_: &str| Some("/usr/bin/x".to_string());
        assert!(check_missing(&all).is_empty());
        assert!(describe_missing_required_with(&all).is_none());
    }

    #[test]
    fn missing_required_are_named() {
        let none = |_: &str| None;
        let msg = describe_missing_required_with(&none).unwrap();
        assert!(msg.contains("ffmpeg"));
        assert!(msg.contains("pactl"));
        assert!(msg.contains("ffprobe"));
    }

    #[test]
    fn optional_only_is_silent() {
        let all_present = |_: &str| Some("/usr/bin/x".to_string());
        assert!(describe_missing_required_with(&all_present).is_none());
    }
}
