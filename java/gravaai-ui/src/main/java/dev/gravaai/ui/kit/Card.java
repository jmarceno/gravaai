package dev.gravaai.ui.kit;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * HeroUI Card port (plan §5.12), source {@code src/components/ui/card.tsx} +
 * {@code @heroui/styles/components/card.css}.
 *
 * <p>{@code bg-surface}, {@code p-4} (16), {@code gap-3} (12), radius 24 px. Compound parts mirror
 * {@code Card.Header/Title/Description/Content/Footer}.
 */
public final class Card extends VBox {

	public Card() {
		getStyleClass().add("card");
	}

	public Card(Node... children) {
		this();
		getChildren().addAll(children);
	}

	/** {@code Card.Header}: flex-col. */
	public static final class Header extends VBox {
		public Header(Node... children) {
			super(children);
			getStyleClass().add("card__header");
		}
	}

	/** {@code Card.Title}: text-sm leading-6 font-medium. */
	public static final class Title extends Label {
		public Title(String text) {
			super(text);
			getStyleClass().add("card__title");
			setWrapText(true);
		}
	}

	/** {@code Card.Description}: text-sm leading-5 text-muted. */
	public static final class Description extends Label {
		public Description(String text) {
			super(text);
			getStyleClass().add("card__description");
			setWrapText(true);
		}
	}

	/** {@code Card.Content}: flex-1 flex-col gap-1. */
	public static final class Content extends VBox {
		public Content(Node... children) {
			super(children);
			getStyleClass().add("card__content");
			VBox.setVgrow(this, javafx.scene.layout.Priority.ALWAYS);
		}
	}

	/** {@code Card.Footer}: flex-row items-center. */
	public static final class Footer extends HBox {
		public Footer(Node... children) {
			super(children);
			getStyleClass().add("card__footer");
		}
	}
}
