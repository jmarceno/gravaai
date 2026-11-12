package dev.gravaai.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.result.Result;

/**
 * The headless command line: which arguments select a verb, what the options mean, and which
 * mistakes are refused.
 *
 * <p>The parser is the only part of the CLI that can be wrong in a way a user cannot see — a
 * silently ignored flag records the wrong take — so every rejected case below is a script that
 * would otherwise do something surprising.
 */
class CliCommandTest {

	@TempDir
	Path tempDir;

	private static CliCommand command(String... args) {
		return switch (CliCommand.parse(args)) {
			case Result.Ok<Optional<CliCommand>> ok -> ok.value().orElseThrow(
					() -> new AssertionError("no command for " + String.join(" ", args)));
			case Result.Err<Optional<CliCommand>> error -> throw new AssertionError(
					"expected a command, got: " + error.error());
		};
	}

	private static String usage(String... args) {
		return switch (CliCommand.parse(args)) {
			case Result.Err<Optional<CliCommand>> error -> error.error();
			case Result.Ok<Optional<CliCommand>> ignored -> throw new AssertionError(
					"expected a usage error for " + String.join(" ", args));
		};
	}

	private static Optional<CliCommand> parsed(String... args) {
		return switch (CliCommand.parse(args)) {
			case Result.Ok<Optional<CliCommand>> ok -> ok.value();
			case Result.Err<Optional<CliCommand>> error -> throw new AssertionError(
					"expected argv to parse, got: " + error.error());
		};
	}

	private static CliCommand.Export export(String... args) {
		return assertInstanceOf(CliCommand.Export.class, command(args));
	}

	private static CliCommand.Record record(String... args) {
		return assertInstanceOf(CliCommand.Record.class, command(args));
	}

	@Test
	@DisplayName("argv without a verb is the GUI path, not an error")
	void noVerb() {
		assertEquals(Optional.empty(), parsed());
		assertEquals(Optional.empty(), parsed("--dashboard"));
		assertEquals(Optional.empty(), parsed("/tmp/demo.gravaai"));
	}

	@Test
	@DisplayName("--help, -h and help all print the usage")
	void help() {
		assertInstanceOf(CliCommand.Help.class, command("--help"));
		assertInstanceOf(CliCommand.Help.class, command("-h"));
		assertInstanceOf(CliCommand.Help.class, command("help"));
	}

	@Test
	@DisplayName("export defaults to MP4 at full quality next to the project")
	void exportDefaults() throws Exception {
		Path project = tempDir.resolve("demo.gravaai");
		CliCommand.Export export = export("export", project.toString());
		assertEquals(project, export.project());
		assertEquals(tempDir.resolve("demo.mp4"), export.out(),
				"the default destination is the project's own name with the format's extension");
		assertEquals(CliCommand.Export.Format.MP4, export.format());
		assertEquals(CliCommand.Export.Quality.SOURCE, export.quality());
		assertEquals(Optional.empty(), export.frameRate());
		assertEquals(false, export.overwrite());
	}

	@Test
	@DisplayName("the default destination follows --format")
	void exportDefaultDestinationFollowsFormat() {
		Path project = tempDir.resolve("demo.gravaai");
		assertEquals(tempDir.resolve("demo.gif"),
				export("export", project.toString(), "--format", "gif").out());
	}

	@Test
	@DisplayName("export reads every documented flag")
	void exportFlags() {
		CliCommand.Export export = export("export", "demo.gravaai", "--out", "out/demo.gif",
				"--format", "gif", "--preset", "720p", "--fps", "20", "--overwrite", "--json");
		assertEquals(Path.of("out/demo.gif"), export.out());
		assertEquals(CliCommand.Export.Format.GIF, export.format());
		assertEquals(CliCommand.Export.Quality.GOOD, export.quality());
		assertEquals(Optional.of(20), export.frameRate());
		assertEquals(true, export.overwrite());
		assertEquals(true, export.json());
	}

