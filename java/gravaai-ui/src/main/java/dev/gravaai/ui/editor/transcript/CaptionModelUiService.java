package dev.gravaai.ui.editor.transcript;

import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Port of the Whisper model half of {@code useAutoCaptionController.ts} for the Captions section:
 * model choice, download/delete, a custom model file, and caption generation. The app implements
 * it over the services; tests inject a fake.
 */
public interface CaptionModelUiService {

	/** {@code whisperModelDownloadStatus}. */
	enum DownloadStatus {
		IDLE, DOWNLOADING, DOWNLOADED, ERROR
	}

	/**
	 * One snapshot of the controller state.
	 *
	 * @param modelId the selected model id ({@code small}, {@code large-v3-turbo-q5_0}), or null
	 *     when no model is selected
	 * @param modelPath the model file used for generation, or null when none is available
	 * @param downloadStatus the model download state
	 * @param downloadProgress download percent, 0-100
	 * @param generating whether captions are being generated
	 */
	record State(@Nullable String modelId, @Nullable String modelPath, DownloadStatus downloadStatus,
			int downloadProgress, boolean generating) {
	}

	State state();

	/** Subscribes to state changes (any thread); close the handle to unsubscribe. */
	AutoCloseable addListener(Consumer<State> listener);

	/** {@code handleSelectWhisperModel}: selects the model, downloading it when missing. */
	void selectModel(String modelId);

	/** {@code handleDownloadWhisperSmallModel}. */
	void downloadSmallModel();

	/** {@code handleDeleteWhisperSmallModel}. */
	void deleteSmallModel();

	/** {@code handlePickWhisperModel}: a custom model file ("Use custom"). */
	void pickCustomModel();

	/** {@code handleGenerateAutoCaptions}. */
	void generateCaptions();

	/** The transcript panel's view of the same generation. */
	TranscriptUiService transcript();
}
