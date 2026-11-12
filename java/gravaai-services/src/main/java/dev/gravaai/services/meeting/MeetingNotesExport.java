package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.document.DocumentBlock;
import dev.gravaai.services.document.DocumentFormat;
import dev.gravaai.services.document.DocxWriter;
import dev.gravaai.services.document.InlineRun;
import dev.gravaai.services.document.MarkdownDocumentParser;
import dev.gravaai.services.document.PdfWriter;
import dev.gravaai.services.document.PlainDocument;

/**
 * Exports a meeting's notes as a document: the title, the date/duration line, the notes themselves
 * and, when asked, the full transcript as an appendix.
 *
 * <p>DOCX and PDF are rendered from one {@link PlainDocument}, so both carry exactly the same
 * headings and lists. Markdown keeps the notes file's own markup, because it is what people paste
 * into other tools.
 */
public final class MeetingNotesExport {

	/** What to export: the format and whether the transcript is appended. */
	public record Request(DocumentFormat format, boolean includeTranscript) {
	}

	/** The written file plus what went into it (used by the toast and by tests). */
	public record Written(Path file, DocumentFormat format, int blockCount) {
	}

	/** The heading the transcript appendix opens with. */
	private static final String TRANSCRIPT_HEADING = "Transcript";

	private final MeetingService service;

	MeetingNotesExport(MeetingService service) {
		this.service = service;
	}

	/**
	 * Builds the document for a meeting and writes it to {@code destination}.
	 *
	 * @param mediaPath the meeting recording
	 * @param request the format and whether to include the transcript
	 * @param destination the file to write (created, parents included)
	 * @param fallbackTitle the localized label used when the meeting has no title and no date
	 */
	public Result<Written> export(Path mediaPath, Request request, Path destination,
			String fallbackTitle) {
		try {
			MeetingService.FilesView files = service.getFiles(mediaPath).orNull();
			if (files == null) {
				return Result.err("Could not read the meeting files.");
			}
			Result<PlainDocument> built = build(files, request, fallbackTitle);
			if (built.isErr()) {
				return Result.err(built.errorMessage());
			}
			PlainDocument document = built.orElse(new PlainDocument(fallbackTitle, List.of(),
					List.of()));
			write(document, request.format(), destination);
			return Result.ok(new Written(destination, request.format(), document.blocks().size()));
		} catch (IOException error) {
			return Result.err(message(error));
		} catch (RuntimeException error) {
			return Result.err(message(error));
		}
	}

	/**
	 * Builds the document for already-read meeting files. Pure: no file is written, so the document
	 * can be inspected (and the writers tested) without a service.
	 *
	 * @param files the meeting's notes, transcript and metadata
	 * @param request the format (only its transcript flag matters here) and the transcript choice
	 * @param fallbackTitle the label for a meeting with no title and no date ("Meeting")
	 * @return the document, or the reason there is nothing to export
	 */
	public static Result<PlainDocument> build(MeetingService.FilesView files, Request request,
			String fallbackTitle) {
		String notes = files.notes();
		String transcript = files.transcript();
		boolean hasNotes = notes != null && !notes.isBlank();
		boolean hasTranscript = transcript != null && !transcript.isBlank();
		if (!hasNotes && !hasTranscript) {
			return Result.err("This meeting has no notes or transcript to export yet.");
		}
		if (request.includeTranscript() && !hasTranscript) {
			return Result.err("This meeting has no transcript to include.");
		}
		List<DocumentBlock> blocks = new ArrayList<>();
		if (hasNotes) {
			blocks.addAll(MarkdownDocumentParser.parseBlocks(notes));
		}
		if (request.includeTranscript()) {
			if (!blocks.isEmpty()) {
				blocks.add(new DocumentBlock.Rule());
			}
			blocks.add(new DocumentBlock.Heading(1,
					List.of(new InlineRun.Text(TRANSCRIPT_HEADING))));
			blocks.addAll(MarkdownDocumentParser.parseBlocks(transcript));
		}
		return Result.ok(new PlainDocument(
				MeetingTexts.meetingTitle(files.meta(), null, fallbackTitle),
				metadata(files.meta(), transcript), blocks));
	}

	/**
	 * The header line under the title: when the meeting was, how long it ran and who spoke. The
	 * speakers come from the transcript's own labels, so the header can never list someone who is
	 * not in the notes.
	 */
	static List<String> metadata(@Nullable MeetingMeta meta, @Nullable String transcript) {
		List<String> metadata = new ArrayList<>();
		if (meta != null) {
			String date = MeetingTexts.formatDateTime(meta.createdAt());
			if (date != null) {
				metadata.add(date);
			}
			String duration = MeetingTexts.formatDuration(meta.durationMs());
			if (duration != null) {
				metadata.add(duration);
			}
		}
		Set<String> speakers = speakers(transcript);
		if (!speakers.isEmpty()) {
			metadata.add(String.join(", ", speakers));
		}
		return List.copyOf(metadata);
	}

	/** The distinct speaker labels the transcript uses, in first-seen order. */
	static Set<String> speakers(@Nullable String transcript) {
		Set<String> speakers = new LinkedHashSet<>();
		if (transcript == null || transcript.isBlank()) {
			return speakers;
		}
		for (String line : transcript.split("\n")) {
			String label = speakerLabel(line);
			if (label != null) {
				speakers.add(label);
			}
		}
		return speakers;
	}

	/**
	 * The bold speaker label of a transcript line ({@code [00:01:05] **Me:** text}), or null when
	 * the line carries none.
	 */
	private static @Nullable String speakerLabel(String line) {
		int start = line.indexOf("**");
		if (start < 0) {
			return null;
		}
		int end = line.indexOf("**", start + 2);
		if (end < 0) {
			return null;
		}
		String label = line.substring(start + 2, end).strip();
		if (label.endsWith(":")) {
			label = label.substring(0, label.length() - 1).strip();
		}
		return label.isEmpty() ? null : label;
	}

	private static void write(PlainDocument document, DocumentFormat format, Path destination)
			throws IOException {
		Path parent = destination.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		switch (format) {
			case MARKDOWN -> Files.writeString(destination, document.toMarkdown(),
					StandardCharsets.UTF_8);
			case DOCX -> {
				try (OutputStream out = Files.newOutputStream(destination)) {
					DocxWriter.write(document, out);
				}
			}
			case PDF -> {
				try (OutputStream out = Files.newOutputStream(destination)) {
					PdfWriter.write(document, out);
				}
			}
		}
	}

	/**
	 * The file name a meeting exports to: the title made file-safe plus the format's extension.
	 *
	 * @param title the meeting title
	 * @param format the intended format
	 */
	public static String defaultFileName(String title, DocumentFormat format) {
		return dev.gravaai.services.automations.AutomationText.sanitizeFileName(title) + "."
				+ format.extension();
	}

	private static String message(Exception error) {
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}
}
