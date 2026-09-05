//! ffmpeg command builders.

use std::path::Path;

/// Loudness normalization applied to every recorded channel.
///
/// `speechnorm` is a causal speech normalizer (a sample-recursive peak
/// follower with no lookahead), so it holds no audio back and flushes cleanly
/// when ffmpeg stops — the file ends where the recording stopped. `e=10`
/// caps the expansion at 20 dB so silence is not amplified into noise, and
/// `l=1` links channels so a stereo pair keeps its balance (a dead channel is
/// never boosted on its own).
///
/// Do NOT use `dynaudnorm` here: its Gaussian gain window (`g` frames of `f`
/// ms — 15×200 ms ≈ 3 s) needs future context to emit, and on stop
/// (SIGTERM or `q`, file or live input) it drops the whole buffered window
/// instead of flushing it, so every recording lost its last ~2.8 s. Measured
/// with the app's exact commands, not theorized.
const NORMALIZE: &str = "speechnorm=e=10:l=1";

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
    // the wall-clock duration). speechnorm is causal and realtime-safe
    // (measured >100× realtime) and lifts quiet mics; each channel is
    // normalized independently.
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

/// Build ffmpeg command recording an arbitrary Custom-mode source list.
///
/// Every input is a PulseAudio/PipeWire source (microphones and/or sink
/// monitors) normalized independently like the fixed modes:
/// - 1 source: single input with the mic-only filter.
/// - 2 sources: `amerge`d into a stereo file (left = first selected).
/// - 3+ sources: mixed down with `amix` and forced to stereo — MP3 has no
///   discrete multichannel layout, so a raw N-channel `amerge` would not
///   encode. Callers guarantee a non-empty list.
pub fn build_ffmpeg_command_multi(
    sources: &[String],
    output_path: &Path,
    quality: &str,
) -> Vec<String> {
    debug_assert!(!sources.is_empty());
    let mut cmd = vec![
        "ffmpeg".to_string(),
        "-hide_banner".into(),
        "-loglevel".into(),
        "error".into(),
        "-y".into(),
    ];
    for source in sources {
        cmd.extend([
            "-thread_queue_size".to_string(),
            "4096".into(),
            "-f".into(),
            "pulse".into(),
            "-i".into(),
            source.clone(),
        ]);
    }
    if sources.len() == 1 {
        cmd.extend(["-af".to_string(), format!("highpass=f=80,{NORMALIZE}")]);
    } else {
        let mut filter = String::new();
        for (i, _) in sources.iter().enumerate() {
            filter.push_str(&format!("[{i}:a]highpass=f=80,{NORMALIZE}[a{i}];"));
        }
        if sources.len() == 2 {
            filter.push_str("[a0][a1]amerge=inputs=2[out]");
        } else {
            let mixed: String = (0..sources.len()).map(|i| format!("[a{i}]")).collect();
            filter.push_str(&format!(
                "{mixed}amix=inputs={}:duration=longest:dropout_transition=0:normalize=0[mix]",
                sources.len()
            ));
        }
        let out_label = if sources.len() == 2 { "[out]" } else { "[mix]" };
        cmd.extend(["-filter_complex".to_string(), filter]);
        cmd.extend(["-map".to_string(), out_label.to_string()]);
        if sources.len() > 2 {
            // The amix output channel count follows its inputs; pin stereo so
            // libmp3lame always receives an encodable layout.
            cmd.extend(["-ac".to_string(), "2".to_string()]);
        }
    }
    cmd.extend([
        "-acodec".to_string(),
        "libmp3lame".into(),
        "-q:a".into(),
        quality.into(),
        output_path.to_string_lossy().into_owned(),
    ]);
    cmd
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
        assert!(filter.contains("[1:a]speechnorm"));
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
        assert_eq!(af, "highpass=f=80,speechnorm=e=10:l=1");
    }

    fn multi(sources: &[&str]) -> Vec<String> {
        build_ffmpeg_command_multi(
            &sources.iter().map(|s| s.to_string()).collect::<Vec<_>>(),
            &PathBuf::from("o.mp3"),
            "5",
        )
    }

    fn filter_of(cmd: &[String]) -> &str {
        let i = cmd.iter().position(|a| a == "-filter_complex").unwrap();
        &cmd[i + 1]
    }

    #[test]
    fn multi_single_source_matches_mic_only_shape() {
        let cmd = multi(&["mic"]);
        assert_eq!(cmd.iter().filter(|a| *a == "-i").count(), 1);
        assert!(cmd.contains(&"mic".to_string()));
        assert!(!cmd.contains(&"-filter_complex".to_string()));
        let af = cmd
            .iter()
            .position(|a| a == "-af")
            .and_then(|i| cmd.get(i + 1))
            .unwrap();
        assert!(af.contains("highpass=f=80"));
        assert!(af.contains("speechnorm"));
        assert_eq!(cmd.last().unwrap(), "o.mp3");
    }

    #[test]
    fn multi_two_sources_merge_to_stereo() {
        let cmd = multi(&["mic", "sink.monitor"]);
        assert_eq!(cmd.iter().filter(|a| *a == "-i").count(), 2);
        let filter = filter_of(&cmd);
        assert!(filter.contains("[0:a]highpass=f=80,speechnorm"));
        assert!(filter.contains("[1:a]highpass=f=80,speechnorm"));
        assert!(filter.ends_with("[a0][a1]amerge=inputs=2[out]"));
        let map = cmd
            .iter()
            .position(|a| a == "-map")
            .and_then(|i| cmd.get(i + 1))
            .unwrap();
        assert_eq!(map, "[out]");
        // No forced channel count for the stereo merge.
        assert!(!cmd.contains(&"-ac".to_string()));
    }

    #[test]
    fn multi_three_sources_mix_down_to_stereo() {
        let cmd = multi(&["mic1", "mic2", "sink.monitor"]);
        assert_eq!(cmd.iter().filter(|a| *a == "-i").count(), 3);
        let filter = filter_of(&cmd);
        assert!(filter.contains("[a0][a1][a2]amix=inputs=3"));
        let map = cmd
            .iter()
            .position(|a| a == "-map")
            .and_then(|i| cmd.get(i + 1))
            .unwrap();
        assert_eq!(map, "[mix]");
        let ac = cmd
            .iter()
            .position(|a| a == "-ac")
            .and_then(|i| cmd.get(i + 1))
            .unwrap();
        assert_eq!(ac, "2");
        assert_eq!(cmd.last().unwrap(), "o.mp3");
    }

    #[test]
    fn no_recording_command_uses_a_tail_dropping_filter() {
        // Regression: `dynaudnorm` buffers its Gaussian gain window (~3 s)
        // and drops it on stop instead of flushing, so every recording lost
        // its last ~2.8 s. All live-chain filters must be causal (flush to
        // the last sample on SIGTERM). This fails if dynaudnorm returns.
        let cmds = vec![
            build_ffmpeg_command("mic", "sink.monitor", &PathBuf::from("o.mp3"), "5"),
            build_ffmpeg_command_mic_only("mic", &PathBuf::from("o.mp3"), "5"),
            multi(&["mic"]),
            multi(&["mic", "sink.monitor"]),
            multi(&["mic1", "mic2", "sink.monitor"]),
        ];
        for cmd in &cmds {
            assert!(
                !cmd.iter().any(|a| a.contains("dynaudnorm")),
                "tail-dropping filter in recording command: {cmd:?}"
            );
        }
        // ...while the quiet-mic boost the normalizer is for must survive:
        // every shape still carries the causal speech normalizer.
        for cmd in &cmds {
            assert!(
                cmd.iter().any(|a| a.contains("speechnorm")),
                "missing loudness normalization in recording command: {cmd:?}"
            );
        }
    }
}
