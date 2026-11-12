package dev.gravaai.services.document;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.commonmark.Extension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Reads meeting notes and transcripts (Markdown) into the renderer-neutral {@link PlainDocument}.
 * The notes are written by a model against the app's own prompts, so the input is ordinary GFM;
 * the task-list extension is enabled because action items are checklists.
 *
 * <p>Nothing here is thrown away silently: a construct the writers cannot express (a table, an
 * image, an HTML block) still contributes its text, so an export never loses a meeting's content.
 */
public final class MarkdownDocumentParser {

	private static final List<Extension> EXTENSIONS = List.of(
			TaskListItemsExtension.create(),
			org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create());

	private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

	private MarkdownDocumentParser() {
	}

	/**
	 * Parses a Markdown body into blocks. The caller supplies the title and metadata because they
	 * come from the meeting's own fields, not from the notes text (which may repeat them).
	 */
	public static PlainDocument parse(String title, List<String> metadata, String markdown) {
		BlockCollector collector = new BlockCollector();
		PARSER.parse(markdown).accept(collector);
		return new PlainDocument(title, metadata, collector.blocks);
	}

	/**
	 * Parses just the body blocks; a blank document is no blocks. A leading H1 the model repeated
	 * is kept as a heading: the export prints the meeting title above the body already, so a
	 * repeated title reads as a section break rather than being silently dropped.
	 *
	 * @param markdown the Markdown body
	 */
	public static List<DocumentBlock> parseBlocks(String markdown) {
		return parse("", List.of(), markdown).blocks();
	}

	/** Whether a list item is a checklist entry, and its state when it is. */
	private enum TaskState {
		NOT_A_TASK,
		OPEN,
		DONE
	}

	/** Walks the commonmark tree and emits one typed block per rendered line. */
	private static final class BlockCollector extends AbstractVisitor {

		private final List<DocumentBlock> blocks = new ArrayList<>();
		/** Nesting depth of the list currently being collected (0 outside any list). */
		private int depth;

		@Override
		public void visit(Heading heading) {
			blocks.add(new DocumentBlock.Heading(heading.getLevel(), runsOf(heading)));
		}

		@Override
		public void visit(Paragraph paragraph) {
			// Paragraphs inside list items are handled by visit(ListItem) so the item stays one row.
			if (paragraph.getParent() instanceof ListItem) {
				return;
			}
			String text = plainText(paragraph);
			if (!text.isBlank()) {
				blocks.add(new DocumentBlock.Paragraph(runsOf(paragraph)));
			}
		}

		@Override
		public void visit(BulletList bulletList) {
			visitList(bulletList);
		}

		@Override
		public void visit(OrderedList orderedList) {
			visitList(orderedList);
		}

