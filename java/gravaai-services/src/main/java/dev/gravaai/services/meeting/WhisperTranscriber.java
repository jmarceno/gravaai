package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.services.meeting.ports.MediaTools;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/**
 * Local transcription with the bundled whisper-cli, ported from
 * electron/ipc/meeting/transcript.ts ({@code transcribeWithWhisper}, {@code probeDurationMs}).
 */
public final class WhisperTranscriber {

	private static final Pattern PROGRESS = Pattern.compile("progress\\s*=\\s*(\\d+)%");
	private static final Duration AUDIO_TIMEOUT = Duration.ofMinutes(30);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

	private final ProcessRunnerPort processes;
	private final MediaTools tools;
	private final Path tempDirectory;

	public WhisperTranscriber(ProcessRunnerPort processes, MediaTools tools, Path tempDirectory) {
		this.processes = processes;
		this.tools = tools;
		this.tempDirectory = tempDirectory;
	}

	/** Runs whisper on the recording's audio (or its two speaker tracks) and parses its JSON. */
	public Transcript.ParsedTranscript transcribe(MeetingAudioInputs inputs, String modelPath,
			String vadModelPath, String language, MeetingStatusCallback onStatus,
			CancelSignal signal) {
		MeetingStatusCallback status = onStatus == null
				? MeetingStatusCallback.IGNORING
				: onStatus;
		Path whisper = tools.whisperCli();
		Path work = createTempDirectory();
		try {
			Path wav = work.resolve("audio.wav");
			status.onStatus("Preparing audio…", null);
			List<String> audioCommand = new ArrayList<>();
			audioCommand.add(tools.ffmpeg().toString());
			audioCommand.addAll(Transcript.buildWhisperInputArgs(inputs, wav));
			ProcessRunnerPort.ProcessResult audio;
			try {
				audio = processes.run(audioCommand, AUDIO_TIMEOUT, Map.of());
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while preparing the audio.",
						interrupted);
			}
			if (audio.exitCode() != 0) {
				throw new IllegalStateException(
						"ffmpeg failed: " + Texts.lastLines(audio.stderr(), 3));
			}

			boolean diarize = inputs.mic() != null && inputs.system() != null;
			String outputBase = work.resolve("out").toString();
			List<String> command = new ArrayList<>();
			command.add(whisper.toString());
			command.addAll(Transcript.whisperMeetingArgs(new WhisperArgsOptions(
					modelPath,
					wav.toString(),
					outputBase,
					language,
					// whisper scales with physical cores, not SMT threads.
					Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)),
					diarize,
					vadModelPath)));
			status.onStatus("Transcribing…", 0);
			runWhisper(whisper, command, status, signal);
			Path json = Path.of(outputBase + ".json");
			try {
				return Transcript.parseWhisperJson(Files.readString(json));
			} catch (IOException error) {
				throw new IllegalStateException("whisper-cli produced no JSON output.", error);
			}
		} finally {
			FileTrees.deleteQuietly(work);
		}
	}

	private void runWhisper(Path whisper, List<String> command, MeetingStatusCallback onStatus,
			CancelSignal signal) {
		String libraryPath = whisper.getParent() == null ? "" : whisper.getParent().toString();
		ProcessRunnerPort.RunningProcess process;
		try {
			process = processes.spawn(command, Map.of("LD_LIBRARY_PATH", libraryPath),
					line -> reportProgress(line, onStatus));
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
		try {
			while (process.isAlive()) {
				if (signal.isCancelled()) {
					process.kill();
					throw new CancelSignal.CancelledException();
				}
				process.waitFor(POLL_INTERVAL);
			}
			int code = process.exitCode().orElse(-1);
			if (code != 0) {
				throw new IllegalStateException("whisper-cli failed (code " + code + "): "
						+ Texts.lastLines(process.stderrTail(), 3));
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			try {
				process.kill();
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			throw new IllegalStateException("Interrupted while transcribing.", interrupted);
		}
	}

	private static void reportProgress(String line, MeetingStatusCallback onStatus) {
		Matcher matcher = PROGRESS.matcher(line);
		Integer last = null;
		while (matcher.find()) {
			last = Integer.valueOf(matcher.group(1));
		}
		if (last != null) {
			onStatus.onStatus("Transcribing…", last);
		}
	}

	/** Duration from ffmpeg's banner, in milliseconds; null when it cannot be read. */
	public @Nullable Long probeDurationMs(Path mediaPath) {
		List<String> command = List.of(tools.ffmpeg().toString(), "-hide_banner", "-i",
				mediaPath.toString());
		ProcessRunnerPort.ProcessResult result;
		try {
			result = processes.run(command, Duration.ofSeconds(30), Map.of());
		} catch (IOException error) {
			return null;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return null;
		}
		Matcher matcher = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")
				.matcher(result.stderr());
		if (!matcher.find()) {
			return null;
		}
		double seconds = Double.parseDouble(matcher.group(3));
		double totalMs = (Double.parseDouble(matcher.group(1)) * 3600.0
				+ Double.parseDouble(matcher.group(2)) * 60.0 + seconds) * 1000.0;
		return Math.round(totalMs);
	}

	private Path createTempDirectory() {
		try {
			return Files.createTempDirectory(tempDirectory, "gravaai-meeting-");
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}
}
