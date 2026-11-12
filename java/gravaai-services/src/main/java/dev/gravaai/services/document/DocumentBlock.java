package dev.gravaai.services.document;

import java.util.List;

/**
 * One block of a plain document, the renderer-neutral shape every {@link DocumentFormat} turns
 * into its own markup. Meeting notes and transcripts are Markdown with a small, well-known
 * subset, so a typed tree keeps the writers free of string pattern matching: each block says what
 * it is, and the DOCX/PDF writers decide only how it looks.
 *
 * <p>{@link #plainText()} is the whole document as readable text (used by tests and by the
 * Markdown writer); it never contains markup.
 */
public sealed interface DocumentBlock permits DocumentBlock.Heading, DocumentBlock.Paragraph,
		DocumentBlock.ListItem, DocumentBlock.Quote, DocumentBlock.Rule {

	/** The block's text runs, in reading order. */
	List<InlineRun> runs();

	/**
	 * A heading; {@code level} is the Markdown level (1-6), so the renderers keep the document's own
	 * hierarchy.
	 */
	record Heading(int level, List<InlineRun> runs) implements DocumentBlock {
		public Heading {
			runs = List.copyOf(runs);
		}
	}

	/** A body paragraph. */
	record Paragraph(List<InlineRun> runs) implements DocumentBlock {
		public Paragraph {
			runs = List.copyOf(runs);
		}
	}

	/**
	 * A bullet, checklist or numbered item.
	 *
	 * @param depth the nesting depth (0 for a top-level item)
	 * @param kind what marks the item
	 * @param checked the task state for {@link Kind#TASK}, ignored otherwise
	 * @param number the item's number for {@link Kind#ORDERED}, starting at 1; 0 otherwise
	 */
	record ListItem(int depth, Kind kind, boolean checked, int number, List<InlineRun> runs)
			implements DocumentBlock {

		public ListItem {
			runs = List.copyOf(runs);
		}

		/** A bullet or task item, which carries no number. */
		public ListItem(int depth, Kind kind, boolean checked, List<InlineRun> runs) {
			this(depth, kind, checked, 0, runs);
		}

		/** How a list item is marked. */
		public enum Kind {
			BULLET,
			TASK,
			ORDERED
		}
	}

	/** A quoted aside, drawn in a quieter style than body text. */
	record Quote(List<InlineRun> runs) implements DocumentBlock {
		public Quote {
			runs = List.copyOf(runs);
		}
	}

	/** A horizontal rule between sections. */
	record Rule() implements DocumentBlock {
		@Override
		public List<InlineRun> runs() {
			return List.of();
		}
	}

	/** The block's runs as plain text, one line (no trailing newline). */
	default String plainText() {
		return InlineRun.join(runs());
	}
}
