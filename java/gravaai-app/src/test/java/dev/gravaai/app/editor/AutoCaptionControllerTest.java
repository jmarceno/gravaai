package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.EditorPreferencesStore;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.services.captions.AutoCaptionService;
import dev.gravaai.services.captions.CaptionCue;
import dev.gravaai.services.captions.CaptionGenerator;
import dev.gravaai.services.captions.CaptionWord;
import dev.gravaai.services.captions.WhisperModels;
import dev.gravaai.ui.editor.transcript.CaptionModelUiService.DownloadStatus;
import javafx.application.Platform;

/** P3.1: the useAutoCaptionController port over fake download and generation. */
@Timeout(60)
class AutoCaptionControllerTest {

	@TempDir
	Path dir;

	@org.junit.jupiter.api.BeforeAll
	static void toolkit() {
		try {
			Platform.startup(() -> { });
		} catch (IllegalStateException alreadyStarted) {
			// another test started the toolkit
		}
	}

	private EditorStore store() {
		return new EditorStore(ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("{}")));
	}

	private AutoCaptionController controller(EditorStore store, AutoCaptionService.Job job) {
		UserData userData = new UserData(dir);
		WhisperModels models = new WhisperModels(userData, (uri, target, progress) -> {
			progress.accept(50);
			Files.writeString(target, "model");
			progress.accept(100);
		});
		return new AutoCaptionController(store, () -> dir.resolve("video.mp4").toString(),
				() -> null, new EditorPreferencesStore(new AppSettingsStore(dir.resolve("settings.json"))),
				models, new AutoCaptionService(job, Executors.newSingleThreadExecutor()),
				dir.resolve("whisper-cli"), () -> null);
	}

	@Test
	@DisplayName("starts on the small model with nothing downloaded")
	void initialState() {
		var state = controller(store(), (options, progress, signal) -> null).state();
		assertEquals("small", state.modelId());
		assertNull(state.modelPath());
		assertEquals(DownloadStatus.IDLE, state.downloadStatus());
	}

	@Test
	@DisplayName("selecting a missing model downloads and persists it")
	void selectDownloadsAndPersists() throws Exception {
		AutoCaptionController controller = controller(store(), (options, progress, signal) -> null);
		CountDownLatch done = new CountDownLatch(1);
		controller.addListener(state -> {
			if (state.downloadStatus() == DownloadStatus.DOWNLOADED) {
				done.countDown();
			}
		});
		controller.selectModel("large-v3-turbo-q5_0");
		assertTrue(done.await(10, TimeUnit.SECONDS));
		Path model = dir.resolve("whisper/ggml-large-v3-turbo-q5_0.bin");
		assertTrue(Files.isRegularFile(model));
		var reopened = controller(store(), (options, progress, signal) -> null).state();
		assertEquals("large-v3-turbo-q5_0", reopened.modelId());
		assertEquals(model.toString(), reopened.modelPath());
	}

	@Test
	@DisplayName("generated cues land in the store and turn captions on")
	void generationAppliesCues() throws Exception {
		Path small = new UserData(dir).whisperSmallModelPath();
		Files.createDirectories(small.getParent());
		Files.writeString(small, "model");
		EditorStore store = store();
		CompletableFuture<CaptionGenerator.Options> seen = new CompletableFuture<>();
		AutoCaptionController controller = controller(store, (options, progress, signal) -> {
			seen.complete(options);
			return new CaptionGenerator.Result(List.of(new CaptionCue("cue-1", 0, 900, "Hello",
					List.of(new CaptionWord("Hello", 0, 900, false)))), "recording");
		});
		controller.generateCaptions();
		assertEquals(small, seen.get(10, TimeUnit.SECONDS).modelPath());
		long deadline = System.currentTimeMillis() + 10_000;
		while (store.state().autoCaptions().isEmpty() && System.currentTimeMillis() < deadline) {
			CompletableFuture<Void> tick = new CompletableFuture<>();
			Platform.runLater(() -> tick.complete(null));
			tick.get(5, TimeUnit.SECONDS);
		}
		assertEquals("Hello", store.state().autoCaptions().getFirst().text());
		assertTrue(store.state().autoCaptionSettings().enabled());
	}
}