	@Test
	@DisplayName("a format and a destination that disagree are refused")
	void exportFormatExtensionMismatch() {
		assertTrue(usage("export", "demo.gravaai", "--format", "gif", "--out", "demo.mp4")
				.contains(".gif destination"));
		assertTrue(usage("export", "demo.gravaai", "--out", "demo.mkv")
				.contains(".mp4 destination"));
	}

	@Test
	@DisplayName("an unknown or malformed option is a usage error, never ignored")
	void exportRejections() {
		assertTrue(usage("export").contains("needs a project path"));
		assertTrue(usage("export", "a.gravaai", "b.gravaai").contains("one project path"));
		assertTrue(usage("export", "a.gravaai", "--nope").contains("unknown export option"));
		assertTrue(usage("export", "a.gravaai", "--format", "webm").contains("mp4 or gif"));
		assertTrue(usage("export", "a.gravaai", "--preset", "8k").contains("--preset"));
		assertTrue(usage("export", "a.gravaai", "--fps", "0").contains("--fps"));
		assertTrue(usage("export", "a.gravaai", "--fps", "abc").contains("--fps"));
		assertTrue(usage("export", "a.gravaai", "--out").contains("--out needs"));
	}

	@Test
	@DisplayName("record defaults to no audio, no destination and no duration")
	void recordDefaults() {
		CliCommand.Record record = record("record");
		assertEquals(Optional.empty(), record.duration());
		assertEquals(Optional.empty(), record.out());
		assertEquals(Optional.empty(), record.screen());
		assertEquals(false, record.systemAudio());
		assertEquals(false, record.microphone());
	}

	@Test
	@DisplayName("record reads duration, destination, screen and audio flags")
	void recordFlags() {
		CliCommand.Record record = record("record", "--duration", "30", "--out", "demo.mp4",
				"--screen", "DP-1", "--system-audio", "--mic", "--json");
		assertEquals(Optional.of(30L), record.duration());
		assertEquals(Optional.of(Path.of("demo.mp4")), record.out());
		assertEquals(Optional.of("DP-1"), record.screen());
		assertEquals(true, record.systemAudio());
		assertEquals(true, record.microphone());
		assertEquals(true, record.json());
	}

	@Test
	@DisplayName("durations accept plain seconds, fractions and 1m30s")
	void durations() {
		assertEquals(Optional.of(30L), CliCommand.parseDurationSeconds("30"));
		assertEquals(Optional.of(3L), CliCommand.parseDurationSeconds("2.5"));
		assertEquals(Optional.of(90L), CliCommand.parseDurationSeconds("90s"));
		assertEquals(Optional.of(90L), CliCommand.parseDurationSeconds("1m30s"));
		assertEquals(Optional.of(60L), CliCommand.parseDurationSeconds("1m"));
		assertEquals(Optional.empty(), CliCommand.parseDurationSeconds("0"));
		assertEquals(Optional.empty(), CliCommand.parseDurationSeconds("-5"));
		assertEquals(Optional.empty(), CliCommand.parseDurationSeconds("soon"));
	}

	@Test
	@DisplayName("a recording destination without an extension is refused")
	void recordNeedsExtension() {
		assertTrue(usage("record", "--out", "demo").contains("with an extension"));
	}

	@Test
	@DisplayName("every preset spelling maps to an export tier")
	void presets() {
		assertEquals(CliCommand.Export.Quality.MEDIUM,
				CliCommand.Export.Quality.parse("low").orElseThrow());
		assertEquals(CliCommand.Export.Quality.GOOD,
				CliCommand.Export.Quality.parse("720p").orElseThrow());
		assertEquals(CliCommand.Export.Quality.HIGH,
				CliCommand.Export.Quality.parse("900p").orElseThrow());
		assertEquals(CliCommand.Export.Quality.SOURCE,
				CliCommand.Export.Quality.parse("1080p").orElseThrow());
		assertEquals(CliCommand.Export.Quality.SOURCE,
				CliCommand.Export.Quality.parse("4K").orElseThrow());
		assertEquals(Optional.empty(), CliCommand.Export.Quality.parse("8k"));
	}
}
