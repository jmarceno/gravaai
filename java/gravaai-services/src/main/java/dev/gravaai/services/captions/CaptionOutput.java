package dev.gravaai.services.captions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/** Port of electron/ipc/captions/output.ts: read whisper's JSON timing, fall back to SRT. */
public final class CaptionOutput {

	private static final System.Logger LOG =
			System.getLogger(CaptionOutput.class.getName());

	private CaptionOutput() {
	}

	/** {@code readWhisperCaptionOutput}. */
	public static List<CaptionCue> readWhisperCaptionOutput(Path outputBase, boolean jsonEnabled) {
		if (jsonEnabled) {
			String json = "";
			try {
				json = Files.readString(Path.of(outputBase + ".json"));
			} catch (NoSuchFileException ignored) {
				// Older whisper builds may not emit the JSON file.
			} catch (IOException error) {
				throw new UncheckedIOException(error);
			}
			List<CaptionCue> timed = json.isEmpty()
					? List.of()
					: CaptionParser.parseWhisperJsonCues(json);
			if (!timed.isEmpty()) {
				return timed;
			}
			LOG.log(System.Logger.Level.WARNING,
					"No usable JSON timing output; falling back to SRT.");
		}
		try {
			return CaptionParser.parseSrtCues(Files.readString(Path.of(outputBase + ".srt")));
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}
}
