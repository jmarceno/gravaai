package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.meeting.MeetingServiceKind;
import dev.gravaai.services.meeting.MeetingSettings;
import dev.gravaai.services.meeting.Subscription;
import dev.gravaai.ui.dashboard.SettingsRow;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.ChoiceGroup;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.TextArea;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.kit.Toast;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Meeting services settings (port of {@code MeetingSettingsPanel.tsx}): where transcription and
 * notes run, language/model choices, the OpenAI-compatible API fields, automatic processing,
 * the notes prompt and the download manager.
 */
public final class MeetingSettingsView extends VBox {

	/** Port of {@code LANGUAGES}. */
	private static final List<String[]> LANGUAGES = List.of(
			new String[] {"auto", "Detect"},
			new String[] {"pt", "Portuguese"},
			new String[] {"en", "English"},
			new String[] {"es", "Spanish"},
			new String[] {"fr", "French"},
			new String[] {"de", "German"},
			new String[] {"it", "Italian"});

	private final MeetingUiService service;
	private final VBox rows = new VBox(24);

	private MeetingSettings.@Nullable View view;
	private List<MeetingService.AssetStatus> assets = List.of();
	private final Map<String, Integer> progress = new LinkedHashMap<>();
	private @Nullable Subscription assetSubscription;
	private boolean loading = true;
	private @Nullable String error;

	public MeetingSettingsView(MeetingUiService service) {
		this.service = service;
		getStyleClass().add("meeting-settings");
		setSpacing(16);
		rows.setSpacing(24);
		rows.setMaxWidth(672);
		getChildren().add(rows);
		assetSubscription = service.onAssetProgress(update -> Platform.runLater(() -> {
			if (update.removed()) {
				progress.remove(update.id());
				refreshAssets();
			} else if (update.progress() != null) {
				progress.put(update.id(), update.progress());
				rebuild();
			}
		}));
		load();
	}

	/** Stops listening to download progress (the page is being disposed). */
	public void close() {
		if (assetSubscription != null) {
			assetSubscription.close();
			assetSubscription = null;
		}
	}

	private record Loaded(MeetingSettings.View settings, List<MeetingService.AssetStatus> assets) {
		Loaded {
			assets = List.copyOf(assets);
		}
	}

	private void load() {
		CompletableFuture.supplyAsync(() -> {
			MeetingSettings.View settings = service.settings();
			List<MeetingService.AssetStatus> statuses = service.listAssets();
			return new Loaded(settings, statuses);
		}).thenAcceptAsync(loaded -> {
			view = loaded.settings();
			assets = loaded.assets();
			loading = false;
			rebuild();
		}, Platform::runLater);
	}

	private void refreshAssets() {
		CompletableFuture.supplyAsync(service::listAssets).thenAcceptAsync(statuses -> {
			assets = statuses == null ? List.of() : statuses;
			rebuild();
		}, Platform::runLater);
	}

