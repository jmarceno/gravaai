package dev.gravaai.services.captions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/**
 * Port of {@code extractCaptionAudioSource} in electron/ipc/captions/generate.ts: decode the first
 * usable audio source to 16 kHz mono PCM WAV with the bundled ffmpeg CLI.
 */
public final class CaptionAudioExtraction {

	private static final Duration TIMEOUT = Duration.ofMinutes(5);

	/** No candidate could be extracted: captions need an audio track. */
	public static final class NoCaptionAudioException extends RuntimeException {
		public NoCaptionAudioException(String message) {
			super(message);
		}
	}

	/** One attempt, for diagnostics. */
	public record Attempt(CaptionAudioCandidate candidate, boolean readable,
			boolean extractedAudio, String error) {
	}

	private final ProcessRunnerPort processes;

	public CaptionAudioExtraction(ProcessRunnerPort processes) {
		this.processes = processes;
	}

	/** The ffmpeg args used for the WAV extraction (identical to Electron, plus optional delay). */
	public static List<String> extractionArgs(Path ffmpeg, CaptionAudioCandidate candidate,
			Path wavPath, long delayMs) {
		List<String> args = new ArrayList<>();
		args.add(ffmpeg.toString());
		args.add("-y");
		args.add("-i");
		args.add(candidate.path());
		args.add("-map");
		args.add("0:a:0");
		args.add("-vn");
		if (delayMs > 0) {
			args.add("-af");
			args.add("adelay=" + delayMs + ":all=1");
		}
		args.add("-ac");
		args.add("1");
		args.add("-ar");
		args.add("16000");
		args.add("-c:a");
		args.add("pcm_s16le");
		args.add(wavPath.toString());
		return List.copyOf(args);
	}

	/**
	 * Extracts the first usable candidate, returning the one that worked.
	 *
	 * @param delayMsFor candidate label to source delay mapping (null when unknown)
	 */
	public CaptionAudioCandidate extract(
			Path ffmpeg,
			Path wavPath,
			List<CaptionAudioCandidate> candidates,
			java.util.function.ToLongFunction<CaptionAudioCandidate> delayMsFor) {
		List<Attempt> attempted = new ArrayList<>();
		for (CaptionAudioCandidate candidate : candidates) {
			try {
				Path source = Path.of(candidate.path());
				if (!Files.isReadable(source)) {
					throw new java.io.FileNotFoundException(source.toString());
				}
				long delayMs = delayMsFor == null ? 0 : delayMsFor.applyAsLong(candidate);
				ProcessRunnerPort.ProcessResult result = processes.run(
						extractionArgs(ffmpeg, candidate, wavPath, delayMs), TIMEOUT, Map.of());
				if (result.exitCode() != 0) {
					throw new IllegalStateException("ffmpeg exited " + result.exitCode());
				}
				attempted.add(new Attempt(candidate, true, true, null));
				return candidate;
			} catch (IOException | RuntimeException error) {
				attempted.add(new Attempt(candidate, true, false, String.valueOf(error)));
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while extracting caption audio.",
						interrupted);
			}
		}
		throw new NoCaptionAudioException(
				"No audio was found to transcribe in the saved recording file. "
						+ "Captions need an audio track. If this recording should have contained "
						+ "sound, the recording was saved without an audio stream.");
	}
}
