package dev.gravaai.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.gravaai.core.result.Result;

/**
 * The headless verbs: {@code gravaai record} and {@code gravaai export} (card "Headless CLI").
 *
 * <p>They run without the GUI (no JavaFX toolkit, no windows) and are the scriptable counterpart of
 * the HUD and the editor's export button. A value here is only the parsed request;
 * {@code dev.gravaai.app.cli.HeadlessCli} runs it.
 *
 * <p>An option the user may leave out is an {@link Optional}, not a nullable field: absent means
 * "use the default the app would use", and every default lives in one place per verb instead of
 * being repeated as null checks at each use.
 *
 * <p>Parsing is strict about unknown flags — a script that misspells {@code --system-audio} must
 * fail, not silently record without audio — so it answers a {@link Result}: the error side is a
 * usage message, which is a different outcome from a failed recording.
 */
public sealed interface CliCommand {

	/** {@code gravaai --help} / {@code gravaai help}. */
	record Help() implements CliCommand {}

	/**
	 * {@code gravaai record}: capture the screen for a bounded time or until SIGINT.
	 *
	 * @param duration seconds to record, or empty to record until interrupted
	 * @param out where to save, or empty for the recordings folder's own name
	 * @param screen the output to capture, or empty for the first one
	 * @param window the window to capture instead of a whole output: an identifier from
	 *     {@code --list-windows}, or a title/app-id fragment to match, or empty to record a screen
	 * @param systemAudio capture the default output's audio
	 * @param microphone capture the default microphone
	 * @param overwrite replace an existing {@code --out} file instead of refusing to start
	 * @param json emit NDJSON events on stdout
	 */
	record Record(Optional<Long> duration, Optional<Path> out, Optional<String> screen,
			Optional<String> window, boolean systemAudio, boolean microphone, boolean overwrite,
			boolean json) implements CliCommand {
	}

	/**
	 * {@code gravaai export <project>}: render a project file to MP4 or GIF.
	 *
	 * @param project the {@code .gravaai} project (or a raw recording, opened as one)
	 * @param out the destination file
	 * @param format {@code mp4} or {@code gif}
	 * @param quality the export quality tier (the export menu's own tiers)
	 * @param frameRate a frame rate override, or empty for the project's setting
	 * @param overwrite replace an existing destination instead of failing
	 * @param json emit NDJSON events on stdout
	 */
	record Export(Path project, Path out, Format format, Quality quality,
			Optional<Integer> frameRate, boolean overwrite, boolean json) implements CliCommand {

		/** {@code --format}. */
		public enum Format {
			MP4("mp4", ".mp4"),
			GIF("gif", ".gif");

			private final String wire;
			private final String extension;

			Format(String wire, String extension) {
				this.wire = wire;
				this.extension = extension;
			}

			public String wire() {
				return wire;
			}

			public String extension() {
				return extension;
			}

			/** The frame rates the export settings accept for this format. */
			public List<Integer> frameRates() {
				int[] rates = this == GIF
						? dev.gravaai.core.editor.ExportPreferences.GIF_FRAME_RATES
						: dev.gravaai.core.editor.ExportPreferences.MP4_FRAME_RATES;
				return java.util.Arrays.stream(rates).boxed().toList();
			}

			boolean allowsFrameRate(int rate) {
				return frameRates().contains(rate);
			}

			/** Parses {@code mp4}/{@code gif}; empty when the word is neither. */
			static Optional<Format> parse(String value) {
				String normalized = value.trim().toLowerCase(Locale.ROOT);
				for (Format format : values()) {
					if (format.wire.equals(normalized)) {
						return Optional.of(format);
					}
				}
				return Optional.empty();
			}
		}

		/**
		 * {@code --preset}: a quality tier. The names are the export menu's own; the resolution
		 * spellings are the same request, because the menu's quality <em>scales</em> the recording —
		 * a 1080p master is the {@code source} tier, not a fixed pixel size.
		 */
		public enum Quality {
			MEDIUM("medium", "low", "540p"),
			GOOD("good", "720p"),
			HIGH("high", "900p"),
			SOURCE("source", "original", "1080p", "1440p", "4k", "native");

			private final String wire;
			private final List<String> aliases;

			Quality(String wire, String... aliases) {
				this.wire = wire;
				this.aliases = List.of(aliases);
			}

			public String wire() {
				return wire;
			}

			/** Parses a tier name or one of its resolution aliases; empty when unknown. */
			static Optional<Quality> parse(String value) {
				String normalized = value.trim().toLowerCase(Locale.ROOT);
				for (Quality quality : values()) {
					if (quality.wire.equals(normalized) || quality.aliases.contains(normalized)) {
						return Optional.of(quality);
					}
				}
				return Optional.empty();
			}

			/** Every accepted spelling, for {@code --help} and error messages. */
			static String accepted() {
				return String.join(", ", java.util.Arrays.stream(values())
						.flatMap(quality -> java.util.stream.Stream.concat(
								java.util.stream.Stream.of(quality.wire), quality.aliases.stream()))
						.toList());
			}
		}
	}

