package dev.gravaai.services.document;

import java.util.List;

/**
 * One styled run of text inside a block. Runs are the unit all three writers share: Markdown wraps
 * them back into {@code **bold**} / {@code *italic*} / backticks, DOCX turns them into w:t runs
 * with rPr properties, and PDF picks the font face for them.
 */
public sealed interface InlineRun permits InlineRun.Text, InlineRun.Strong, InlineRun.Emphasis,
		InlineRun.Code, InlineRun.Strike, InlineRun.Link {

	/** The run's literal text, without markup. */
	String text();

	/** Plain text. */
	record Text(String text) implements InlineRun {
	}

	/** Strong emphasis ({@code **bold**}). */
	record Strong(List<InlineRun> runs) implements InlineRun {
		public Strong {
			runs = List.copyOf(runs);
		}

		@Override
		public String text() {
			return join(runs);
		}
	}

	/** Emphasis ({@code *italic*}). */
	record Emphasis(List<InlineRun> runs) implements InlineRun {
		public Emphasis {
			runs = List.copyOf(runs);
		}

		@Override
		public String text() {
			return join(runs);
		}
	}

	/** Inline code (backticks). */
	record Code(String text) implements InlineRun {
	}

	/** Strikethrough ({@code ~~text~~}). */
	record Strike(List<InlineRun> runs) implements InlineRun {
		public Strike {
			runs = List.copyOf(runs);
		}

		@Override
		public String text() {
			return join(runs);
		}
	}

	/**
	 * A link: the label plus its destination. Both writers keep the destination visible where the
	 * target format cannot carry a clickable link.
	 */
	record Link(List<InlineRun> runs, String destination) implements InlineRun {
		public Link {
			runs = List.copyOf(runs);
		}

		@Override
		public String text() {
			return join(runs);
		}
	}

	/** Joins runs into plain text. */
	static String join(List<InlineRun> runs) {
		StringBuilder text = new StringBuilder();
		for (InlineRun run : runs) {
			text.append(run.text());
		}
		return text.toString();
	}
}
