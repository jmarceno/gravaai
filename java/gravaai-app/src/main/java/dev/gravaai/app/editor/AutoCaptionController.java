package dev.gravaai.app.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.core.editor.EditorPreferences;
import dev.gravaai.core.editor.EditorPreferencesStore;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.WhisperModel;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.services.captions.AutoCaptionService;
import dev.gravaai.services.captions.CaptionGenerator;
import dev.gravaai.services.captions.WhisperModels;
import dev.gravaai.ui.editor.EditorMutations;
import dev.gravaai.ui.editor.transcript.AutoCaptionTranscriptUiService;
import dev.gravaai.ui.editor.transcript.CaptionModelUiService;
import dev.gravaai.ui.editor.transcript.TranscriptUiService;
import dev.gravaai.ui.kit.Toast;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.stage.FileChooser;
import tools.jackson.databind.node.ObjectNode;

/**
 * Port of {@code useAutoCaptionController.ts}: Whisper model selection, download and delete,
 * caption generation for the open project, and applying the generated cues to the editor.
 */
public final class AutoCaptionController implements CaptionModelUiService, AutoCloseable {

	private static final System.Logger LOG = System.getLogger(AutoCaptionController.class.getName());

	private final EditorStore store;
	private final Supplier<String> videoPath;
	private final Supplier<Path> webcamPath;
	private final EditorPreferencesStore preferences;
	private final WhisperModels models;
	private final AutoCaptionService service;
	private final Path whisperCli;
	private final Supplier<Node> toastOwner;
	private final AutoCaptionTranscriptUiService transcriptService;
	private final TranscriptUiService transcript;
	private final AutoCloseable serviceSubscription;
	private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
	private volatile State state;
	private volatile Object appliedStatus;

	public AutoCaptionController(EditorStore store, Supplier<String> videoPath,
			Supplier<Path> webcamPath, EditorPreferencesStore preferences, WhisperModels models,
			AutoCaptionService service, Path whisperCli, Supplier<Node> toastOwner) {
		this.store = store;
		this.videoPath = videoPath;
		this.webcamPath = webcamPath;
		this.preferences = preferences;
		this.models = models;
		this.service = service;
		this.whisperCli = whisperCli;
		this.toastOwner = toastOwner;
		this.state = initialState();
		this.transcriptService = new AutoCaptionTranscriptUiService(service, this::options);
		this.transcript = new TranscriptUiService() {
			@Override
			public Status status() {
				return transcriptService.status();
			}

			@Override
			public AutoCloseable addListener(Consumer<Status> listener) {
				return transcriptService.addListener(listener);
			}

			@Override
			public void generateCaptions() {
				AutoCaptionController.this.generateCaptions();
			}

			@Override
			public void cancel() {
				transcriptService.cancel();
			}
		};
		this.serviceSubscription = service.addListener(this::onServiceStatus);
	}

	/** On start, the persisted model when its file exists, else the small model. */
	private State initialState() {
		EditorPreferences prefs = preferences.loadPreferences();
		WhisperModel persisted = prefs.whisperModel();
		String persistedPath = prefs.whisperModelPath();
		if (persistedPath != null && Files.isReadable(Path.of(persistedPath))) {
			return new State(persisted == null ? null : persisted.wire(), persistedPath,
					DownloadStatus.DOWNLOADED, 100, false);
		}
		if (persisted != null && models.existingPath(persisted) != null) {
			return new State(persisted.wire(), models.existingPath(persisted).toString(),
					DownloadStatus.DOWNLOADED, 100, false);
		}
		Path small = models.existingPath(WhisperModel.SMALL);
		return small == null
				? new State(WhisperModel.SMALL.wire(), null, DownloadStatus.IDLE, 0, false)
				: new State(WhisperModel.SMALL.wire(), small.toString(), DownloadStatus.DOWNLOADED,
						100, false);
	}

	@Override
	public State state() {
		return state;
	}

	@Override
	public AutoCloseable addListener(Consumer<State> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	private void setState(State next) {
		state = next;
		for (Consumer<State> listener : listeners) {
			listener.accept(next);
		}
	}

	@Override
	public void selectModel(String modelId) {
		WhisperModel model = WhisperModel.fromWire(modelId);
		if (model == null) {
			return;
		}
		Path existing = models.existingPath(model);
		if (existing != null) {
			setState(new State(model.wire(), existing.toString(), DownloadStatus.DOWNLOADED, 100,
					state.generating()));
			persist(model, existing);
			return;
		}
		download(model);
	}

	@Override
	public void downloadSmallModel() {
		WhisperModel selected = WhisperModel.fromWire(state.modelId());
		download(selected == null ? WhisperModel.SMALL : selected);
	}

	private void download(WhisperModel model) {
		if (state.downloadStatus() == DownloadStatus.DOWNLOADING) {
			return;
		}
		setState(new State(model.wire(), null, DownloadStatus.DOWNLOADING, 0, state.generating()));
		Thread.ofVirtual().name("gravaai-whisper-download").start(() -> {
			try {
				Path path = models.download(model, progress -> {
					if (progress.state() == WhisperModels.DownloadState.DOWNLOADING
							&& progress.percent() != state.downloadProgress()) {
						setState(new State(model.wire(), null, DownloadStatus.DOWNLOADING,
								progress.percent(), state.generating()));
					}
				});
				persist(model, path);
				setState(new State(model.wire(), path.toString(), DownloadStatus.DOWNLOADED, 100,
						state.generating()));
				toast("Whisper model downloaded", Toast.Variant.SUCCESS);
			} catch (IOException | InterruptedException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "whisper model download failed: " + error);
				setState(new State(model.wire(), null, DownloadStatus.ERROR, 0, state.generating()));
				toast("Failed to download Whisper model", Toast.Variant.DANGER);
			}
		});
	}

