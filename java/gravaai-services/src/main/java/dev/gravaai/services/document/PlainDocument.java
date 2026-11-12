package dev.gravaai.services.document;

import java.util.ArrayList;
import java.util.List;

/**
 * A renderable document: a title, a metadata line under it and the body blocks. This is the one
 * shape the Markdown, DOCX and PDF writers consume, so a meeting export can never drift between
 * formats — the only per-format code is how each block is drawn.
 *
 * @param title the document title (never blank; callers pass a fallback)
 * @param metadata short lines shown under the title (date, duration, participants)
 * @param blocks the body, in reading order
 */
public record PlainDocument(String title, List<String> metadata, List<DocumentBlock> blocks) {

	public PlainDocument {
		metadata = List.copyOf(metadata);
		blocks = List.copyOf(blocks);
	}

	/** The body as Markdown, for the export that keeps the notes' original markup. */
	public String toMarkdown() {
		StringBuilder text = new StringBuilder();
		if (!title.isBlank()) {
			text.append("# ").append(title).append("\n\n");
		}
		for (String line : metadata) {
			text.append(line).append('\n');
		}
		if (!metadata.isEmpty()) {
			text.append('\n');
		}
		for (int index = 0; index < blocks.size(); index++) {
			DocumentBlock block = blocks.get(index);
			// Items of one list stay tight; before anything else (and after a list) a blank line
			// keeps Markdown's block boundaries: a heading needs it, and lists need it to end.
			boolean tightListItem = index > 0 && block instanceof DocumentBlock.ListItem
					&& blocks.get(index - 1) instanceof DocumentBlock.ListItem;
			if (index > 0 && !tightListItem) {
				text.append('\n');
			}
			text.append(markdownOf(block)).append('\n');
		}
		return text.toString();
	}

	private static String markdownOf(DocumentBlock block) {
		return switch (block) {
			case DocumentBlock.Heading heading -> "#".repeat(Math.max(1, Math.min(6, heading.level())))
					+ " " + markdownOf(heading.runs());
			case DocumentBlock.Paragraph paragraph -> markdownOf(paragraph.runs());
			case DocumentBlock.Rule rule -> "---";
			case DocumentBlock.Quote quote -> "> " + markdownOf(quote.runs());
			case DocumentBlock.ListItem item -> {
				String marker = switch (item.kind()) {
					case BULLET -> "- ";
					case TASK -> item.checked() ? "- [x] " : "- [ ] ";
					case ORDERED -> item.number() + ". ";
				};
				yield "  ".repeat(item.depth()) + marker + markdownOf(item.runs());
			}
		};
	}

	private static String markdownOf(List<InlineRun> runs) {
		StringBuilder text = new StringBuilder();
		for (InlineRun run : runs) {
			switch (run) {
				case InlineRun.Strong strong ->
						text.append("**").append(markdownOf(strong.runs())).append("**");
				case InlineRun.Emphasis emphasis ->
						text.append("*").append(markdownOf(emphasis.runs())).append("*");
				case InlineRun.Code code -> text.append('`').append(code.text()).append('`');
				case InlineRun.Strike strike ->
						text.append("~~").append(markdownOf(strike.runs())).append("~~");
				case InlineRun.Link link -> text.append('[').append(markdownOf(link.runs()))
						.append("](").append(link.destination()).append(')');
				case InlineRun.Text plain -> text.append(plain.text());
			}
		}
		return text.toString();
	}

	/** The whole document as plain lines, blank-line separated between blocks. */
	public List<String> plainLines() {
		List<String> lines = new ArrayList<>();
		lines.add(title);
		lines.addAll(metadata);
		for (DocumentBlock block : blocks) {
			lines.add(block.plainText());
		}
		return List.copyOf(lines);
	}
}
