package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.OptionalLong;

import dev.gravaai.media.export.VideoEncoderSpec;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * The screen-recording encoder: one ffmpeg process that a failed GPU session may replace once with
 * libx264 (card "GPU hardware encoding for export and recording").
 *
 * <p>An H.264 hardware session opens when the first frame reaches the encoder, so a driver that
 * rejects it only shows up after frames already flow. Waiting for that before the copy starts
 * would stall the capture helper and shift the video against the audio tracks and the cursor
 * telemetry, so the copy thread starts at once and asks this class to swap the encoder when a
 * write fails early ({@link #canFallBack()}). Every caller holds this one object, so the stop and
 * discard paths always reach whichever process is current.
 */
final class RecordingEncoder implements SpawnedProcess {

	/** Starts one encoder process for a spec. */
	@FunctionalInterface
	interface Starter {
		SpawnedProcess start(VideoEncoderSpec spec) throws IOException;
	}

	/**
	 * How long after the start a failed hardware session may still be replaced. The replacement
	 * rewrites the video file from its first frame, so only the opening seconds are worth trading;
	 * past that a partial recording (short clusters) is worth more than a restart.
	 */
	static final long FALLBACK_WINDOW_MS = 5_000;

	private final Starter starter;
	private final long startedAtMs;
	private volatile SpawnedProcess process;
	private volatile VideoEncoderSpec spec;
	private volatile OptionalLong restartedAtMs = OptionalLong.empty();

	private RecordingEncoder(Starter starter, SpawnedProcess process, VideoEncoderSpec spec,
			long startedAtMs) {
		this.starter = starter;
		this.process = process;
		this.spec = spec;
		this.startedAtMs = startedAtMs;
	}

	/** Starts the encoder on {@code spec}. */
	static RecordingEncoder start(VideoEncoderSpec spec, Starter starter) throws IOException {
		return new RecordingEncoder(starter, starter.start(spec), spec, System.currentTimeMillis());
	}

	/** The encoder that is running now (what the logs and the tray name). */
	VideoEncoderSpec spec() {
		return spec;
	}

	/** When the libx264 replacement took over, if a hardware session was replaced. */
	OptionalLong restartedAtMs() {
		return restartedAtMs;
	}

	/** True while a failed write may still move this recording onto libx264. */
	boolean canFallBack() {
		return spec.hardware() && restartedAtMs.isEmpty()
				&& System.currentTimeMillis() - startedAtMs < FALLBACK_WINDOW_MS;
	}

	/**
	 * Replaces the failed hardware process with libx264 and returns the new stdin.
	 *
	 * @throws IOException when libx264 cannot start either (the recording then ends as before)
	 */
	OutputStream fallBack() throws IOException {
		SpawnedProcess failed = process;
		failed.destroyForcibly();
		SpawnedProcess replacement = starter.start(VideoEncoderSpec.SOFTWARE);
		OutputStream stdin = replacement.stdin();
		if (stdin == null) {
			replacement.destroyForcibly();
			throw new IOException("The libx264 recording encoder has no stdin pipe");
		}
		process = replacement;
		spec = VideoEncoderSpec.SOFTWARE;
		restartedAtMs = OptionalLong.of(System.currentTimeMillis());
		return stdin;
	}

	@Override
	public @Nullable OutputStream stdin() {
		return process.stdin();
	}

	@Override
	public @Nullable InputStream stdout() {
		return process.stdout();
	}

	@Override
	public @Nullable InputStream stderr() {
		return process.stderr();
	}

	@Override
	public long pid() {
		return process.pid();
	}

	@Override
	public boolean isAlive() {
		return process.isAlive();
	}

	@Override
	public int waitFor() throws InterruptedException {
		// A swap during the wait leaves the caller on the replaced process: wait for the new one.
		SpawnedProcess waited;
		int code;
		do {
			waited = process;
			code = waited.waitFor();
		} while (waited != process);
		return code;
	}

	@Override
	public void destroy() {
		process.destroy();
	}

	@Override
	public void destroyForcibly() {
		process.destroyForcibly();
	}
}
