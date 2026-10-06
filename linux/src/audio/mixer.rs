//! ffmpeg command builders.

use std::path::Path;

use super::devices::AudioSource;

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

/// Downmix one input to a single channel before it is merged. PipeWire
/// sources and sink monitors are usually stereo: `amerge` of two stereo
/// inputs yields a 4.0 stream that libmp3lame then downmixes, blending mic
/// and system audio into both channels and destroying the separation.
const MONO: &str = "aformat=channel_layouts=mono";

/// Merge two mono branches into a true stereo pair (left = first input).
const STEREO_PAIR: &str = "amerge=inputs=2,pan=stereo|c0=c0|c1=c1";

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
        "[0:a]{MONO},highpass=f=80,{NORMALIZE}[mic];\
         [1:a]{MONO},{NORMALIZE}[sys];\
         [mic][sys]{STEREO_PAIR}[out]"
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
        "-fragment_size".into(),
        "1024".into(),
        "-f".into(),
        "pulse".into(),
        "-i".into(),
        source.into(),
        "-thread_queue_size".into(),
        "4096".into(),
        "-fragment_size".into(),
        "1024".into(),
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
        "-fragment_size".into(),
        "1024".into(),
        "-f".into(),
        "pulse".into(),
        "-i".into(),
        source.into(),
        "-af".into(),
        format!("{MONO},highpass=f=80,{NORMALIZE},pan=stereo|c0=c0|c1=0*c0"),
        "-acodec".into(),
        "libmp3lame".into(),
        "-q:a".into(),
        quality.into(),
        output_path.to_string_lossy().into_owned(),
    ]
}

