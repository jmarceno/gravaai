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
		}		DocumentBlock previous = null;
		for (DocumentBlock block : blocks) {
			// Items of one list stay tight; before anything else (and after a list) a blank line
			// keeps Markdown's block boundaries: a heading needs it, and lists need it to end.
			boolean listItem = block instanceof DocumentBlock.ListItem;
			boolean previousListItem = previous instanceof DocumentBlock.ListItem;
			if (previous != null && (!listItem || !previousListItem)) {
				text.append('\n');
			}
			text.append(markdownOf(block)).append('\n');
			previous = block;
		}
		return text.toString();
	}

	private static String markdownOf(DocumentBlock block) {
		if (block instanceof DocumentBlock.Heading heading) {
			return "#".repeat(Math.max(1, Math.min(6, heading.level()))) + " "
					+ markdownOf(heading.runs());
		}
		if (block instanceof DocumentBlock.Paragraph paragraph) {
			return markdownOf(paragraph.runs());
		}
		if (block instanceof DocumentBlock.Rule) {
			return "---";
		}
		if (block instanceof DocumentBlock.Quote quote) {
			return "> " + markdownOf(quote.runs());
		}
		DocumentBlock.ListItem item = (DocumentBlock.ListItem) block;
		String indent = "  ".repeat(item.depth());
		String marker = switch (item.kind()) {
			case BULLET -> "- ";
			case TASK -> item.checked() ? "- [x] " : "- [ ] ";
			case ORDERED -> item.number() + ". ";
		};
		return indent + marker + markdownOf(item.runs());
	}

	private static String markdownOf(List<InlineRun> runs) {
		StringBuilder text = new StringBuilder();
		for (InlineRun run : runs) {
			if (run instanceof InlineRun.Strong strong) {
				text.append("**").append(markdownOf(strong.runs())).append("**");
			} else if (run instanceof InlineRun.Emphasis emphasis) {
				text.append("*").append(markdownOf(emphasis.runs())).append("*");
			} else if (run instanceof InlineRun.Code code) {
				text.append('`').append(code.text()).append('`');
			} else if (run instanceof InlineRun.Strike strike) {
				text.append("~~").append(markdownOf(strike.runs())).append("~~");
			} else if (run instanceof InlineRun.Link link) {
				text.append('[').append(markdownOf(link.runs())).append("](")
						.append(link.destination()).append(')');
			} else {
				text.append(run.text());
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
