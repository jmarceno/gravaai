package dev.gravaai.services.meeting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
 * <p>All three formats are rendered from one {@link PlainDocument}, so they carry exactly the same
 * headings and lists.
 *
 * <p>The file is written next to its destination and moved into place only once it is complete,
 * so a failed export never leaves a truncated document over an earlier one.
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
	 * @param untitledLabel the localized label of a meeting with no title ("Meeting"); the date is
	 *     appended to it when the meeting has one
	 */
	public Result<Written> export(Path mediaPath, Request request, Path destination,
			String untitledLabel) {
		try {
			if (!(service.getFiles(mediaPath) instanceof Result.Ok<MeetingService.FilesView> files)) {
				return Result.err("Could not read the meeting files.");
			}
			return switch (build(files.value(), request, untitledLabel)) {
				case Result.Err<PlainDocument> error -> Result.err(error.error());
				case Result.Ok<PlainDocument> document -> {
					write(document.value(), request.format(), destination);
					yield Result.ok(new Written(destination, request.format(),
							document.value().blocks().size()));
				}
			};
		} catch (IOException | RuntimeException error) {
			return Result.err(message(error));
		}
	}

	/**
	 * Builds the document for already-read meeting files. Pure: no file is written, so the document
	 * can be inspected (and the writers tested) without a service.
	 *
	 * @param files the meeting's notes, transcript and metadata
	 * @param request the transcript choice (the format does not change the document)
	 * @param untitledLabel the label for a meeting with no title ("Meeting")
	 * @return the document, or the reason there is nothing to export
	 */
	public static Result<PlainDocument> build(MeetingService.FilesView files, Request request,
			String untitledLabel) {
		Optional<String> notes = nonBlank(files.notes());
		Optional<String> transcript = nonBlank(files.transcript());
		if (notes.isEmpty() && transcript.isEmpty()) {
			return Result.err("This meeting has no notes or transcript to export yet.");
		}
		if (request.includeTranscript() && transcript.isEmpty()) {
			return Result.err("This meeting has no transcript to include.");
		}
		List<DocumentBlock> blocks = new ArrayList<>();
		notes.ifPresent(markdown -> blocks.addAll(MarkdownDocumentParser.parseBlocks(markdown)));
		if (request.includeTranscript()) {
			if (!blocks.isEmpty()) {
				blocks.add(new DocumentBlock.Rule());
			}
			blocks.add(new DocumentBlock.Heading(1,
					List.of(new InlineRun.Text(TRANSCRIPT_HEADING))));
			transcript.ifPresent(markdown -> blocks.addAll(MarkdownDocumentParser.parseBlocks(markdown)));
		}
		return Result.ok(new PlainDocument(
				MeetingTexts.meetingTitle(Optional.of(files.meta()), OptionalLong.empty(),
						untitledLabel),
				metadata(files.meta(), transcript.orElse("")), blocks));
	}

	private static Optional<String> nonBlank(@org.jspecify.annotations.Nullable String text) {
		return Optional.ofNullable(text).filter(value -> !value.isBlank());
	}

	/**
	 * The header line under the title: when the meeting was, how long it ran and who spoke. The
	 * speakers come from the transcript's own labels, so the header can never list someone who is
	 * not in the notes.
	 */
	static List<String> metadata(MeetingMeta meta, String transcript) {
		List<String> metadata = new ArrayList<>();
		MeetingTexts.formatDateTime(meta.createdAt()).ifPresent(metadata::add);
		MeetingTexts.formatDuration(meta.durationMs()).ifPresent(metadata::add);
		Set<String> speakers = speakers(transcript);
		if (!speakers.isEmpty()) {
			metadata.add(String.join(", ", speakers));
		}
		return List.copyOf(metadata);
	}

	/** The distinct speaker labels the transcript uses, in first-seen order. */
	static Set<String> speakers(String transcript) {
		Set<String> speakers = new LinkedHashSet<>();
		for (String line : transcript.split("\n")) {
			speakerLabel(line).ifPresent(speakers::add);
		}
		return speakers;
	}

	/**
	 * The bold speaker label of a transcript line ({@code [00:01:05] **Me:** text}); empty when the
	 * line carries none.
	 */
	private static Optional<String> speakerLabel(String line) {
		int start = line.indexOf("**");
		if (start < 0) {
			return Optional.empty();
		}
		int end = line.indexOf("**", start + 2);
		if (end < 0) {
			return Optional.empty();
		}
		String label = line.substring(start + 2, end).strip();
		if (label.endsWith(":")) {
			label = label.substring(0, label.length() - 1).strip();
		}
		return label.isEmpty() ? Optional.empty() : Optional.of(label);
	}

	/**
	 * Writes the document to a sibling temp file, then moves it over {@code destination}: the move
	 * is the only step that touches an existing file, so an encoder or font failure halfway leaves
	 * whatever was there before intact.
	 */
	private static void write(PlainDocument document, DocumentFormat format, Path destination)
			throws IOException {
		Path target = destination.toAbsolutePath().normalize();
		Path directory = target.getParent();
		Files.createDirectories(directory);
		Path temporary = Files.createTempFile(directory, ".gravaai-export-", ".part");
		try {
			try (OutputStream out = Files.newOutputStream(temporary)) {
				switch (format) {
					case MARKDOWN -> out.write(document.toMarkdown().getBytes(StandardCharsets.UTF_8));
					case DOCX -> DocxWriter.write(document, out);
					case PDF -> PdfWriter.write(document, out);
				}
			}
			try {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
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
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.toString() : message;
	}
}
