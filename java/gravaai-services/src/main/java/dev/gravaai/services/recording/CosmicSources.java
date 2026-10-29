package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.services.sources.SelectedSource;

/**
 * Source-id and output-list helpers from {@code electron/ipc/recording/cosmic.ts}.
 *
 * <p>Pure logic only: the recording pipeline lives in {@link CosmicRecorder}. The helpers are kept
 * together so the capture-selection code can resolve COSMIC outputs without starting a recording.
 */
public final class CosmicSources {

	/** Source ids for direct COSMIC output capture. */
	public static final String COSMIC_SOURCE_PREFIX = "screen:cosmic:";

	/** Frames per second the native helper captures. */
	public static final int CAPTURE_FPS = 60;

	private static final Pattern OUTPUT_LINE = Pattern.compile("^OUTPUT (\\S+) (-?\\d+) (-?\\d+) (\\d+) (\\d+)$");

	private CosmicSources() {
	}

	/** True when the selected source is captured directly from a COSMIC output. */
	public static boolean isCosmicSource(SelectedSource source) {
		return source != null && isCosmicSourceId(source.id());
	}

	/** True when a source id names a COSMIC output. */
	public static boolean isCosmicSourceId(String sourceId) {
		return sourceId != null && sourceId.startsWith(COSMIC_SOURCE_PREFIX);
	}

	/** The output name embedded in a COSMIC source id (empty for a missing id). */
	public static String outputNameForSourceId(@Nullable String sourceId) {
		if (sourceId == null) {
			return "";
		}
		return sourceId.length() <= COSMIC_SOURCE_PREFIX.length()
				? ""
				: sourceId.substring(COSMIC_SOURCE_PREFIX.length());
	}

	/** Parses the helper's {@code --list} stdout: {@code OUTPUT name x y w h} lines, anything else ignored. */
	public static List<CosmicOutput> parseOutputs(String stdout) {
		List<CosmicOutput> outputs = new ArrayList<>();
		if (stdout == null) {
			return outputs;
		}
		for (String line : stdout.split("\n", -1)) {
			Matcher match = OUTPUT_LINE.matcher(line);
			if (match.matches()) {
				outputs.add(new CosmicOutput(
						match.group(1),
						Double.parseDouble(match.group(2)),
						Double.parseDouble(match.group(3)),
						Double.parseDouble(match.group(4)),
						Double.parseDouble(match.group(5))));
			}
		}
		return outputs;
	}

	/** The output containing a pointer position, used to resolve a click on the capture picker. */
	public static @Nullable CosmicOutput outputAt(List<CosmicOutput> outputs, double x, double y) {
		for (CosmicOutput output : outputs) {
			if (x >= output.x() && x < output.x() + output.width()
					&& y >= output.y() && y < output.y() + output.height()) {
				return output;
			}
		}
		return null;
	}
}
