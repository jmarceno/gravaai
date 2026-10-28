package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.recording.RecordingDiagnostics.CompanionAudioCandidate;
import dev.gravaai.services.recording.RecordingDiagnostics.MicrophoneChunkTimingEvent;
import dev.gravaai.services.recording.RecordingDiagnostics.MicrophoneChunkTimingSummary;
import dev.gravaai.services.recording.RecordingDiagnostics.MicrophonePauseInterval;
import dev.gravaai.services.recording.RecordingDiagnostics.VideoStreamDurationProbe;

/** Ported from the portable cases of {@code electron/ipc/recording/diagnostics.test.ts}. */
class RecordingDiagnosticsTest {

	@TempDir
	Path dir;

	@Test
	@DisplayName("scales audio mux timeout for long recordings")
	void scalesAudioMuxTimeout() {
		assertEquals(5 * 60 * 1000, RecordingDiagnostics.getRecordingAudioMuxTimeoutMs(0));
		double duration = 29 * 60 + 29.41;
		assertEquals(1_829_410.0, (double) RecordingDiagnostics.getRecordingAudioMuxTimeoutMs(duration), 2.0);
		assertEquals(true, RecordingDiagnostics.getRecordingAudioMuxTimeoutMs(duration) > 120_000);
	}

	@Test
	@DisplayName("uses video stream frames when container duration is misleading")
	void usesVideoStreamFrames() {
		assertEquals(
				new VideoStreamDurationProbe(18.0, 540L, 30.0),
				RecordingDiagnostics.parseFfprobeVideoStreamDuration("""
						{"streams":[{"duration":"18.000000","nb_read_frames":"540","avg_frame_rate":"30/1"}]}
						"""));
	}

	@Test
	@DisplayName("derives video stream duration from frame count when stream duration is absent")
	void derivesDurationFromFrameCount() {
		assertEquals(
				new VideoStreamDurationProbe(18.0, 540L, 30.0),
				RecordingDiagnostics.parseFfprobeVideoStreamDuration("""
						{"streams":[{"nb_read_frames":"540","avg_frame_rate":"30/1"}]}
						"""));
		assertNull(RecordingDiagnostics.parseFfprobeVideoStreamDuration("not json"));
		assertNull(RecordingDiagnostics.parseFfprobeVideoStreamDuration("{\"streams\":[]}"));
	}

	@Test
	@DisplayName("classifies wall-clock mic chunk gaps covered by pause intervals")
	void classifiesPauseAccountedGaps() {
		MicrophoneChunkTimingSummary summary = RecordingDiagnostics.summarizeMicrophoneChunkTiming(
				List.of(
						new MicrophoneChunkTimingEvent(0, 1024, 250, null, 250L, null),
						new MicrophoneChunkTimingEvent(1, 1024, 8250, 8000L, 500L, 250L)),
				List.of(new MicrophonePauseInterval(250, 8250L, 7750.0)),
				250);
		assertEquals("pause-accounted", summary.status());
		assertEquals(1, summary.wallClockGapCount());
		assertEquals(0, summary.recordedGapCount());
		assertEquals(7750, summary.pausedDurationMs());
	}

	@Test
	@DisplayName("flags recorded mic chunk gaps that remain after pause accounting")
	void flagsRecordedGaps() {
		MicrophoneChunkTimingSummary summary = RecordingDiagnostics.summarizeMicrophoneChunkTiming(
				List.of(
						new MicrophoneChunkTimingEvent(0, 1024, 250, null, 250L, null),
						new MicrophoneChunkTimingEvent(1, 1024, 2500, 2250L, 2500L, 2250L)),
				List.of(),
				250);
		assertEquals("needs-review", summary.status());
		assertEquals(1, summary.wallClockGapCount());
		assertEquals(1, summary.recordedGapCount());
	}

	@Test
	@DisplayName("parses the ffmpeg container duration and diagnostics path")
	void parsesDurationAndDiagnosticsPath() {
		assertEquals(
				18.0,
				RecordingDiagnostics.parseFfmpegDurationSeconds(
						"Duration: 00:00:18.00, start: 0.000000"));
		assertNull(RecordingDiagnostics.parseFfmpegDurationSeconds("Stream #0:0: Video: h264"));
		assertEquals(
				"/tmp/rec/recording-1.recording-diagnostics.json",
				RecordingDiagnostics.getRecordingDiagnosticsPath("/tmp/rec/recording-1.mp4"));
	}

	@Test
	@DisplayName("loads saved sidecar timing metadata alongside companion audio paths")
	void loadsSidecarTimingMetadata() throws IOException {
		Path video = dir.resolve("recording.mp4");
		Path mic = dir.resolve("recording.mic.webm");
		Files.writeString(video, "video", StandardCharsets.UTF_8);
		Files.writeString(mic, "mic", StandardCharsets.UTF_8);
		Files.writeString(Path.of(mic + ".json"), "\ufeff{\"startDelayMs\":2750}", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("recording.system.wav"), "system", StandardCharsets.UTF_8);

		List<CompanionAudioCandidate> candidates =
				RecordingDiagnostics.getUsableCompanionAudioCandidates(video.toString());

		CompanionAudioCandidate mac = candidates.stream()
				.filter(candidate -> candidate.platform().equals("mac"))
				.findFirst()
				.orElseThrow();
		assertEquals(List.of(mic.toString()), mac.usablePaths());
		CompanionAudioCandidate win = candidates.stream()
				.filter(candidate -> candidate.platform().equals("win"))
				.findFirst()
				.orElseThrow();
		assertEquals(
				List.of(dir.resolve("recording.system.wav").toString()), win.usablePaths());
		assertEquals(2750L, RecordingDiagnostics.getCompanionAudioStartDelayMs(mic.toString()));
	}

	@Test
	@DisplayName("ignores invalid sidecar timing metadata values")
	void ignoresInvalidSidecarTimingMetadata() throws IOException {
		Path mic = dir.resolve("recording.mic.wav");
		Files.writeString(mic, "mic", StandardCharsets.UTF_8);
		Files.writeString(Path.of(mic + ".json"), "{\"startDelayMs\":-250}", StandardCharsets.UTF_8);
		assertNull(RecordingDiagnostics.getCompanionAudioStartDelayMs(mic.toString()));
		assertNull(RecordingDiagnostics.getCompanionAudioStartDelayMs(
				dir.resolve("missing.wav").toString()));
	}
}