/// Record every selected source with microphones on the left and system
/// monitors on the right, regardless of selection order. Multiple sources
/// within one role are mixed only into that role's channel. A missing role
/// gets silence, so diarization never mistakes a monitor-only capture for mic.
pub fn build_ffmpeg_command_multi(
    sources: &[AudioSource],
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
            "-fragment_size".into(),
            "1024".into(),
            "-f".into(),
            "pulse".into(),
            "-i".into(),
            source.name.clone(),
        ]);
    }
    let mut filter = String::new();
    let mut microphones = Vec::new();
    let mut monitors = Vec::new();
    for (index, source) in sources.iter().enumerate() {
        filter.push_str(&format!(
            "[{index}:a]{MONO},highpass=f=80,{NORMALIZE}[a{index}];"
        ));
        if source.is_monitor {
            monitors.push(index);
        } else {
            microphones.push(index);
        }
    }
    let mut group = |indices: &[usize], label: &str| {
        if indices.is_empty() {
            return;
        }
        let inputs: String = indices.iter().map(|index| format!("[a{index}]")).collect();
        if indices.len() == 1 {
            filter.push_str(&format!("{inputs}anull[{label}];"));
        } else {
            filter.push_str(&format!(
                "{inputs}amix=inputs={}:duration=longest:dropout_transition=0:normalize=1[{label}];",
                indices.len()
            ));
        }
    };
    group(&microphones, "mic");
    group(&monitors, "sys");
    match (microphones.is_empty(), monitors.is_empty()) {
        (false, false) => filter.push_str(&format!("[mic][sys]{STEREO_PAIR}[out]")),
        (false, true) => filter.push_str("[mic]pan=stereo|c0=c0|c1=0*c0[out]"),
        (true, false) => filter.push_str("[sys]pan=stereo|c0=0*c0|c1=c0[out]"),
        (true, true) => unreachable!("recording requires at least one source"),
    }
    cmd.extend([
        "-filter_complex".into(),
        filter,
        "-map".into(),
        "[out]".into(),
    ]);
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
    #[test]
    fn pulse_inputs_use_small_fragments_for_short_recordings() {
        // The default fragment on FFmpeg 4.4 can buffer seconds of capture.
        // SIGTERM then leaves a short recording with only an MP3 header.
        let sources = audio_sources(&["mic", "sink.monitor", "extra"]);
        for cmd in [
            build_ffmpeg_command("mic", "monitor", Path::new("out.mp3"), "2"),
            build_ffmpeg_command_mic_only("mic", Path::new("out.mp3"), "2"),
            build_ffmpeg_command_multi(&sources, Path::new("out.mp3"), "2"),
        ] {
            for (index, arg) in cmd.iter().enumerate() {
                if arg == "-f" && cmd[index + 1] == "pulse" {
                    assert_eq!(&cmd[index - 2..index], ["-fragment_size", "1024"]);
                }
            }
        }
    }
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
        assert!(filter.contains("[0:a]aformat=channel_layouts=mono,highpass=f=80,"));
        assert!(filter.contains("[1:a]aformat=channel_layouts=mono,speechnorm"));
        assert!(filter.ends_with("[mic][sys]amerge=inputs=2,pan=stereo|c0=c0|c1=c1[out]"));
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
        assert_eq!(af, "aformat=channel_layouts=mono,highpass=f=80,speechnorm=e=10:l=1,pan=stereo|c0=c0|c1=0*c0");
    }

    fn audio_sources(names: &[&str]) -> Vec<AudioSource> {
        names
            .iter()
            .map(|name| AudioSource {
                name: (*name).into(),
                description: String::new(),
                is_monitor: name.ends_with(".monitor"),
            })
            .collect()
    }

    fn multi(sources: &[&str]) -> Vec<String> {
        build_ffmpeg_command_multi(&audio_sources(sources), &PathBuf::from("o.mp3"), "5")
    }

    fn filter_of(cmd: &[String]) -> &str {
        let i = cmd.iter().position(|a| a == "-filter_complex").unwrap();
        &cmd[i + 1]
    }

    #[test]
    fn single_role_keeps_the_other_channel_silent() {
        let mic = multi(&["mic"]);
        assert!(filter_of(&mic).ends_with("[mic]pan=stereo|c0=c0|c1=0*c0[out]"));
        let system = multi(&["sink.monitor"]);
        assert!(filter_of(&system).ends_with("[sys]pan=stereo|c0=0*c0|c1=c0[out]"));
    }

    #[test]
    fn monitor_first_still_maps_microphone_left() {
        let cmd = multi(&["sink.monitor", "mic"]);
        let filter = filter_of(&cmd);
        assert!(filter.contains("[a1]anull[mic]"));
        assert!(filter.contains("[a0]anull[sys]"));
        assert!(filter.ends_with("[mic][sys]amerge=inputs=2,pan=stereo|c0=c0|c1=c1[out]"));
        assert_eq!(filter.matches(MONO).count(), 2);
    }

    #[test]
    fn multiple_sources_mix_only_within_their_role() {
        let cmd = multi(&["sink.monitor", "mic1", "other.monitor", "mic2"]);
        let filter = filter_of(&cmd);
        assert!(filter.contains(
            "[a1][a3]amix=inputs=2:duration=longest:dropout_transition=0:normalize=1[mic]"
        ));
        assert!(filter.contains(
            "[a0][a2]amix=inputs=2:duration=longest:dropout_transition=0:normalize=1[sys]"
        ));
        assert!(filter.ends_with("[mic][sys]amerge=inputs=2,pan=stereo|c0=c0|c1=c1[out]"));
        assert_eq!(filter.matches(MONO).count(), 4);
        assert_eq!(cmd.last().unwrap(), "o.mp3");
    }

    #[test]
    fn monitor_role_uses_metadata_even_without_monitor_suffix() {
        let sources = [AudioSource {
            name: "custom-loopback".into(),
            description: String::new(),
            is_monitor: true,
        }];
        let cmd = build_ffmpeg_command_multi(&sources, Path::new("o.mp3"), "5");
        assert!(filter_of(&cmd).ends_with("[sys]pan=stereo|c0=0*c0|c1=c0[out]"));
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