	@Override
	public void deleteSmallModel() {
		WhisperModel selected = WhisperModel.fromWire(state.modelId());
		try {
			if (selected != null) {
				models.delete(selected);
			}
			setState(new State(state.modelId(), null, DownloadStatus.IDLE, 0, state.generating()));
			persistPath(null);
			toast("Whisper model deleted", Toast.Variant.SUCCESS);
		} catch (IOException error) {
			toast("Failed to delete Whisper model", Toast.Variant.DANGER);
		}
	}

	@Override
	public void pickCustomModel() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Select Whisper model");
		chooser.getExtensionFilters().addAll(
				new FileChooser.ExtensionFilter("Whisper model", "*.bin"),
				new FileChooser.ExtensionFilter("All files", "*"));
		Node owner = toastOwner.get();
		var file = chooser.showOpenDialog(owner == null || owner.getScene() == null ? null
				: owner.getScene().getWindow());
		if (file == null) {
			return;
		}
		setState(new State(null, file.getAbsolutePath(), DownloadStatus.DOWNLOADED, 100,
				state.generating()));
		persistPath(file.getAbsolutePath());
	}

	@Override
	public void generateCaptions() {
		CaptionGenerator.Options options = options();
		if (options == null) {
			toast("Select a Whisper model or download the small model first", Toast.Variant.DANGER);
			return;
		}
		service.start(options);
	}

	@Override
	public TranscriptUiService transcript() {
		return transcript;
	}

	/** Null when no model is available. */
	private CaptionGenerator.Options options() {
		String modelPath = state.modelPath();
		String video = videoPath.get();
		if (modelPath == null || video == null) {
			return null;
		}
		String executable = preferences.loadPreferences().whisperExecutablePath();
		Path whisper = executable != null && Files.isExecutable(Path.of(executable))
				? Path.of(executable) : whisperCli;
		Path webcam = webcamPath.get();
		String language = store.state().autoCaptionSettings().language();
		return new CaptionGenerator.Options(video, whisper, Path.of(modelPath),
				language == null || language.isBlank() ? "auto" : language,
				CaptionGenerator.resolveCaptionAudioCandidates(video,
						webcam == null ? null : webcam.toString()));
	}

	private void onServiceStatus(AutoCaptionService.Status status) {
		boolean generating = status.generating();
		if (generating != state.generating()) {
			State current = state;
			setState(new State(current.modelId(), current.modelPath(), current.downloadStatus(),
					current.downloadProgress(), generating));
		}
		if (status == appliedStatus) {
			return;
		}
		switch (status.state()) {
			case DONE -> {
				appliedStatus = status;
				List<dev.gravaai.core.project.CaptionCue> cues =
						AutoCaptionTranscriptUiService.projectCues(status.cues());
				Platform.runLater(() -> {
					// One undoable command, published so the timeline and dirty state follow.
					EditorMutations.update(store, json -> {
						json.set("autoCaptions",
								dev.gravaai.core.project.ProjectDocument.writeAutoCaptions(cues));
						if (!cues.isEmpty()) {
							((ObjectNode) json.get("autoCaptionSettings")).put("enabled", true);
						}
					});
					showToast(status.message(), Toast.Variant.SUCCESS);
				});
			}
			case ERROR -> {
				appliedStatus = status;
				toast(status.message(), Toast.Variant.DANGER);
			}
			default -> {
				// progress only
			}
		}
	}

	private void persist(WhisperModel model, Path path) {
		ObjectNode patch = Json.MAPPER.createObjectNode();
		patch.put("whisperModel", model.wire());
		patch.put("whisperModelPath", path.toString());
		preferences.savePreferences(patch);
	}

	private void persistPath(String path) {
		ObjectNode patch = Json.MAPPER.createObjectNode();
		if (path == null) {
			patch.putNull("whisperModelPath");
		} else {
			patch.put("whisperModelPath", path);
		}
		preferences.savePreferences(patch);
	}

	private void toast(String text, Toast.Variant variant) {
		Platform.runLater(() -> showToast(text, variant));
	}

	private void showToast(String text, Toast.Variant variant) {
		Node owner = toastOwner.get();
		if (owner != null && owner.getScene() != null && text != null && !text.isBlank()) {
			Toast.show(owner, text).variant(variant);
		}
	}

	@Override
	public void close() {
		try {
			serviceSubscription.close();
		} catch (Exception ignored) {
			// best effort
		}
		transcriptService.close();
	}
}
