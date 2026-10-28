package dev.gravaai.ui.editor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Dialog;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Label;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * "Add Google Font" dialog (port of {@code AddCustomFontDialog.tsx}): import URL + display name,
 * validation with the original messages, load/verify through {@link CustomFonts.FontLoader} and
 * {@link CustomFonts#addCustomFont} duplicate handling; the parent persists the returned font.
 */
public final class AddCustomFontDialog {

	private final Dialog dialog;
	private final List<CustomFonts.CustomFont> existingFonts;
	private final CustomFonts.FontLoader loader;
	private final Consumer<CustomFonts.CustomFont> onFontAdded;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "custom-font-loader");
		thread.setDaemon(true);
		return thread;
	});
	private final Input importUrl = new Input();
	private final Input fontName = new Input();
	private final Label error = new Label();
	private final Button add = new Button("Add Font", Button.Variant.DEFAULT, Button.Size.SM);

	public AddCustomFontDialog(List<CustomFonts.CustomFont> existingFonts,
			CustomFonts.FontLoader loader, Consumer<CustomFonts.CustomFont> onFontAdded) {
		this.existingFonts = existingFonts == null ? List.of() : existingFonts;
		this.loader = loader == null ? CustomFonts.defaultLoader() : loader;
		this.onFontAdded = onFontAdded == null ? font -> { } : onFontAdded;

		dialog = new Dialog("Add Google Font");
		dialog.description(
				"Add a custom font from Google Fonts to use in your annotations.");
		dialog.width(560);

		error.setWrapText(true);
		error.setStyle("-fx-font-size: 11px; -fx-text-fill: -g-danger;");
		error.setManaged(false);
		error.setVisible(false);

		importUrl.setPromptText("https://fonts.googleapis.com/css2?family=Roboto&display=swap");
		importUrl.fullWidth();
		importUrl.textProperty().addListener((observable, oldValue, newValue) -> {
			if (CustomFonts.isValidGoogleFontsUrl(newValue)) {
				String extracted = CustomFonts.parseFontFamilyFromImport(newValue);
				if (extracted != null && fontName.getText().isBlank()) {
					fontName.setText(extracted);
				}
			}
		});
		fontName.setPromptText("My Custom Font");
		fontName.fullWidth();

		VBox content = new VBox(16,
				field("Google Fonts Import URL", importUrl,
						"Get this from Google Fonts: Select a font → Click \"Get font\" → "
								+ "Copy the @import URL"),
				field("Display Name", fontName,
						"This is how the font will appear in the font selector"),
				error);
		content.setPadding(new Insets(8, 0, 0, 0));
		dialog.content(content);

		Button cancel = new Button("Cancel", Button.Variant.OUTLINE, Button.Size.SM);
		cancel.setOnAction(event -> dialog.close());
		add.setOnAction(event -> submit());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox footer = new HBox(8, spacer, cancel, add);
		footer.setAlignment(Pos.CENTER_RIGHT);
		dialog.footer(footer);
	}

	/** Opens the dialog inside the kit root that owns {@code owner}. */
	public void show(Node owner) {
		dialog.show(owner);
	}

	public Dialog dialog() {
		return dialog;
	}

	private void submit() {
		String normalizedUrl = importUrl.getText() == null ? "" : importUrl.getText().strip();
		String normalizedName = fontName.getText() == null ? "" : fontName.getText().strip();
		if (normalizedUrl.isEmpty()) {
			fail("Please enter a Google Fonts import URL");
			return;
		}
		if (!CustomFonts.isValidGoogleFontsUrl(normalizedUrl)) {
			fail("Please enter a valid Google Fonts URL");
			return;
		}
		if (normalizedName.isEmpty()) {
			fail("Please enter a font name");
			return;
		}
		String fontFamily = CustomFonts.parseFontFamilyFromImport(normalizedUrl);
		if (fontFamily == null) {
			fail("Could not extract font family from URL");
			return;
		}

		clearError();
		add.setDisable(true);
		add.setText("Adding...");
		CustomFonts.CustomFont font = new CustomFonts.CustomFont(
				CustomFonts.generateFontId(normalizedName), normalizedName, fontFamily,
				normalizedUrl);
		executor.submit(() -> {
			try {
				CustomFonts.addCustomFont(existingFonts, font, loader);
				Platform.runLater(() -> {
					onFontAdded.accept(font);
					dialog.close();
				});
			} catch (CustomFonts.DuplicateFontException duplicate) {
				Platform.runLater(() -> {
					resetButton();
					fail("Failed to add font — this font has already been added.");
				});
			} catch (Exception failure) {
				boolean timeout = failure.getMessage() != null
						&& failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout");
				Platform.runLater(() -> {
					resetButton();
					fail(timeout
							? "Font took too long to load. Please check the URL and try again."
							: "The font could not be loaded. Please verify the Google Fonts URL "
									+ "is correct.");
				});
			}
		});
	}

	private void resetButton() {
		add.setDisable(false);
		add.setText("Add Font");
	}

	private void fail(String message) {
		error.setText(message);
		error.setManaged(true);
		error.setVisible(true);
	}

	private void clearError() {
		error.setText("");
		error.setManaged(false);
		error.setVisible(false);
	}

	private static VBox field(String labelText, Input input, String help) {
		Label label = new Label(labelText);
		label.setStyle("-fx-font-size: 12px; -fx-font-weight: 500; -fx-text-fill: -g-fg;");
		Label hint = new Label(help);
		hint.setWrapText(true);
		hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -g-muted;");
		return new VBox(6, label, input, hint);
	}
}