		/**
		 * Lists are flattened: each item becomes one block carrying its depth, so the writers can
		 * indent without having to rebuild the tree. Ordered items carry their own number, taken
		 * from the list's start marker (Markdown allows a list to begin at any number).
		 */
		private void visitList(ListBlock list) {
			depth++;
			boolean ordered = list instanceof OrderedList;
			int start = ordered && list instanceof OrderedList numbered
					&& numbered.getMarkerStartNumber() != null
							? numbered.getMarkerStartNumber()
							: 1;
			int index = 0;
			for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof ListItem item) {
					emitListItem(item, ordered, ordered ? start + index : 0);
					index++;
				}
			}
			depth--;
		}

		private void emitListItem(ListItem item, boolean ordered, int number) {
			TaskState task = taskState(item);
			// The item's own text is the first paragraph among its children; for a task item the
			// checkbox marker comes before it, so the first child is not always the paragraph.
			Optional<Paragraph> textParagraph = firstParagraph(item);
			List<InlineRun> runs = textParagraph.map(BlockCollector::runsOf).orElse(List.of());
			// A marker with no paragraph still needs its text; fall back to the whole item's text.
			if (runs.isEmpty()) {
				String text = plainText(item);
				if (!text.isBlank()) {
					runs = List.of(new InlineRun.Text(text.strip()));
				}
			}
			if (!runs.isEmpty() || task != TaskState.NOT_A_TASK) {
				DocumentBlock.ListItem.Kind kind = task != TaskState.NOT_A_TASK
						? DocumentBlock.ListItem.Kind.TASK
						: ordered ? DocumentBlock.ListItem.Kind.ORDERED
								: DocumentBlock.ListItem.Kind.BULLET;
				blocks.add(new DocumentBlock.ListItem(depth - 1, kind, task == TaskState.DONE,
						number, runs));
			}
			// Anything after the item's own text belongs to it: continuation prose, a nested list
			// (which recurses), or a block such as code or a quote, which keeps its own shape.
			for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
				if (textParagraph.isPresent() && child == textParagraph.get()) {
					continue;
				}
				switch (child) {
					case Paragraph paragraph -> {
						if (!plainText(paragraph).isBlank()) {
							blocks.add(new DocumentBlock.Paragraph(runsOf(paragraph)));
						}
					}
					case ListBlock nested -> visitList(nested);
					case TaskListItemMarker marker -> {
						// Carried by the item's kind.
					}
					default -> child.accept(this);
				}
			}
		}

		/** The first paragraph among a list item's children; empty when it has none. */
		private static Optional<Paragraph> firstParagraph(ListItem item) {
			for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof Paragraph paragraph) {
					return Optional.of(paragraph);
				}
			}
			return Optional.empty();
		}

		/** The {@code [ ]}/{@code [x]} marker of a task item. */
		private static TaskState taskState(ListItem item) {
			for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof TaskListItemMarker marker) {
					return marker.isChecked() ? TaskState.DONE : TaskState.OPEN;
				}
			}
			return TaskState.NOT_A_TASK;
		}

		@Override
		public void visit(FencedCodeBlock block) {
			addCode(block.getLiteral());
		}

		@Override
		public void visit(IndentedCodeBlock block) {
			addCode(block.getLiteral());
		}

		/**
		 * Raw HTML has no rendering in a document, but its text is still part of the meeting: it is
		 * kept as code so nothing the model wrote disappears from the export.
		 */
		@Override
		public void visit(HtmlBlock block) {
			addCode(block.getLiteral());
		}

		private void addCode(@Nullable String literal) {
			// commonmark leaves the literal unset on an empty block.
			String text = literal == null ? "" : literal.stripTrailing();
			if (!text.isBlank()) {
				blocks.add(new DocumentBlock.Paragraph(List.of(new InlineRun.Code(text))));
			}
		}

		@Override
		public void visit(ThematicBreak thematicBreak) {
			blocks.add(new DocumentBlock.Rule());
		}

		@Override
		public void visit(BlockQuote blockQuote) {
			// A quote keeps its inline formatting; only the nested blocks are flattened to text.
			List<InlineRun> runs = new ArrayList<>();
			for (Node child = blockQuote.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof Paragraph paragraph) {
					if (!runs.isEmpty()) {
						runs.add(new InlineRun.Text(" "));
					}
					runs.addAll(runsOf(paragraph));
				}
			}
			if (!runs.isEmpty()) {
				blocks.add(new DocumentBlock.Quote(runs));
			}
		}

		/** Text of a tree with the inline markers stripped and one space per line break. */
		private static String plainText(Node node) {
			StringBuilder text = new StringBuilder();
			appendText(node, text);
			return text.toString().replaceAll("[ \\t]+", " ").strip();
		}

		private static void appendText(Node node, StringBuilder text) {
			for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof Text literal) {
					text.append(literal.getLiteral());
				} else if (child instanceof Code code) {
					text.append(code.getLiteral());
				} else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
					text.append(' ');
				} else if (child instanceof TaskListItemMarker) {
					// The checkbox is carried by the block, not by the text.
				} else {
					appendText(child, text);
				}
			}
		}

		/** The inline runs of a node, preserving bold/italic/code/strike/link. */
		private static List<InlineRun> runsOf(Node node) {
			List<InlineRun> runs = new ArrayList<>();
			appendRuns(node, runs);
			return mergeAdjacentText(runs);
		}

		private static void appendRuns(Node node, List<InlineRun> runs) {
			for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof Text literal) {
					runs.add(new InlineRun.Text(literal.getLiteral()));
				} else if (child instanceof StrongEmphasis strong) {
					runs.add(new InlineRun.Strong(runsOf(strong)));
				} else if (child instanceof Emphasis emphasis) {
					runs.add(new InlineRun.Emphasis(runsOf(emphasis)));
				} else if (child instanceof Code code) {
					runs.add(new InlineRun.Code(code.getLiteral()));
				} else if (child instanceof Link link) {
					// commonmark's destination is never null for a parsed link; "" when empty.
					String destination = link.getDestination() == null ? "" : link.getDestination();
					List<InlineRun> label = runsOf(link);
					if (destination.isBlank()) {
						// A link to nowhere is just its label: an empty hyperlink target is not
						// valid in a DOCX relationship and means nothing in a PDF.
						runs.addAll(label);
					} else {
						runs.add(new InlineRun.Link(label, destination));
					}
				} else if (child instanceof org.commonmark.ext.gfm.strikethrough.Strikethrough strike) {
					runs.add(new InlineRun.Strike(runsOf(strike)));
				} else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
					runs.add(new InlineRun.Text(" "));
				} else if (child instanceof TaskListItemMarker) {
					// Marker handled by the list item.
				} else {
					appendRuns(child, runs);
				}
			}
		}

		/**
		 * Merges neighbouring plain-text runs and drops empty ones: the Markdown splitter produces
		 * many tiny literals, and every writer would otherwise repeat the same style switch.
		 */
		private static List<InlineRun> mergeAdjacentText(List<InlineRun> runs) {
			List<InlineRun> merged = new ArrayList<>();
			StringBuilder pending = new StringBuilder();
			for (InlineRun run : runs) {
				if (run instanceof InlineRun.Text text) {
					pending.append(text.text());
					continue;
				}
				if (!pending.isEmpty()) {
					merged.add(new InlineRun.Text(pending.toString()));
					pending.setLength(0);
				}
				merged.add(run);
			}
			if (!pending.isEmpty()) {
				merged.add(new InlineRun.Text(pending.toString()));
			}
			return List.copyOf(merged);
		}
	}
}
