package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.gravaai.media.export.VideoEncoderSpec;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioRole;
import dev.gravaai.services.recording.MicNoiseReduction;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * One recording in progress: what it captured, where its files are and how it is paused.
 *
 * <p>It is the shared state of the capture topology, the audio tracks, the manifest writer and the
 * stop path, so it lives on its own instead of being nested in the recorder.
 */
final class CosmicRecording {

	/** A uniformly captured frame size and pixel layout reported by the helper. */
	record CaptureFormat(int width, int height, String pixelFormat, int transform) {
	}

	/** The pausable flag shared by the video command stream and the audio writers. */
	record PauseState(AtomicBoolean paused) {

		static PauseState running() {
			return new PauseState(new AtomicBoolean(false));
		}
	}

	/**
	 * The capture helper feeding the encoder, the encoder itself and the path it writes.
	 *
	 * @param encoderSpec the encoder that actually started (a hardware session that died at once was
	 *     already replaced by libx264, so this is what ran)
	 */
	record VideoCapture(SpawnedProcess capture, SpawnedProcess encoder, Path videoPath,
			VideoEncoderSpec encoderSpec) {
	}

	/** One parec process writing raw PCM to its own file. */
	static final class AudioTrack {

		private final SpawnedProcess process;
		private final CosmicAudioRole role;
		private final Path path;
		private final OutputStream file;
		private final CompletableFuture<Void> firstData = new CompletableFuture<>();
		private volatile long startedAtMs = -1;

		AudioTrack(SpawnedProcess process, CosmicAudioRole role, Path path, OutputStream file) {
			this.process = process;
			this.role = role;
			this.path = path;
			this.file = file;
		}

		SpawnedProcess process() {
			return process;
		}

		CosmicAudioRole role() {
			return role;
		}

		Path path() {
			return path;
		}

		OutputStream file() {
			return file;
		}

		/** Completes when the track produced its first chunk, or died. */
		CompletableFuture<Void> firstData() {
			return firstData;
		}

		/** First data wall clock, or -1 when the device has not produced anything yet. */
		long startedAtMs() {
			return startedAtMs;
		}

		/** True when no chunk has arrived yet. */
		boolean awaitingFirstData() {
			return startedAtMs < 0;
		}

		/**
		 * Records when the first chunk was captured. Called once per track, from the drain thread.
		 *
		 * @param startedAt the wall clock the chunk's first sample was captured at
		 */
		void markFirstData(long startedAt) {
			if (startedAtMs < 0) {
				startedAtMs = startedAt;
			}
			firstData.complete(null);
		}

		/** Completes {@link #firstData()} without a start time, for a track that will never produce. */
		void completeWithoutData() {
			firstData.complete(null);
		}
	}

	private final @Nullable VideoCapture video;
	private final List<AudioTrack> audio;
	private final Path outputPath;
	private final long startedAtMs;
	private final boolean keepSpeakerTracks;
	private final PauseState pauseState;
	private final MicNoiseReduction micNoiseReduction;
	private final long timestamp;
	private final Path manifestPath;

	CosmicRecording(
			@Nullable VideoCapture video,
			List<AudioTrack> audio,
			Path outputPath,
			long startedAtMs,
			boolean keepSpeakerTracks,
			PauseState pauseState,
			MicNoiseReduction micNoiseReduction,
			long timestamp,
			Path manifestPath) {
		this.video = video;
		this.audio = List.copyOf(audio);
		this.outputPath = outputPath;
		this.startedAtMs = startedAtMs;
		this.keepSpeakerTracks = keepSpeakerTracks;
		this.pauseState = pauseState;
		this.micNoiseReduction = micNoiseReduction;
		this.timestamp = timestamp;
		this.manifestPath = manifestPath;
	}

	/** The video halves, or null for an audio-only recording. */
	@Nullable VideoCapture video() {
		return video;
	}

	List<AudioTrack> audio() {
		return audio;
	}

	Path outputPath() {
		return outputPath;
	}

	long startedAtMs() {
		return startedAtMs;
	}

	boolean keepSpeakerTracks() {
		return keepSpeakerTracks;
	}

	PauseState pauseState() {
		return pauseState;
	}

	MicNoiseReduction micNoiseReduction() {
		return micNoiseReduction;
	}

	long timestamp() {
		return timestamp;
	}

	Path manifestPath() {
		return manifestPath;
	}

	/** True while a pause is in effect. */
	boolean isPaused() {
		return pauseState.paused().get();
	}

	/** The temp files this recording owns, manifest last. */
	List<Path> tempFiles() {
		List<Path> files = new ArrayList<>();
		for (AudioTrack track : audio) {
			files.add(track.path());
		}
		if (video != null) {
			files.add(video.videoPath());
		}
		files.add(manifestPath);
		return List.copyOf(files);
	}
}
