package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Renders the Markdown subset the meeting notes and transcripts use (port of
 * {@code MeetingMarkdown.tsx}): headings, bullets, checklists, bold, italics, inline code, and
 * clickable [HH:MM:SS] timestamps that seek the recording.
 */
public final class MeetingMarkdownView extends VBox {

	private static final String MONO_STYLE =
			"-fx-font-family: 'monospace'; -fx-font-size: 11px; -fx-background-color: rgba(255,255,255,0.06);"
					+ " -fx-background-radius: 4; -fx-padding: 0 4 0 4;";

	private final @Nullable Consumer<Integer> onSeek;
	private String source = "";

	public MeetingMarkdownView() {
		this(null, null);
	}

	public MeetingMarkdownView(@Nullable String source, @Nullable Consumer<Integer> onSeek) {
		this.onSeek = onSeek;
		getStyleClass().add("meeting-markdown");
		setSpacing(2);
		refresh(source);
	}

	/** Re-renders the given Markdown source (null renders the empty document). */
	public void refresh(@Nullable String source) {
		this.source = source == null ? "" : source;
		getChildren().clear();
		if (source == null || source.isBlank()) {
			return;
		}
		List<MeetingMarkdownParser.Block> blocks = MeetingMarkdownParser.parse(source);
		VBox list = null;
		for (MeetingMarkdownParser.Block block : blocks) {
			if (block instanceof MeetingMarkdownParser.Heading heading) {
				list = null;
				getChildren().add(headingNode(heading));
			} else if (block instanceof MeetingMarkdownParser.Bullet bullet) {
				if (list == null) {
					list = new VBox(4);
					list.setPadding(new Insets(0, 0, 6, 0));
					getChildren().add(list);
				}
				list.getChildren().add(bulletNode(bullet));
			} else if (block instanceof MeetingMarkdownParser.Paragraph paragraph) {
				list = null;
				getChildren().add(paragraphNode(paragraph.text()));
			}
		}
	}

	public String source() {
		return source;
	}

	private Node headingNode(MeetingMarkdownParser.Heading heading) {
		Label label = new Label();
		TextFlow flow = flow(heading.text());
		label.setGraphic(flow);
		label.setWrapText(true);
		label.setPadding(new Insets(heading.level() <= 2 ? 16 : 12, 0, 6, 0));
		label.setStyle("-fx-text-fill: " + Theme.FG.toString().replace("0x", "#") + ";");
		double size = heading.level() <= 2 ? 15 : 14;
		for (Node node : flow.getChildren()) {
			if (node instanceof Text text) {
				text.setFont(Font.font("DM Sans", FontWeight.SEMI_BOLD, size));
			}
		}
		flow.prefWidthProperty().bind(widthProperty().subtract(24));
		return label;
	}

	private Node paragraphNode(String text) {
		HBox row = new HBox(0);
		row.setAlignment(Pos.TOP_LEFT);
		row.setPadding(new Insets(6, 0, 6, 0));
		Node content = timestampAware(text);
		HBox.setHgrow(content, Priority.ALWAYS);
		row.getChildren().add(content);
		return row;
	}

	private Node bulletNode(MeetingMarkdownParser.Bullet bullet) {
		HBox row = new HBox(8);
		row.setAlignment(Pos.TOP_LEFT);
		Region indent = new Region();
		indent.setMinWidth(16.0 * bullet.depth());
		indent.setPrefWidth(16.0 * bullet.depth());
		Label marker = new Label(bullet.checked() == null ? "•"
				: bullet.checked() ? "☑" : "☐");
		marker.setStyle("-fx-text-fill: " + css(Theme.MUTED) + "; -fx-font-size: 13px;");
		marker.setMinWidth(14);
		Node content = timestampAware(bullet.text());
		HBox.setHgrow(content, Priority.ALWAYS);
		row.getChildren().addAll(indent, marker, content);
		return row;
	}

	/** A text line; a leading timestamp becomes a seek button. */
	private Node timestampAware(String text) {
		Integer seconds = MeetingMarkdownParser.parseTimestampSeconds(text);
		if (seconds == null) {
			return wrap(flow(text));
		}
		int prefixLength = MeetingMarkdownParser.timestampPrefixLength(text);
		String label = text.substring(0, prefixLength).trim();
		String rest = text.substring(prefixLength);
		HBox row = new HBox(6);
		row.setAlignment(Pos.TOP_LEFT);
		Button seek = new Button(label, Button.Variant.GHOST, Button.Size.SM);
		seek.setMinWidth(Region.USE_PREF_SIZE);
		seek.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12px; -fx-text-fill: "
				+ css(Theme.ACCENT) + "; -fx-padding: 0 4 0 4;");
		if (onSeek != null) {
			seek.setOnAction(event -> onSeek.accept(seconds));
		} else {
			seek.setDisable(true);
			seek.setStyle(seek.getStyle().replace(css(Theme.ACCENT), css(Theme.MUTED)));
		}
		Node body = rest.isBlank() ? new Region() : wrap(flow(rest));
		HBox.setHgrow(body, Priority.ALWAYS);
		row.getChildren().addAll(seek, body);
		return row;
	}

	private Region wrap(TextFlow flow) {
		flow.setLineSpacing(2);
		flow.prefWidthProperty().bind(widthProperty().subtract(40));
		return flow;
	}

	private TextFlow flow(String text) {
		TextFlow flow = new TextFlow();
		for (MeetingInline.Token token : MeetingInline.tokenize(text)) {
			if (token instanceof MeetingInline.Bold bold) {
				Text node = new Text(bold.text());
				node.setStyle("-fx-font-weight: bold; -fx-fill: " + css(Theme.FG) + ";");
				flow.getChildren().add(node);
			} else if (token instanceof MeetingInline.Code code) {
				Label chip = new Label(code.text());
				chip.setStyle(MONO_STYLE + " -fx-text-fill: " + css(Theme.FG) + ";");
				flow.getChildren().add(chip);
			} else if (token instanceof MeetingInline.Italic italic) {
				Text node = new Text(italic.text());
				node.setFont(Font.font("DM Sans", FontPosture.ITALIC, 13));
				node.setStyle("-fx-fill: " + css(Theme.MUTED) + ";");
				flow.getChildren().add(node);
			} else if (token instanceof MeetingInline.Text plain) {
				Text node = new Text(plain.text());
				node.setFont(Font.font("DM Sans", 13));
				node.setStyle("-fx-fill: " + css(Theme.FG) + ";");
				flow.getChildren().add(node);
			}
		}
		return flow;
	}

	private static String css(javafx.scene.paint.Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
