package dev.gravaai.ui.overlays;

import org.jspecify.annotations.Nullable;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.services.recording.CountdownService;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Card;

/**
 * The recording countdown window (port of {@code src/components/countdown/CountdownOverlay.tsx},
 * plan §7.1 P1.6): a 200 px transparent window centred on the primary display with a
 * {@code size-44} card showing the remaining seconds and a ghost Cancel button. Clicking anywhere
 * or pressing Escape cancels the countdown; the service owns the timer and ticks.
 *
 * <p>Wiring contract for {@code AppContext}:
 * <pre>{@code
 * CountdownService countdowns = new CountdownService(new CountdownOverlay(), remaining -> hud.setCountdown(remaining), CountdownService.Scheduler.createDefault());
 * }</pre>
 */
public final class CountdownOverlay implements CountdownService.CountdownWindowFactory {

	/** {@code createCountdownWindow}: 200 px square, centred (windows.ts). */
	public static final double WINDOW_SIZE = 200;
	/** Card {@code size-44} (Tailwind 11rem). */
	private static final double CARD_SIZE = 176;

	private @Nullable Window active;

	/**
	 * Creates and shows the countdown window, from any thread.
	 *
	 * <p>{@code CountdownService.start()} runs on the recording-start worker (a virtual thread), so
	 * the stage must be created on the FX thread and handed back to that worker; creating it
	 * directly threw {@code "Overlay windows must be created on the JavaFX application thread"} and
	 * the countdown — and with it the recording — silently never started.
	 */
	@Override
	public CountdownService.CountdownWindow create() {
		if (Platform.isFxApplicationThread()) {
			return createOnFxThread();
		}
		java.util.concurrent.CompletableFuture<CountdownService.CountdownWindow> created =
				new java.util.concurrent.CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				created.complete(createOnFxThread());
			} catch (RuntimeException | Error error) {
				created.completeExceptionally(error);
			}
		});
		try {
			return created.join();
		} catch (java.util.concurrent.CompletionException error) {
			Throwable cause = error.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error serious) {
				throw serious;
			}
			throw error;
		}
	}

	private CountdownService.CountdownWindow createOnFxThread() {
		OverlayStage.requireFxThread();
		closeActive();
		Window window = new Window();
		active = window;
		window.show();
		return window;
	}

	/** Closes the window if one is open (shutdown/cleanup path; callable from any thread). */
	public void closeActive() {
		Window window = active;
		active = null;
		if (window != null) {
			window.forceClose();
		}
	}

	public boolean isShowing() {
		Window window = active;
		return window != null && window.isVisible();
	}

	private final class Window implements CountdownService.CountdownWindow {

		private final Stage stage;
		private final Label number = new Label();
		private final List<Runnable> closedListeners = new ArrayList<>();
		private final List<Runnable> failedLoadListeners = new ArrayList<>();
		private final List<Runnable> renderGoneListeners = new ArrayList<>();
		private final List<Runnable> loadedListeners = new ArrayList<>();
		private boolean closedFired;
		private volatile boolean destroyed;
		/** Close requested / window no longer mapped; readable from the countdown timer thread. */
		private volatile boolean closed;
		private volatile boolean visible;

		Window() {
			number.setStyle("-fx-text-fill: -g-fg; -fx-font-size: 72px;");
			number.setAlignment(Pos.CENTER);
			Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
			cancel.setOnAction(event -> requestCancel());
			Card card = new Card(number, cancel);
			card.setAlignment(Pos.CENTER);
			card.setPrefSize(CARD_SIZE, CARD_SIZE);
			card.setMinSize(CARD_SIZE, CARD_SIZE);
			card.setMaxSize(CARD_SIZE, CARD_SIZE);
			StackPane content = new StackPane(card);
			content.setAlignment(Pos.CENTER);
			content.setStyle("-fx-background-color: transparent;");
			content.setOnMouseClicked(event -> requestCancel());
			stage = OverlayStage.create("Gravaai countdown");
			stage.setScene(OverlayStage.scene(content, WINDOW_SIZE, WINDOW_SIZE));
			OverlayStage.centerOnPrimary(stage, WINDOW_SIZE, WINDOW_SIZE);
			stage.getScene().setOnKeyPressed(event -> {
				if (event.getCode() == KeyCode.ESCAPE) {
					event.consume();
					requestCancel();
				}
			});
			stage.setOnHidden(event -> {
				visible = false;
				fireClosed();
			});
			stage.setOnCloseRequest(event -> {
				event.consume();
				requestCancel();
			});
		}

		void show() {
			stage.show();
			visible = true;
			stage.toFront();
			stage.requestFocus();
		}

		private void requestCancel() {
			fireClosed();
			if (stage.isShowing()) {
				stage.close();
			}
		}

		boolean isVisible() {
			return visible && !destroyed;
		}

		private void fireClosed() {
			if (closedFired) {
				return;
			}
			closedFired = true;
			for (Runnable listener : List.copyOf(closedListeners)) {
				listener.run();
			}
		}

		/** Closes the stage; safe from the countdown timer thread and the shutdown path. */
		void forceClose() {
			if (!Platform.isFxApplicationThread()) {
				closed = true;
				Platform.runLater(this::forceClose);
				return;
			}
			destroyed = true;
			closed = true;
			visible = false;
			if (stage.isShowing()) {
				stage.close();
			}
		}

		@Override
		public void onClosed(Runnable listener) {
			closedListeners.add(listener);
		}

		@Override
		public void onDidFailLoad(Runnable listener) {
			failedLoadListeners.add(listener);
		}

		@Override
		public void onRenderProcessGone(Runnable listener) {
			renderGoneListeners.add(listener);
		}

		@Override
		public boolean isLoadingMainFrame() {
			// A JavaFX stage is laid out as soon as it is shown; there is no renderer load.
			return false;
		}

		@Override
		public void onLoaded(Runnable listener) {
			loadedListeners.add(listener);
		}

		@Override
		public void sendTick(int remaining) {
			if (!Platform.isFxApplicationThread()) {
				Platform.runLater(() -> sendTick(remaining));
				return;
			}
			number.setText(String.valueOf(remaining));
		}

		@Override
		public boolean isDestroyed() {
			return destroyed || closed || !visible;
		}

		@Override
		public void close() {
			closed = true;
			forceClose();
		}
	}
}
