package dev.gravaai.ui;

import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.event.UiEvents;
import java.util.function.Consumer;
import javafx.application.Platform;

/**
 * Bridges a JavaFX-free {@link UiEvents} publisher to the FX application thread (plan §4.1:
 * services publish events, the UI adapter hops to the FX thread with {@link Platform#runLater}).
 */
public final class UiEventsFx {

	private UiEventsFx() {
	}

	/** Subscribes {@code listener} so it always runs on the FX application thread. */
	public static <T> Subscription onFx(UiEvents<T> events, Consumer<T> listener) {
		return events.subscribe(event -> {
			if (Platform.isFxApplicationThread()) {
				listener.accept(event);
			} else {
				Platform.runLater(() -> listener.accept(event));
			}
		});
	}
}
