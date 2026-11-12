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
	 * @param systemAudio capture the default output's audio
	 * @param microphone capture the default microphone
	 * @param json emit NDJSON events on stdout
	 */
	record Record(Optional<Long> duration, Optional<Path> out, Optional<String> screen,
			boolean systemAudio, boolean microphone, boolean json) implements CliCommand {
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
		boolean systemAudio = false;
		boolean microphone = false;
		boolean json = false;
		int index = 1;
		while (index < args.length) {
			String arg = args[index];
			switch (arg) {
				case "--json" -> json = true;
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
		return Result.ok(new Record(duration, out, screen, systemAudio, microphone, json));
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
						if (rate <= 0 || rate > 120) {
							return Result.err("--fps must be between 1 and 120, got \"" + value
									+ "\"");
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
		String remainder = value.trim().toLowerCase(Locale.ROOT);
		String minutes = remainder;
		double total = 0;
		try {
			int minutesMark = remainder.indexOf('m');
			if (minutesMark >= 0) {
				total += Double.parseDouble(remainder.substring(0, minutesMark)) * 60;
				minutes = remainder.substring(minutesMark + 1);
			}
			String seconds = minutes.replace("s", "");
			if (!seconds.isEmpty()) {
				total += Double.parseDouble(seconds);
			}
		} catch (NumberFormatException error) {
			return Optional.empty();
		}
		long whole = Math.round(total);
		return whole > 0 ? Optional.of(whole) : Optional.empty();
	}

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
