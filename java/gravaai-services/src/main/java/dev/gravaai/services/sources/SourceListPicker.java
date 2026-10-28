package dev.gravaai.services.sources;

import java.util.concurrent.CompletableFuture;

/**
 * Keeps the Linux source-list window open until selection or dismissal, ported from
 * {@code electron/sourceListPicker.ts}. The UI creates the window and calls {@link #complete} or
 * {@link #cancel}; the requesting service awaits one result.
 */
public final class SourceListPicker {

	/** The TS result shape {@code { success, canceled?, source? }}. */
	public record SourceListPickResult(boolean success, boolean canceled, SelectedSource source) {

		public static SourceListPickResult picked(SelectedSource source) {
			return new SourceListPickResult(true, false, source);
		}

		public static SourceListPickResult cancelledResult() {
			return new SourceListPickResult(false, true, null);
		}
	}

	private final Runnable createWindow;
	private CompletableFuture<SourceListPickResult> pending;
	private boolean open;

	public SourceListPicker(Runnable createWindow) {
		this.createWindow = createWindow;
	}

	/** Opens (or joins) the source list and resolves when a source is selected or the window closes. */
	public CompletableFuture<SourceListPickResult> open() {
		synchronized (this) {
			if (pending != null) {
				return pending;
			}
			pending = new CompletableFuture<>();
			open = true;
			createWindow.run();
			return pending;
		}
	}

	public synchronized boolean isOpen() {
		return open;
	}

	/** The selection completed; resolves the pending open request. */
	public void complete(SelectedSource source) {
		CompletableFuture<SourceListPickResult> current;
		synchronized (this) {
			current = pending;
			pending = null;
			open = false;
		}
		if (current != null) {
			current.complete(SourceListPickResult.picked(source));
		}
	}

	/** The user dismissed the window without choosing. */
	public void cancel() {
		CompletableFuture<SourceListPickResult> current;
		synchronized (this) {
			current = pending;
			pending = null;
			open = false;
		}
		if (current != null) {
			current.complete(SourceListPickResult.cancelledResult());
		}
	}
}