	/**
	 * Parses the headless verbs.
	 *
	 * @param args the process arguments after the executable; only the first one selects the verb
	 * @return the command, an empty optional when argv names no headless verb (the GUI path takes
	 *     over), or a usage message
	 */
	static Result<Optional<CliCommand>> parse(String[] args) {
		if (args.length == 0) {
			return Result.ok(Optional.empty());
		}
		return switch (args[0]) {
			case "--help", "-h", "help" -> Result.ok(Optional.of(new Help()));
			case "record" -> parseRecord(args).map(Optional::of);
			case "export" -> parseExport(args).map(Optional::of);
			default -> Result.ok(Optional.empty());
		};
	}

	private static Result<Record> parseRecord(String[] args) {
		Optional<Long> duration = Optional.empty();
		Optional<Path> out = Optional.empty();
		Optional<String> screen = Optional.empty();
		Optional<String> window = Optional.empty();
		boolean systemAudio = false;
		boolean microphone = false;
		boolean overwrite = false;
		boolean json = false;
		int index = 1;
		while (index < args.length) {
			String arg = args[index];
			switch (arg) {
				case "--json" -> json = true;
				case "--overwrite", "-y" -> overwrite = true;
				case "--system-audio" -> systemAudio = true;
				case "--mic", "--microphone" -> microphone = true;
				case "--out" -> {
					if (++index >= args.length) {
						return Result.err("--out needs a file path");
					}
					String value = args[index];
					if (extensionOf(value).isEmpty()) {
						return Result.err(
								"--out needs a file name with an extension, got \"" + value + "\"");
					}
					out = Optional.of(Path.of(value));
				}
				case "--screen" -> {
					if (++index >= args.length) {
						return Result.err("--screen needs an output name");
					}
					screen = Optional.of(args[index]);
				}
				case "--window" -> {
					if (++index >= args.length) {
						return Result.err(
								"--window needs a window identifier, title or app id");
					}
					window = Optional.of(args[index]);
				}
				case "--duration" -> {
					if (++index >= args.length) {
						return Result.err("--duration needs a number of seconds");
					}
					String value = args[index];
					Optional<Long> parsed = parseDurationSeconds(value);
					if (parsed.isEmpty()) {
						return Result.err("--duration must be a positive number of seconds, got \""
								+ value + "\"");
					}
					duration = parsed;
				}
				default -> {
					return Result.err("unknown record option \"" + arg + "\"");
				}
			}
			index++;
		}
		if (screen.isPresent() && window.isPresent()) {
			// One take records one surface: refusing is honest, silently preferring one would
			// record something the script did not ask for.
			return Result.err("--screen and --window are mutually exclusive");
		}
		return Result.ok(new Record(duration, out, screen, window, systemAudio, microphone,
				overwrite, json));
	}

