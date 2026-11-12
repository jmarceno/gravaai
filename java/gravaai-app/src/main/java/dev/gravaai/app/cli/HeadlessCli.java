package dev.gravaai.app.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import dev.gravaai.app.CliCommand;
import dev.gravaai.core.editor.EditorPreferences;
import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.result.Result;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.core.storage.UserData;

/**
 * Entry point of the headless verbs (card "Headless CLI"): {@code gravaai record} and
 * {@code gravaai export}.
 *
 * <p>{@link dev.gravaai.app.Main} hands every argv here before it touches JavaFX or the
 * single-instance lock, so the verbs work while the GUI is open (they open no windows, so they are
 * not a second instance) and over SSH.
 *
 * <p>Exit codes are the script contract: {@code 0} success, {@code 1} the work failed (no outputs,
 * encoder error, destination exists), {@code 2} the command line is wrong. Every failure prints one
 * clear line on stderr, and with {@code --json} also one {@code {"event":"error"}} line on stdout.
 */
public final class HeadlessCli {

	/** Wrong usage, distinct from a run that failed so scripts can tell them apart. */
	private static final int EXIT_USAGE = 2;

	private HeadlessCli() {}

	/**
	 * Runs the headless command argv names, if any.
	 *
	 * @param args the process arguments
	 * @param userData the user-data directory
	 * @param stdout the machine-output stream
	 * @param stderr the human-output stream
	 * @return empty when argv names no headless verb (the GUI path takes over), or the exit code
	 */
	public static Optional<Integer> run(String[] args, UserData userData, PrintStream stdout,
			PrintStream stderr) {
		Result<Optional<CliCommand>> parsed = CliCommand.parse(args);
		if (parsed instanceof Result.Err<Optional<CliCommand>> error) {
			CliEvents events = new CliEvents(wantsJson(args), stdout, stderr);
			events.emit("error", java.util.Map.of("message", error.error()));
			stderr.println("gravaai: " + error.error());
			stderr.println("Run `gravaai --help` for the usage.");
			return Optional.of(EXIT_USAGE);
		}
		if (!(parsed instanceof Result.Ok<Optional<CliCommand>> ok) || ok.value().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(dispatch(ok.value().get(), userData, stdout, stderr));
	}

	private static int dispatch(CliCommand command, UserData userData, PrintStream stdout,
			PrintStream stderr) {
		return switch (command) {
			case CliCommand.Help ignored -> {
				stdout.println(HELP);
				stdout.flush();
				yield 0;
			}
			case CliCommand.Record request -> new HeadlessRecord(
					new CliEvents(request.json(), stdout, stderr)).run(request, userData);
			case CliCommand.Export request -> new HeadlessExport(
					new CliEvents(request.json(), stdout, stderr)).run(request,
							exportPreferences(userData), extensionsDir(userData));
		};
	}

	/** Whether argv asked for NDJSON, so a usage error is still reported as an event. */
	private static boolean wantsJson(String[] args) {
		for (String arg : args) {
			if ("--json".equals(arg)) {
				return true;
			}
		}
		return false;
	}

	/** The extensions directory, or empty when the user has none installed. */
	private static Optional<Path> extensionsDir(UserData userData) {
		Path directory = userData.extensionsDir();
		return Files.isDirectory(directory) ? Optional.of(directory) : Optional.empty();
	}

	/**
	 * The persisted export preferences, or the built-in defaults.
	 *
	 * <p>{@link EditorPreferences#exportToJson()} plus
	 * {@link ExportPreferences#normalizeExportPreferences} is the one conversion from the stored
	 * editor preferences to an export request; re-listing the eleven export fields here would let
	 * the CLI drift from the export popover.
	 */
	private static ExportPreferences exportPreferences(UserData userData) {
		try {
			AppSettingsStore store = new AppSettingsStore(userData.appSettingsFile());
			var persisted = store.readSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY);
			return persisted == null ? ExportPreferences.defaults() : exportPreferences(
					EditorPreferences.normalize(persisted, EditorPreferences.defaults()));
		} catch (RuntimeException error) {
			System.getLogger("gravaai.cli").log(System.Logger.Level.WARNING,
					"could not read the export preferences, using the defaults: " + error);
			return ExportPreferences.defaults();
		}
	}

	private static ExportPreferences exportPreferences(EditorPreferences preferences) {
		return ExportPreferences.normalizeExportPreferences(preferences.exportToJson());
	}

	/** {@code gravaai --help}. */
	private static final String HELP = """
			Gravaai — local screen recorder and editor.

			Usage:
			  gravaai                            open the app (or forward to the running one)
			  gravaai <file.gravaai|recording>   open a project or recording in the editor
			  gravaai --mcp                      MCP server over stdio (AI control)
			  gravaai record  [options]          record the screen without the GUI
			  gravaai export <project> [options] render a project to MP4 or GIF

			record options:
			  --duration <seconds>   stop after this long (30, 2.5, 90s, 1m30s); default: until Ctrl-C
			  --out <file.mp4>       where to save; default: the recordings folder
			  --screen <name>        which output to record; default: the first. A wrong name lists
			                         the available outputs
			  --system-audio         capture the default output's audio
			  --mic                  capture the default microphone
			  --json                 NDJSON events on stdout

			export options:
			  --out <file>           destination; default: next to the project, same name
			  --format mp4|gif       default: mp4
			  --preset <tier>        medium | good | high | source (aliases: low, 540p, 720p, 900p,
			                         1080p, 4k, original, native); default: source
			  --fps <n>              frame rate override (24, 30 or 60 for MP4; 15, 20, 25 or 30 for GIF)
			  --overwrite, -y        replace an existing destination
			  --json                 NDJSON events on stdout

			Projects and raw recordings both export; a recording is opened as a one-clip project.
			Exports honour the app's export settings (quality, fps, captions) unless overridden here.

			With --json, stdout carries one JSON object per line ({"event":..., ...}) and the last
			line is the result. Without it the written file name is printed on stdout and progress
			goes to stderr.

			Exit codes: 0 success, 1 the recording/export failed, 2 bad usage.
			""";
}
