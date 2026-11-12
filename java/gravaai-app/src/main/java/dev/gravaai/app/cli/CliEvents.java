package dev.gravaai.app.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import dev.gravaai.core.json.Json;

/**
 * Output of the headless verbs (card "Headless CLI"): NDJSON on stdout with {@code --json},
 * human-readable progress on stderr otherwise.
 *
 * <p>The split matters to a script. In {@code --json} mode stdout carries <em>only</em> one JSON
 * object per line and every human line stays on stderr. Without it the written file name goes to
 * stdout — so {@code out=$(gravaai export …)} works — and progress goes to stderr, keeping that
 * value clean.
 *
 * <p>Progress is throttled to whole percentage points: a long export would otherwise emit thousands
 * of near-identical lines.
 */
public final class CliEvents {

	private final boolean json;
	private final PrintStream stdout;
	private final PrintStream stderr;
	private int lastPercent = -1;
	private Optional<String> lastDetail = Optional.empty();

	public CliEvents(boolean json, PrintStream stdout, PrintStream stderr) {
		this.json = json;
		this.stdout = stdout;
		this.stderr = stderr;
	}

	/** Emits one event with no fields beyond its name. */
	public void emit(String event) {
		emit(event, Map.of());
	}

	/** Emits one event; fields keep their insertion order in the JSON line. */
	public void emit(String event, Map<String, ?> fields) {
		if (!json) {
			return;
		}
		Map<String, Object> line = new LinkedHashMap<>();
		line.put("event", event);
		line.putAll(fields);
		// Compact JSON, one line, flushed: a reader piping into jq must see each event at once.
		// Json.stringify is the app's pretty format (it mirrors JSON.stringify(v, null, 2)), so the
		// NDJSON line uses the mapper's compact writer, as the agent control socket does.
		stdout.println(Json.MAPPER.writeValueAsString(line));
		stdout.flush();
	}

	/**
	 * Reports progress at whole per cent.
	 *
	 * @param percent how far the work got
	 * @param detail what it is doing; empty means "nothing new to say". A detail is carried on the
	 *     line where it first appears and then dropped, so a phase change is news and a long run of
	 *     percentages stays one column
	 */
	public void progress(double percent, Optional<String> detail) {
		int whole = (int) Math.floor(Math.max(0, Math.min(100, percent)));
		boolean newDetail = detail.isPresent() && !detail.equals(lastDetail);
		if (whole == lastPercent && !newDetail) {
			return;
		}
		lastPercent = whole;
		lastDetail = detail;
		if (json) {
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("percent", whole);
			if (newDetail) {
				fields.put("detail", detail.get());
			}
			emit("progress", fields);
			return;
		}
		stderr.println(newDetail ? whole + "% " + detail.get() : whole + "%");
	}

	/** A human-readable line on stderr; never part of the machine output. */
	public void note(String message) {
		stderr.println(message);
	}

	/**
	 * The final result: the written file plus its facts.
	 *
	 * @param path the file that was produced
	 * @param fields what the caller knows about it (bytes, frames, …), in insertion order
	 */
	public void result(Path path, Map<String, ?> fields) {
		Map<String, Object> done = new LinkedHashMap<>();
		done.put("path", path.toString());
		done.putAll(fields);
		if (json) {
			emit("done", done);
			return;
		}
		stdout.println(path);
		stdout.flush();
	}
}
