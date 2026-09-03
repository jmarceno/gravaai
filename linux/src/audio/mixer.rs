//! ffmpeg command builders.

use std::path::Path;

/// Build ffmpeg command reading mic + system monitor into a stereo MP3.
///
/// Channel layout: Left (ch 0) = mic, Right (ch 1) = system audio. `amerge`
/// produces a true stereo file, preserving speaker separation for AI
/// transcription.
pub fn build_ffmpeg_command(
    source: &str,
    monitor: &str,
    output_path: &Path,
    quality: &str,
) -> Vec<String> {
    // highpass=f=80: cut sub-80 Hz rumble. No denoiser: afftdn/anlmdn are too
    // slow for real-time use and make ffmpeg drop packets (file shorter than
    // the wall-clock duration).
    let filter = "[0:a]highpass=f=80[mic];[mic][1:a]amerge=inputs=2[out]";
    vec![
        "ffmpeg".into(),
        "-hide_banner".into(),
        "-loglevel".into(),
        "error".into(),
        "-y".into(),
        // Buffers packets between the PulseAudio input thread and the
        // filter/encode thread; without it ffmpeg silently drops audio.
        "-thread_queue_size".into(),
        "4096".into(),
        "-f".into(),
        "pulse".into(),
        "-i".into(),
        source.into(),
        "-thread_queue_size".into(),
        "4096".into(),
        "-f".into(),
        "pulse".into(),
        "-i".into(),
        monitor.into(),
        "-filter_complex".into(),
        filter.into(),
        "-map".into(),
        "[out]".into(),
        "-acodec".into(),
        "libmp3lame".into(),
        "-q:a".into(),
        quality.into(),
        output_path.to_string_lossy().into_owned(),
    ]
}

/// Build ffmpeg command recording the microphone only (speaker mode — the
/// monitor is skipped to avoid echo).
pub fn build_ffmpeg_command_mic_only(
    source: &str,
    output_path: &Path,
    quality: &str,
) -> Vec<String> {
    vec![
        "ffmpeg".into(),
        "-hide_banner".into(),
        "-loglevel".into(),
        "error".into(),
        "-y".into(),
        "-thread_queue_size".into(),
        "4096".into(),
        "-f".into(),
        "pulse".into(),
        "-i".into(),
        source.into(),
        "-af".into(),
        "highpass=f=80".into(),
        "-acodec".into(),
        "libmp3lame".into(),
        "-q:a".into(),
        quality.into(),
        output_path.to_string_lossy().into_owned(),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn stereo_layout() {
        let cmd = build_ffmpeg_command("mic", "sink.monitor", &PathBuf::from("o.mp3"), "5");
        assert!(cmd.contains(&"-filter_complex".to_string()));
        assert!(cmd.iter().any(|a| a.contains("amerge=inputs=2")));
        // mic is the first input, monitor the second.
        let mic_pos = cmd.iter().position(|a| a == "mic").unwrap();
        let mon_pos = cmd.iter().position(|a| a == "sink.monitor").unwrap();
        assert!(mic_pos < mon_pos);
        assert_eq!(cmd.last().unwrap(), "o.mp3");
    }

    #[test]
    fn mic_only_has_single_input() {
        let cmd = build_ffmpeg_command_mic_only("mic", &PathBuf::from("o.mp3"), "5");
        assert_eq!(cmd.iter().filter(|a| *a == "-i").count(), 1);
        assert!(cmd.iter().any(|a| a.contains("highpass")));
    }
}