	private static Result<Export> parseExport(String[] args) {
		Optional<Path> project = Optional.empty();
		Optional<Path> out = Optional.empty();
		Export.Format format = Export.Format.MP4;
		Export.Quality quality = Export.Quality.SOURCE;
		Optional<Integer> frameRate = Optional.empty();
		boolean overwrite = false;
		boolean json = false;
		int index = 1;
		while (index < args.length) {
			String arg = args[index];
			switch (arg) {
				case "--json" -> json = true;
				case "--overwrite", "-y" -> overwrite = true;
				case "--out" -> {
					if (++index >= args.length) {
						return Result.err("--out needs a destination file");
					}
					out = Optional.of(Path.of(args[index]));
				}
				case "--format" -> {
					if (++index >= args.length) {
						return Result.err("--format must be mp4 or gif");
					}
					String value = args[index];
					Optional<Export.Format> parsed = Export.Format.parse(value);
					if (parsed.isEmpty()) {
						return Result.err("--format must be mp4 or gif, got \"" + value + "\"");
					}
					format = parsed.get();
				}
				case "--preset" -> {
					if (++index >= args.length) {
						return Result.err("--preset must be one of " + Export.Quality.accepted());
					}
					String value = args[index];
					Optional<Export.Quality> parsed = Export.Quality.parse(value);
					if (parsed.isEmpty()) {
						return Result.err("--preset must be one of " + Export.Quality.accepted()
								+ ", got \"" + value + "\"");
					}
					quality = parsed.get();
				}
				case "--fps" -> {
					if (++index >= args.length) {
						return Result.err("--fps needs a frame rate");
					}
					String value = args[index];
					try {
						int rate = Integer.parseInt(value.trim());
						if (rate <= 0) {
							return Result.err("--fps must be a positive whole number, got \""
									+ value + "\"");
						}
						frameRate = Optional.of(rate);
					} catch (NumberFormatException error) {
						return Result.err(
								"--fps must be a positive whole number, got \"" + value + "\"");
					}
				}
				default -> {
					if (arg.startsWith("-")) {
						return Result.err("unknown export option \"" + arg + "\"");
					}
					if (project.isPresent()) {
						return Result.err(
								"export takes one project path, got a second: \"" + arg + "\"");
					}
					project = Optional.of(Path.of(arg));
				}
			}
			index++;
		}
		if (project.isEmpty()) {
			return Result.err(
					"export needs a project path: gravaai export <project.gravaai> --out <file>");
		}
		if (frameRate.isPresent() && !format.allowsFrameRate(frameRate.get())) {
			// The export settings only store these rates; anything else would silently fall back
			// to the default rate instead of the one the script asked for.
			return Result.err("--fps for " + format.wire() + " must be one of "
					+ format.frameRates() + ", got " + frameRate.get());
		}
		Path destination = out.isPresent()
				? out.get()
				: withExtension(project.get(), format.extension());
		Optional<String> extension = extensionOf(destination.toString());
		if (extension.isEmpty() || !extension.get().equalsIgnoreCase(format.wire())) {
			return Result.err("a " + format.wire() + " export needs a " + format.extension()
					+ " destination, got \"" + destination + "\"");
		}
		return Result.ok(new Export(project.get(), destination, format, quality, frameRate,
				overwrite, json));
	}

	/** {@code 30}, {@code 2.5}, {@code 90s}, {@code 1m30s} to whole seconds; empty when unusable. */
	static Optional<Long> parseDurationSeconds(String value) {
		java.util.regex.Matcher match = DURATION.matcher(value.trim().toLowerCase(Locale.ROOT));
		if (!match.matches()) {
			// "1s30", "1ms" or "-5" are not durations; guessing would record for the wrong time.
			return Optional.empty();
		}
		// Matcher.group answers null for a group that did not take part in the match.
		double minutes = Optional.ofNullable(match.group(1)).map(Double::parseDouble).orElse(0.0);
		double seconds = Optional.ofNullable(match.group(2)).map(Double::parseDouble).orElse(0.0);
		long whole = Math.round(minutes * 60 + seconds);
		return whole > 0 ? Optional.of(whole) : Optional.empty();
	}

	/** {@code [<n>m][<n>[s]]}: optional minutes, then optional seconds with an optional unit. */
	java.util.regex.Pattern DURATION = java.util.regex.Pattern.compile(
			"(?:(\\d+(?:\\.\\d+)?)m)?(?:(\\d+(?:\\.\\d+)?)s?)?");

	/** The extension without the dot; empty when the name has none. */
	static Optional<String> extensionOf(String path) {
		int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		String name = path.substring(slash + 1);
		int dot = name.lastIndexOf('.');
		return dot > 0 && dot < name.length() - 1
				? Optional.of(name.substring(dot + 1))
				: Optional.empty();
	}

	/** {@code demo.gravaai} to {@code demo.mp4}: a destination next to the given file. */
	private static Path withExtension(Path file, String extension) {
		Path absolute = file.toAbsolutePath().normalize();
		String name = absolute.getFileName().toString();
		Optional<String> existing = extensionOf(name);
		String base = existing.isPresent()
				? name.substring(0, name.length() - existing.get().length() - 1)
				: name;
		Path parent = absolute.getParent();
		return (parent == null ? Path.of("") : parent).resolve(base + extension);
	}
}
