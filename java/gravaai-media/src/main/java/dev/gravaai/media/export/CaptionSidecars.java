package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Caption sidecar writer (port of {@code electron/ipc/register/exportCaptionSidecars.ts}, plan
 * §5.10): writes {@code .srt}/{@code .vtt} next to an exported video, best effort.
 */
public final class CaptionSidecars {

	/** {@code CaptionSidecarCue}. */
	public record Cue(double startMs, double endMs, String text) {}

	/** {@code "srt" | "vtt" | "both"}. */
	public enum Format {
		SRT("srt"),
		VTT("vtt"),
		BOTH("both");

		private final String wire;

		Format(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		public static @Nullable Format fromWire(String value) {
			return "srt".equals(value) ? SRT : "vtt".equals(value) ? VTT : "both".equals(value) ? BOTH : null;
		}
	}

	/** {@code CaptionSidecarPayload}. */
	public record Payload(Format format, List<Cue> cues) {
		public Payload {
			cues = List.copyOf(cues);
		}
	}

	/** {@code CaptionSidecarWriteResult}. */
	public record WriteResult(boolean wroteAny, @Nullable String error) {}

	private CaptionSidecars() {}

	/** {@code normalizeCaptionSidecarCues}. */
	public static List<Cue> normalizeCues(List<Cue> cues) {
		if (cues == null) {
			return List.of();
		}
		List<Cue> normalized = new ArrayList<>();
		for (Cue cue : cues) {
			if (cue == null || !Double.isFinite(cue.startMs()) || !Double.isFinite(cue.endMs())
					|| cue.endMs() <= cue.startMs() || cue.text() == null
					|| cue.text().trim().isEmpty()) {
				continue;
			}
			normalized.add(new Cue(cue.startMs(), cue.endMs(),
					cue.text().replace("\r\n", "\n").trim()));
		}
		return List.copyOf(normalized);
	}

	/** {@code toSrtTimestamp}. */
	public static String toSrtTimestamp(double totalMs) {
		long ms = Math.max(0, Math.round(totalMs));
		long hours = ms / 3_600_000L;
		long minutes = (ms % 3_600_000L) / 60_000L;
		long seconds = (ms % 60_000L) / 1000L;
		long millis = ms % 1000L;
		return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
	}

	/** {@code toVttTimestamp}. */
	public static String toVttTimestamp(double totalMs) {
		long ms = Math.max(0, Math.round(totalMs));
		long hours = ms / 3_600_000L;
		long minutes = (ms % 3_600_000L) / 60_000L;
		long seconds = (ms % 60_000L) / 1000L;
		long millis = ms % 1000L;
		return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
	}

	/** {@code serializeSrt}. */
	public static String serializeSrt(List<Cue> cues) {
		List<Cue> normalized = normalizeCues(cues);
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < normalized.size(); index++) {
			if (index > 0) {
				builder.append('\n').append('\n');
			}
			Cue cue = normalized.get(index);
			builder.append(index + 1).append('\n')
					.append(toSrtTimestamp(cue.startMs())).append(" --> ")
					.append(toSrtTimestamp(cue.endMs())).append('\n')
					.append(cue.text());
		}
		return builder.toString();
	}

	/** {@code serializeVtt}. */
	public static String serializeVtt(List<Cue> cues) {
		List<Cue> normalized = normalizeCues(cues);
		StringBuilder body = new StringBuilder();
		for (int index = 0; index < normalized.size(); index++) {
			if (index > 0) {
				body.append('\n').append('\n');
			}
			Cue cue = normalized.get(index);
			body.append(toVttTimestamp(cue.startMs())).append(" --> ")
					.append(toVttTimestamp(cue.endMs())).append('\n')
					.append(cue.text());
		}
		return "WEBVTT\n\n" + body;
	}

	/** {@code writeCaptionSidecars}. */
	public static void write(Path videoPath, Payload payload) throws IOException {
		if (payload == null || payload.format() == null) {
			return;
		}
		Path basePath = withoutExtension(videoPath);
		if (payload.format() == Format.SRT || payload.format() == Format.BOTH) {
			Files.writeString(Path.of(basePath + ".srt"), serializeSrt(payload.cues()),
					StandardCharsets.UTF_8);
		}
		if (payload.format() == Format.VTT || payload.format() == Format.BOTH) {
			Files.writeString(Path.of(basePath + ".vtt"), serializeVtt(payload.cues()),
					StandardCharsets.UTF_8);
		}
	}

	/** {@code writeCaptionSidecarsBestEffort}. */
	public static WriteResult writeBestEffort(Path videoPath, Payload payload) {
		if (payload == null) {
			return new WriteResult(false, null);
		}
		try {
			write(videoPath, payload);
			return new WriteResult(true, null);
		} catch (IOException error) {
			return new WriteResult(false,
					error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
		}
	}

	/** {@code withCaptionSidecarMessage}. */
	public static String withSidecarMessage(String baseMessage, WriteResult result) {
		if (result == null || result.error() == null) {
			return baseMessage;
		}
		return baseMessage + " Captions could not be saved alongside the video.";
	}

	private static Path withoutExtension(Path file) {
		Path fileName = file.getFileName();
		if (fileName == null) {
			return file;
		}
		String name = fileName.toString();
		int dot = name.lastIndexOf('.');
		String baseName = dot > 0 ? name.substring(0, dot) : name;
		Path parent = file.getParent();
		return parent == null ? Path.of(baseName) : parent.resolve(baseName);
	}
}