	private void rebuild() {
		rows.getChildren().clear();
		if (loading || view == null) {
			Label label = new Label("Loading meeting settings…");
			label.setStyle("-fx-font-size: 13px; -fx-text-fill: " + css(Theme.MUTED) + ";");
			rows.getChildren().add(label);
			return;
		}
		if (error != null && !error.isBlank()) {
			Label label = new Label(error);
			label.setWrapText(true);
			label.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.DANGER)
					+ "; -fx-background-color: rgba(229,101,110,0.1); -fx-background-radius: 12;"
					+ " -fx-padding: 8 12 8 12;");
			rows.getChildren().add(label);
		}
		MeetingSettings settings = view.settings();
		rows.getChildren().add(buildTranscription(settings));
		if (settings.transcriptionService() == MeetingServiceKind.LOCAL) {
			rows.getChildren().add(new SettingsRow("Whisper model", "The local model used to transcribe.",
					stackedField("Whisper model", settings.whisperModel(),
							value -> save(s -> s.withWhisperModel(value))), true));
		}
		rows.getChildren().add(buildLanguage(settings));
		rows.getChildren().add(buildNotes(settings));
		if (settings.summarizationService() == MeetingServiceKind.LOCAL) {
			rows.getChildren().add(buildLocalNotesModel(settings));
		}
		if (usesApi(settings)) {
			rows.getChildren().add(buildApi(settings));
		}
		rows.getChildren().add(new SettingsRow("Process meetings automatically",
				"Transcribe and write notes as soon as a meeting recording stops.",
				autoSwitch(settings.autoProcess(), "Process meetings automatically",
						MeetingSettings::withAutoProcess)));
		rows.getChildren().add(new SettingsRow("Name meetings from their notes", "",
				autoSwitch(settings.autoTitle(), "Name meetings from their notes",
						MeetingSettings::withAutoTitle)));
		rows.getChildren().add(buildPrompt(settings));
		rows.getChildren().add(buildDownloads());
	}

	private Node buildTranscription(MeetingSettings settings) {
		ChoiceGroup choice = new ChoiceGroup();
		choice.item("local", "On this computer");
		choice.item("api", "API");
		MeetingServiceKind transcriptionService = java.util.Objects.requireNonNull(
				settings.transcriptionService(), "meeting settings without a transcription service");
		choice.setValue(java.util.Objects.requireNonNull(transcriptionService.wire(),
				"transcription service wire value"));
		choice.onValueChange(value ->
				save(s -> s.withTranscriptionService(
						"api".equals(value) ? MeetingServiceKind.API : MeetingServiceKind.LOCAL)));
		return new SettingsRow("Transcription",
				"Runs a local whisper model, or uploads the recording to an OpenAI-compatible API.",
				choice, true);
	}

	private Node buildLanguage(MeetingSettings settings) {
		ChoiceGroup choice = new ChoiceGroup();
		for (String[] language : LANGUAGES) {
			choice.item(language[0], language[1]);
		}
		choice.setValue(settings.language());
		choice.onValueChange(value -> save(s -> s.withLanguage(value)));
		return new SettingsRow("Spoken language",
				"Detect tries every language; picking one is faster and more accurate.", choice, true);
	}

	private Node buildNotes(MeetingSettings settings) {
		ChoiceGroup choice = new ChoiceGroup();
		choice.item("local", "On this computer");
		choice.item("api", "API");
		MeetingServiceKind summarizationService = java.util.Objects.requireNonNull(
				settings.summarizationService(), "meeting settings without a summarization service");
		choice.setValue(java.util.Objects.requireNonNull(summarizationService.wire(),
				"summarization service wire value"));
		choice.onValueChange(value ->
				save(s -> s.withSummarizationService(
						"api".equals(value) ? MeetingServiceKind.API : MeetingServiceKind.LOCAL)));
		String description = settings.summarizationService() == MeetingServiceKind.LOCAL
				? "Runs a small model on this computer, started only while it writes notes."
				: "Sends the transcript to the API below.";
		return new SettingsRow("Meeting notes", description, choice, true);
	}

	private Node buildLocalNotesModel(MeetingSettings settings) {
		ChoiceGroup choice = new ChoiceGroup();
		choice.item("qwen3.5-2b", "Qwen3.5 2B (1.3 GB)");
		choice.item("qwen3.5-4b", "Qwen3.5 4B (2.7 GB)");
		choice.setValue(settings.localModel());
		choice.onValueChange(value -> save(s -> s.withLocalModel(value)));
		return new SettingsRow("Notes model",
				"2B fits in about 2 GB of memory; 4B writes better notes but needs about 4 GB.",
				choice, true);
	}

	private Node buildApi(MeetingSettings settings) {
		VBox card = new VBox(16);
		card.setPadding(new Insets(16));
		card.setStyle("-fx-background-color: " + css(Theme.SURFACE) + "; -fx-background-radius: 16;"
				+ " -fx-border-color: " + css(Theme.SEPARATOR) + "; -fx-border-radius: 16;");
		Label title = new Label("API");
		title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: "
				+ css(Theme.FG) + ";");
		Label description = new Label(
				"Any OpenAI-compatible service: OpenAI, OpenRouter, Ollama, LM Studio…");
		description.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		description.setWrapText(true);
		card.getChildren().addAll(title, description);

		card.getChildren().add(new SettingsRow("Base URL", "",
				stackedField("API base URL", settings.apiBaseUrl(),
						value -> save(s -> s.withApiBaseUrl(value))), true));

		Region keyField = buildApiKey(settings);
		MeetingSettings.View currentView = view;
		boolean hasSavedKey = currentView != null && currentView.hasApiKey();
		card.getChildren().add(new SettingsRow("API key",
				hasSavedKey ? "A key is saved in the system keyring."
						: "Stored encrypted in the system keyring. Leave empty for local servers.",
				keyField, true));

		if (settings.transcriptionService() == MeetingServiceKind.API) {
			card.getChildren().add(new SettingsRow("Transcription model", "",
					stackedField("API transcription model", settings.apiTranscriptionModel(),
							value -> save(s -> s.withApiTranscriptionModel(value))), true));
		}
		if (settings.summarizationService() == MeetingServiceKind.API) {
			card.getChildren().add(new SettingsRow("Notes model", "",
					stackedField("API notes model", settings.apiChatModel(),
							value -> save(s -> s.withApiChatModel(value))), true));
		}
		return card;
	}

	private Region buildApiKey(MeetingSettings settings) {
		PasswordField key = new PasswordField();
		MeetingSettings.View currentView = view;
		boolean hasSavedKey = currentView != null && currentView.hasApiKey();
		key.setPromptText(hasSavedKey ? "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" : "sk-\u2026");
		key.setPrefHeight(36);
		styleField(key);
		HBox.setHgrow(key, Priority.ALWAYS);
		Button save = new Button("Save key", Button.Variant.SECONDARY, Button.Size.SM);
		save.setDisable(true);
		key.textProperty().addListener((observable, oldValue, value) ->
				save.setDisable(value == null || value.trim().isEmpty()));
		save.setOnAction(event -> {
			String value = key.getText() == null ? "" : key.getText().trim();
			if (value.isEmpty()) {
				return;
			}
			apply(() -> service.setApiKey(value));
			key.clear();
		});
		Button remove = new Button("Remove", Button.Variant.GHOST, Button.Size.SM);
		remove.setVisible(hasSavedKey);
		remove.setManaged(hasSavedKey);
		remove.setOnAction(event -> saveKeyRemoval());
		HBox row = new HBox(8, key, save, remove);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private Node buildPrompt(MeetingSettings settings) {
		TextArea prompt = new TextArea(
				"Leave empty to use the built-in prompt. Use {transcript} where the text goes.");
		prompt.text(settings.summaryPrompt());
		prompt.setPrefHeight(112);
		prompt.setPrefWidth(560);
		prompt.setMaxWidth(560);
		prompt.setWrapText(true);
		prompt.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12px;"
				+ " -fx-control-inner-background: " + css(Theme.FIELD) + "; -fx-text-fill: "
				+ css(Theme.FG) + ";");
		prompt.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused && !settings.summaryPrompt().equals(prompt.getText())) {
				save(s -> s.withSummaryPrompt(prompt.getText()));
			}
		});
		VBox column = new VBox(8, prompt);
		if (settings.summaryPrompt() != null && !settings.summaryPrompt().isBlank()) {
			Button reset = new Button("Use the built-in prompt", Button.Variant.GHOST,
					Button.Size.SM);
			reset.setOnAction(event -> save(s -> s.withSummaryPrompt("")));
			column.getChildren().add(reset);
		}
		return new SettingsRow("Notes prompt", "", column, true);
	}

	private Node buildDownloads() {
		VBox list = new VBox(10);
		for (MeetingService.AssetStatus asset : assets) {
			HBox row = new HBox(12);
			row.setAlignment(Pos.CENTER_LEFT);
			Label label = new Label(asset.label() + "  " + MeetingText.formatSize(asset.sizeBytes()));
			label.setStyle("-fx-font-size: 13px; -fx-text-fill: " + css(Theme.FG) + ";");
			label.setMaxWidth(Double.MAX_VALUE);
			HBox.setHgrow(label, Priority.ALWAYS);
			Integer percent = progress.get(asset.id());
			Node control;
			if (percent != null) {
				Label percentLabel = new Label(percent + "%");
				percentLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED)
						+ ";");
				control = percentLabel;
			} else if (asset.installed()) {
				Button remove = new Button("Remove", Button.Variant.GHOST, Button.Size.SM);
				remove.setOnAction(event -> removeAsset(asset));
				control = remove;
			} else {
				Button download = new Button("Download", Button.Variant.SECONDARY, Button.Size.SM);
				download.setOnAction(event -> downloadAsset(asset));
				control = download;
			}
			row.getChildren().addAll(label, control);
			list.getChildren().add(row);
		}
		return new SettingsRow("Downloads",
				"Models are downloaded the first time they are needed.", list, true);
	}

	private Region autoSwitch(boolean checked, String accessibleText,
			BiFunction<MeetingSettings, Boolean, MeetingSettings> edit) {
		Switch toggle = new Switch("", checked);
		toggle.setAccessibleText(accessibleText);
		toggle.onCheckedChange(value -> save(s -> edit.apply(s, value)));
		return toggle;
	}

	private Region stackedField(String accessibleText, String current,
			java.util.function.Consumer<String> onSave) {
		Input field = new Input();
		field.setText(current == null ? "" : current);
		field.setAccessibleText(accessibleText);
		field.setPrefHeight(36);
		field.setPrefWidth(320);
		field.setMaxWidth(320);
		field.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				String value = field.getText() == null ? "" : field.getText();
				if (!value.equals(current == null ? "" : current)) {
					onSave.accept(value);
				}
			}
		});
		field.setOnAction(event -> {
			String value = field.getText() == null ? "" : field.getText();
			if (!value.equals(current == null ? "" : current)) {
				onSave.accept(value);
			}
		});
		styleField(field);
		return field;
	}

	private void styleField(javafx.scene.control.TextInputControl field) {
		field.setStyle("-fx-background-color: " + css(Theme.FIELD) + "; -fx-background-radius: 12;"
				+ " -fx-text-fill: " + css(Theme.FG) + "; -fx-prompt-text-fill: "
				+ css(Theme.MUTED) + "; -fx-padding: 0 12 0 12;");
	}

	private void save(UnaryOperator<MeetingSettings> edit) {
		apply(() -> service.updateSettings(edit));
	}

	private void apply(Supplier<Result<MeetingSettings.View>> change) {
		CompletableFuture.supplyAsync(change).thenAcceptAsync(result -> {
			if (result.isErr()) {
				error = result.errorMessage();
				Toast.show(this, error).variant(Toast.Variant.DANGER);
				rebuild();
				return;
			}
			error = null;
			MeetingSettings.View updated = result.orNull();
			if (updated != null) {
				view = updated;
			}
			rebuild();
			refreshAssets();
		}, Platform::runLater);
	}

	private void saveKeyRemoval() {
		apply(service::clearApiKey);
	}

	private void downloadAsset(MeetingService.AssetStatus asset) {
		progress.put(asset.id(), 0);
		rebuild();
		service.downloadAsset(asset.id()).thenAcceptAsync(result -> {
			progress.remove(asset.id());
			if (result.isErr()) {
				Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
			}
			refreshAssets();
		}, Platform::runLater);
	}

	private void removeAsset(MeetingService.AssetStatus asset) {
		CompletableFuture.supplyAsync(() -> service.removeAsset(asset.id()))
				.thenAcceptAsync(result -> {
					if (result.isErr()) {
						Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
						return;
					}
					refreshAssets();
				}, Platform::runLater);
	}

	private static boolean usesApi(MeetingSettings settings) {
		return settings.transcriptionService() == MeetingServiceKind.API
				|| settings.summarizationService() == MeetingServiceKind.API;
	}

	private static String css(javafx.scene.paint.Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
