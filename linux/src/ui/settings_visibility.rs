//! Models-tab section visibility policy. Pure and unit-tested.

/// Which settings sections are visible for the chosen services.
/// Returns `(show_openai, show_whisper_cpp, show_crisp_asr, show_ollama)`.
pub fn compute_section_visibility(
    transcription: &str,
    summarization: &str,
) -> (bool, bool, bool, bool) {
    let show_openai = transcription == "openai" || summarization == "openai";
    let show_whisper_cpp = transcription == "whisper_cpp";
    let show_crisp_asr = transcription == "crisp_asr";
    let show_ollama = summarization == "ollama";
    (show_openai, show_whisper_cpp, show_crisp_asr, show_ollama)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visibility_policy() {
        assert_eq!(
            compute_section_visibility("openai", "openai"),
            (true, false, false, false)
        );
        assert_eq!(
            compute_section_visibility("whisper_cpp", "ollama"),
            (false, true, false, true)
        );
        assert_eq!(
            compute_section_visibility("whisper_cpp", "openai"),
            (true, true, false, false)
        );
        assert_eq!(
            compute_section_visibility("openai", "ollama"),
            (true, false, false, true)
        );
        assert_eq!(
            compute_section_visibility("crisp_asr", "ollama"),
            (false, false, true, true)
        );
        assert_eq!(
            compute_section_visibility("crisp_asr", "openai"),
            (true, false, true, false)
        );
    }
}
