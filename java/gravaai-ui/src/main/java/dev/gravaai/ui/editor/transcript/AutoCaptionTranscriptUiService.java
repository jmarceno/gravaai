package dev.gravaai.ui.editor.transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionCueWord;
import dev.gravaai.services.captions.AutoCaptionService;
import dev.gravaai.services.captions.CaptionGenerator;

/**
 * Adapter from {@link AutoCaptionService} to the UI {@link TranscriptUiService} port: maps the
 * service state machine, converts generated cues to the core project caption model and forwards the
 * options the editor session resolved for this project.
 */
public final class AutoCaptionTranscriptUiService implements TranscriptUiService, AutoCloseable {

	private final AutoCaptionService service;
	private final Supplier<CaptionGenerator.Options> options;
	private final AutoCloseable subscription;

	public AutoCaptionTranscriptUiService(AutoCaptionService service,
			Supplier<CaptionGenerator.Options> options) {
		this.service = Objects.requireNonNull(service, "service");
		this.options = options;
		this.subscription = service.addListener(status -> { });
	}

	@Override
	public Status status() {
		return convert(service.status());
	}

	@Override
	public AutoCloseable addListener(Consumer<Status> listener) {
		return service.addListener(status -> listener.accept(convert(status)));
	}

	@Override
	public void generateCaptions() {
		CaptionGenerator.Options next = options == null ? null : options.get();
		if (next != null) {
			service.start(next);
		}
	}

	@Override
	public void cancel() {
		service.cancel();
	}

	@Override
	public void close() {
		try {
			subscription.close();
		} catch (Exception ignored) {
			// unsubscribing is best-effort
		}
		service.close();
	}

	private static Status convert(AutoCaptionService.Status status) {
		return new Status(State.valueOf(status.state().name()), status.progress(),
				status.message(), projectCues(status.cues()), status.error());
	}

	/** Generated cues in the project model. */
	public static List<CaptionCue> projectCues(
			List<dev.gravaai.services.captions.CaptionCue> generated) {
		List<CaptionCue> cues = new ArrayList<>(generated.size());
		for (dev.gravaai.services.captions.CaptionCue cue : generated) {
			List<CaptionCueWord> words = new ArrayList<>(cue.words().size());
			for (dev.gravaai.services.captions.CaptionWord word : cue.words()) {
				words.add(new CaptionCueWord(word.text(), word.startMs(), word.endMs(),
						word.leadingSpace() ? Boolean.TRUE : null));
			}
			cues.add(new CaptionCue(cue.id(), cue.startMs(), cue.endMs(), cue.text(), words));
		}
		return cues;
	}
}