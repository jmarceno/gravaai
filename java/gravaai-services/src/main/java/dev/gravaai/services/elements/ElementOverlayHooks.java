package dev.gravaai.services.elements;

import java.util.function.Supplier;

import io.github.humbleui.skija.Canvas;

import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.FrameOverlayHooks;

/**
 * Installs the elements lane into a compositor through the render-hook seam: elements draw at the
 * {@code post-annotations} phase (after annotations and loupes, before captions and key chips),
 * then the wrapped hooks (the extensions) run as before.
 *
 * <p>Every render path installs one — the editor preview, the UI export, the agent's
 * {@code frames} and the agent's export — so what the agent looks at is what gets exported.
 */
public final class ElementOverlayHooks implements FrameOverlayHooks, AutoCloseable {

	private final FrameOverlayHooks delegate;
	private final JsElementRuntime runtime;
	private final Supplier<ProjectEditorState> state;

	/**
	 * @param delegate the hooks to run after the elements (extensions), or {@code NONE}
	 * @param diagnostics where element errors are reported
	 * @param state the editor state to draw (read per frame, so preview follows edits)
	 */
	public ElementOverlayHooks(FrameOverlayHooks delegate, ElementDiagnostics diagnostics,
			Supplier<ProjectEditorState> state) {
		this.delegate = delegate;
		this.runtime = new JsElementRuntime(diagnostics);
		this.state = state;
	}

	public JsElementRuntime runtime() {
		return runtime;
	}

	@Override
	public void render(Phase phase, Canvas canvas, FrameInfo frame) {
		if (phase == Phase.POST_ANNOTATIONS) {
			ProjectEditorState current = state.get();
			if (!current.elements().isEmpty()) {
				runtime.render(canvas, frame.width(), frame.height(), frame.exactTimeMs(),
						current.elements(), current.elementComponents());
			}
		}
		if (delegate != FrameOverlayHooks.NONE) {
			delegate.render(phase, canvas, frame);
		}
	}

	/**
	 * Throws when any element failed while rendering through these hooks; exports call this so a
	 * broken element fails the export instead of silently disappearing from it.
	 */
	public void requireNoFailures() {
		runtime.failures().stream().findFirst().ifPresent(error -> {
			throw new IllegalStateException("element " + error.elementId() + " failed at "
					+ String.format(java.util.Locale.ROOT, "%.2f", error.timeMs() / 1000.0) + "s: "
					+ error.message());
		});
	}

	@Override
	public void close() {
		runtime.close();
	}
}
