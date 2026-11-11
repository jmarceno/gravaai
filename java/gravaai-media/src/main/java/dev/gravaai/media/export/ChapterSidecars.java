package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import dev.gravaai.core.project.Chapters;

/**
 * Chapter output of an export: the {@code ffmetadata} document the MP4 muxer embeds and the
 * YouTube-ready {@code chapters.txt} sidecar written next to the video.
 *
 * <p>The bundled ffmpeg has both the {@code ffmetadata} demuxer and muxer, so the MP4 stays a
 * single encode: {@link Mp4Encoder} passes the metadata document as a second input and maps its
 * chapters onto the output. The sidecar is written after publish, like the caption sidecars.
 */
public final class ChapterSidecars {

	/** The chapter list of one export; empty means the export has no chapters. */
	public record Payload(List<Chapters.Chapter> chapters) {

		public static final Payload NONE = new Payload(List.of());

		public Payload {
			chapters = chapters == null ? List.of() : List.copyOf(chapters);
		}

		public boolean hasChapters() {
			return !chapters.isEmpty();
		}
	}

	/** The sidecar write result, like {@link CaptionSidecars.WriteResult}. */
	public record WriteResult(boolean wrote, @Nullable String error) {}

	/** The temporary {@code ffmetadata} file name handed to ffmpeg beside an export temp file. */
	static final String METADATA_SUFFIX = ".chapters.txt";

	private ChapterSidecars() {}

	/**
	 * The {@code ffmetadata} document: a {@code ;FFMETADATA1} header, then one {@code [CHAPTER]}
	 * block per chapter. Each chapter ends where the next starts; the last one ends at the export
	 * duration.
	 *
	 * @param durationMs the export length; the closing edge of the final chapter
	 */
	public static String ffmetadata(List<Chapters.Chapter> chapters, long durationMs) {
		StringBuilder text = new StringBuilder(";FFMETADATA1\n");
		long duration = Math.max(1, durationMs);
		for (int index = 0; index < chapters.size(); index++) {
			Chapters.Chapter chapter = chapters.get(index);
			long start = Math.max(0, Math.min(chapter.startMs(), duration - 1));
			long end = index + 1 < chapters.size()
					? Math.max(start + 1, Math.min(chapters.get(index + 1).startMs(), duration))
					: duration;
			text.append("[CHAPTER]\n")
					.append("TIMEBASE=1/1000\n")
					.append("START=").append(start).append('\n')
					.append("END=").append(end).append('\n')
					.append("title=").append(escape(chapter.title())).append('\n');
		}
		return text.toString();
	}

	/**
	 * ffmetadata escaping: {@code \ = ; #} and newlines are backslash-escaped, and a leading
	 * {@code ;} or {@code #} would otherwise start a comment.
	 */
	static String escape(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder escaped = new StringBuilder(value.length() + 8);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '\\', '=', ';', '#' -> escaped.append('\\').append(character);
				case '\n' -> escaped.append("\\\n");
				case '\r' -> { }
				default -> escaped.append(character);
			}
		}
		return escaped.toString();
	}

	/** The YouTube-ready sidecar text: one {@code 00:00 Title} line per chapter. */
	public static String sidecarText(List<Chapters.Chapter> chapters) {
		return Chapters.sidecarText(chapters);
	}

	/** Writes {@code <video base>.chapters.txt} next to the published video, best effort. */
	public static WriteResult writeBestEffort(Path videoPath, Payload payload) {
		if (payload == null || !payload.hasChapters()) {
			return new WriteResult(false, null);
		}
		try {
			Path sidecar = sidecarPath(videoPath);
			Files.writeString(sidecar, sidecarText(payload.chapters()), StandardCharsets.UTF_8);
			return new WriteResult(true, null);
		} catch (IOException error) {
			return new WriteResult(false, error.getMessage() == null
					? error.getClass().getSimpleName() : error.getMessage());
		}
	}

	/** {@code <video base>.chapters.txt}. */
	public static Path sidecarPath(Path videoPath) {
		Path absolute = videoPath.toAbsolutePath().normalize();
		Path fileName = absolute.getFileName();
		String name = fileName == null ? "export" : fileName.toString();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		Path parent = absolute.getParent();
		return parent == null ? Path.of(base + ".chapters.txt")
				: parent.resolve(base + ".chapters.txt");
	}

	/**
	 * Writes the {@code ffmetadata} document next to an export temp file and returns its path, or
	 * null when there is nothing to embed. The caller deletes it after the encode.
	 */
	static @Nullable Path writeMetadataFile(Path exportFile, List<Chapters.Chapter> chapters,
			long durationMs) throws IOException {
		if (chapters == null || chapters.isEmpty()) {
			return null;
		}
		Path metadata = exportFile.resolveSibling(exportFile.getFileName() + METADATA_SUFFIX);
		Files.writeString(metadata, ffmetadata(chapters, durationMs), StandardCharsets.UTF_8);
		return metadata;
	}

	/** {@code withChapterMessage}: appends the sidecar failure to an export message. */
	public static String withSidecarMessage(String baseMessage, WriteResult result) {
		if (result == null || result.error() == null) {
			return baseMessage;
		}
		return baseMessage + " Chapters could not be saved alongside the video.";
	}

	/** The chapter count line used in export summaries ({@code "3 chapters"}). */
	static String describe(int chapterCount) {
		return chapterCount + (chapterCount == 1 ? " chapter" : " chapters");
	}

	/** Locale-stable debug string for a chapter (tests/diagnostics). */
	static String debugLine(Chapters.Chapter chapter) {
		return String.format(Locale.ROOT, "%s %s", Chapters.timestamp(chapter.startMs()),
				chapter.title());
	}
}
