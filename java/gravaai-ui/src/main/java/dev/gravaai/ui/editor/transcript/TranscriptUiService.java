package dev.gravaai.ui.editor.transcript;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

import dev.gravaai.core.project.CaptionCue;

/**
 * Port of the transcript panel's caption-generation controller
 * ({@code useAutoCaptionController.ts}): exposes the auto-caption state machine and the generated
 * cues without depending on the services implementation (records it here as core caption cues).
 *
 * <p>The app adapts {@code dev.gravaai.services.captions.AutoCaptionService} with
 * {@link AutoCaptionTranscriptUiService}; tests inject a fake.
 */
public interface TranscriptUiService {

	/** Port of the service state machine. */
	enum State {
		IDLE,
		EXTRACTING,
		TRANSCRIBING,
		PARSING,
		DONE,
		ERROR,
		CANCELLED
	}

	/** One immutable status snapshot (progress 0-100, the generated cues on DONE). */
	record Status(State state, int progress, String message, List<CaptionCue> cues, String error) {

		public Status {
			cues = cues == null ? List.of() : List.copyOf(cues);
		}

		public boolean generating() {
			return state == State.EXTRACTING || state == State.TRANSCRIBING
					|| state == State.PARSING;
		}
	}

	Status status();

	/** Subscribes to status pushes; close the handle to unsubscribe. */
	AutoCloseable addListener(Consumer<Status> listener);

	/** Starts caption generation for the current project (no-op when already running). */
	void generateCaptions();

	/** Cancels an in-flight generation. */
	void cancel();

	/** Optional speaker attribution for a cue (services meeting transcripts), or {@code null}. */
	default @Nullable String speakerForCue(String cueId) {
		return null;
	}
}
