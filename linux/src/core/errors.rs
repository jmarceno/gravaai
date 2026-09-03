//! Error presentation policy.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Presentation {
    Dialog,
    Toast,
}

/// Actionable configuration problems get a modal dialog; transient/runtime
/// failures get a toast.
pub fn error_presentation(msg: &str) -> Presentation {
    let lower = msg.to_lowercase();
    const DIALOG_MARKERS: &[&str] = &[
        "api key",
        "not configured",
        "not installed",
        "not found. please install",
        "open settings",
        "audio device",
        "permission",
    ];
    if DIALOG_MARKERS.iter().any(|m| lower.contains(m)) {
        Presentation::Dialog
    } else {
        Presentation::Toast
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dialog_for_config_problems() {
        assert_eq!(
            error_presentation(
                "OpenAI-compatible API key is not configured. Please open Settings."
            ),
            Presentation::Dialog
        );
        assert_eq!(
            error_presentation("audio device error: no source"),
            Presentation::Dialog
        );
    }

    #[test]
    fn toast_for_transient() {
        assert_eq!(
            error_presentation("connection reset by peer"),
            Presentation::Toast
        );
        assert_eq!(error_presentation(""), Presentation::Toast);
    }
}
