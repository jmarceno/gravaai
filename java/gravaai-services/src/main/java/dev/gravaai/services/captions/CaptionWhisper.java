package dev.gravaai.services.captions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/**
 * Port of the whisper invocation in electron/ipc/captions/generate.ts: exactly today's args
 * ({@code -m <model> -f <wav> -osrt -of <base> -l <lang> -np -mc 0}) plus {@code -ojf} for word
 * timings, with the SRT-only retry for older runtimes.
 */
public final class CaptionWhisper {

	private static final Duration TIMEOUT = Duration.ofMinutes(30);
	private static final Pattern PROGRESS = Pattern.compile("progress\\s*=\\s*(\\d+)%");

	private final ProcessRunnerPort processes;

	public CaptionWhisper(ProcessRunnerPort processes) {
		this.processes = processes;
	}

	/** The whisper base args shared by the JSON and SRT-only runs. */
	public static List<String> baseArgs(
			Path modelPath, Path wavPath, String outputBase, String language) {
		String requested = language == null || language.trim().isEmpty()
				? "auto"
				: language.trim();
		List<String> args = new ArrayList<>();
		args.add("-m");
		args.add(modelPath.toString());
		args.add("-f");
		args.add(wavPath.toString());
		args.add("-osrt");
		args.add("-of");
		args.add(outputBase);
		args.add("-l");
		args.add(requested);
		args.add("-np");
		args.add("-mc");
		args.add("0");
		return List.copyOf(args);
	}

	/** {@code baseArgs} plus {@code -ojf}; returns the arguments in the same order Electron uses. */
	public static List<String> argsWithJson(
			Path modelPath, Path wavPath, String outputBase, String language) {
		List<String> args = new ArrayList<>(baseArgs(modelPath, wavPath, outputBase, language));
		args.add("-ojf");
		return List.copyOf(args);
	}

	/**
	 * Runs whisper and returns the output base, retrying SRT-only when the runtime rejects
	 * {@code -ojf}. {@code jsonEnabled} is false after a fallback.
	 */
	public Result transcribe(
			Path whisperExecutable,
			Path modelPath,
			Path wavPath,
			Path outputBase,
			String language,
			java.util.function.IntConsumer progress) {
		Path whisper = whisperExecutable.toAbsolutePath().normalize();
		try {
			execute(whisper, argsWithJson(modelPath, wavPath, outputBase.toString(), language),
					progress);
			return new Result(outputBase, true);
		} catch (IllegalStateException error) {
			if (!CaptionParser.shouldRetryWhisperWithoutJson(error.getMessage())) {
				throw error;
			}
			execute(whisper, baseArgs(modelPath, wavPath, outputBase.toString(), language),
					progress);
			return new Result(outputBase, false);
		}
	}

	/** Caption output base plus whether the JSON file is expected. */
	public record Result(Path outputBase, boolean jsonEnabled) {
	}

	private void execute(Path whisper, List<String> args, java.util.function.IntConsumer progress) {
		List<String> command = new ArrayList<>();
		command.add(whisper.toString());
		command.addAll(args);
		String libraryPath = whisper.getParent() == null ? "" : whisper.getParent().toString();
		try {
			ProcessRunnerPort.RunningProcess process = processes.spawn(
					command, Map.of("LD_LIBRARY_PATH", libraryPath), line -> {
						Matcher matcher = PROGRESS.matcher(line);
						Integer last = null;
						while (matcher.find()) {
							last = Integer.valueOf(matcher.group(1));
						}
						if (last != null && progress != null) {
							progress.accept(last);
						}
					});
			while (process.isAlive()) {
				process.waitFor(Duration.ofMillis(200));
			}
			OptionalInt exitCode = process.exitCode();
			int code = exitCode.isPresent() ? exitCode.getAsInt() : -1;
			if (code != 0) {
				throw new IllegalStateException("whisper-cli failed (code " + code + "): "
						+ lastLines(process.stderrTail(), 3));
			}
		} catch (IOException error) {
			throw new IllegalStateException("whisper-cli could not start: " + error, error);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while running whisper-cli.", interrupted);
		}
	}

	private static String lastLines(String text, int count) {
		String[] lines = (text == null ? "" : text).split("\\R");
		int start = Math.max(0, lines.length - count);
		return String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length));
	}

	/** Resolves whisper-cli: bundled path first, then {@code WHISPER_CPP_PATH}, then PATH. */
	public static Path resolveExecutable(Path bundled, ProcessRunnerPort processes) {
		if (bundled != null && Files.isRegularFile(bundled) && Files.isExecutable(bundled)) {
			return bundled.toAbsolutePath().normalize();
		}
		String environment = System.getenv("WHISPER_CPP_PATH");
		if (environment != null && !environment.isBlank()) {
			Path candidate = Path.of(environment.trim());
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		for (String name : List.of("whisper-cli", "whisper-cpp", "whisper", "main")) {
			try {
				ProcessRunnerPort.ProcessResult result = processes.run(
						List.of("which", name), Duration.ofSeconds(5), Map.of());
				if (result.exitCode() == 0) {
					String resolved = result.stdout().lines()
							.map(String::trim).filter(line -> !line.isEmpty())
							.findFirst().orElse("");
					if (!resolved.isEmpty()) {
						Path candidate = Path.of(resolved);
						if (Files.isExecutable(candidate)) {
							return candidate.toAbsolutePath().normalize();
						}
					}
				}
			} catch (IOException | InterruptedException error) {
				if (error instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
			}
		}
		throw new IllegalStateException(
				"No Whisper runtime was found for " + System.getProperty("os.name") + "/"
						+ System.getProperty("os.arch") + ". This Gravaai build is missing its "
						+ "bundled caption runtime. Reinstall or update Gravaai, or select a "
						+ "whisper-cli executable in Caption settings.");
	}
}
