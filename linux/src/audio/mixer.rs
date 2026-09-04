//! ffmpeg command builders.

use std::path::Path;

/// Loudness normalization applied to every recorded channel.
///
/// `dynaudnorm` adapts each channel to a healthy level in ~200 ms frames —
/// cheap enough for real-time capture (measured >100× realtime on CPU) and
/// it fixes very quiet microphones. `m=10` caps the boost at 20 dB so
/// silence is not amplified into noise.
const NORMALIZE: &str = "dynaudnorm=f=200:g=15:p=0.9:m=10";

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
    // the wall-clock duration). dynaudnorm is realtime-safe and lifts quiet
    // mics; each channel is normalized independently.
    let filter = format!(
        "[0:a]highpass=f=80,{NORMALIZE}[mic];\
         [1:a]{NORMALIZE}[sys];\
         [mic][sys]amerge=inputs=2[out]"
    );
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
        filter,
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
        format!("highpass=f=80,{NORMALIZE}"),
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
    fn stereo_normalizes_both_channels_independently() {
        let cmd = build_ffmpeg_command("mic", "sink.monitor", &PathBuf::from("o.mp3"), "5");
        let filter = cmd
            .iter()
            .position(|a| a == "-filter_complex")
            .and_then(|i| cmd.get(i + 1))
            .unwrap();
        // Both channels pass through the normalizer before amerge, and the
        // mic keeps its highpass.
        assert!(filter.contains("[0:a]highpass=f=80,"));
        assert!(filter.contains("[1:a]dynaudnorm"));
        assert!(filter.ends_with("[mic][sys]amerge=inputs=2[out]"));
    }

    #[test]
    fn mic_only_normalizes_audio() {
        let cmd = build_ffmpeg_command_mic_only("mic", &PathBuf::from("o.mp3"), "5");
        assert_eq!(cmd.iter().filter(|a| *a == "-i").count(), 1);
        let af = cmd
            .iter()
            .position(|a| a == "-af")
            .and_then(|i| cmd.get(i + 1))
            .unwrap();
        assert_eq!(af, "highpass=f=80,dynaudnorm=f=200:g=15:p=0.9:m=10");
    }
}
