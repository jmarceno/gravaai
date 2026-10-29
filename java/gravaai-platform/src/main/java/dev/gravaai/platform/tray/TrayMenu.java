package dev.gravaai.platform.tray;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The tray context menu (plan §5.5), copied from {@code electron/main.ts}
 * {@code createTray}/{@code updateTrayMenu}: <i>Show Overlay</i>, <i>Open Gravaai</i>, a separator
 * and <i>Quit</i>; a <i>Stop Recording</i> entry is added while a recording is running (plan §2.1).
 * The labels and order are the source of truth here, so the D-Bus layer stays declarative.
 */
public final class TrayMenu {

	public static final int ROOT_ID = 0;
	public static final int SHOW_OVERLAY_ID = 1;
	public static final int OPEN_GRAVAAI_ID = 2;
	public static final int STOP_RECORDING_ID = 3;
	public static final int SEPARATOR_ID = 4;
	public static final int QUIT_ID = 5;

	public static final String TYPE_STANDARD = "standard";
	public static final String TYPE_SEPARATOR = "separator";

	public static final String LABEL_SHOW_OVERLAY = "Show Overlay";
	public static final String LABEL_OPEN_GRAVAAI = "Open Gravaai";
	public static final String LABEL_STOP_RECORDING = "Stop Recording";
	public static final String LABEL_QUIT = "Quit";

	/** One clickable entry of the menu. */
	public record Item(int id, String label, String type) {
		public boolean separator() {
			return TYPE_SEPARATOR.equals(type);
		}
	}

	/** What the menu entries do; wired by the app. */
	public record Actions(Runnable showOverlay, Runnable openGravaai, Runnable stopRecording, Runnable quit) {
		public Actions {
			Objects.requireNonNull(showOverlay, "showOverlay");
			Objects.requireNonNull(openGravaai, "openGravaai");
			Objects.requireNonNull(stopRecording, "stopRecording");
			Objects.requireNonNull(quit, "quit");
		}
	}

	private final boolean recording;
	private final List<Item> items;

	public TrayMenu(boolean recording) {
		this.recording = recording;
		this.items = List.copyOf(build(recording));
	}

	private static List<Item> build(boolean recording) {
		List<Item> items = new ArrayList<>();
		items.add(new Item(SHOW_OVERLAY_ID, LABEL_SHOW_OVERLAY, TYPE_STANDARD));
		items.add(new Item(OPEN_GRAVAAI_ID, LABEL_OPEN_GRAVAAI, TYPE_STANDARD));
		if (recording) {
			items.add(new Item(STOP_RECORDING_ID, LABEL_STOP_RECORDING, TYPE_STANDARD));
		}
		items.add(new Item(SEPARATOR_ID, "", TYPE_SEPARATOR));
		items.add(new Item(QUIT_ID, LABEL_QUIT, TYPE_STANDARD));
		return items;
	}

	public boolean isRecording() {
		return recording;
	}

	public List<Item> items() {
		return items;
	}

	/** The item with {@code id}, or {@code null} for the root and unknown ids. */
	public @Nullable Item item(int id) {
		for (Item item : items) {
			if (item.id() == id) {
				return item;
			}
		}
		return null;
	}

	/**
	 * Runs the action bound to a clicked menu entry.
	 *
	 * @return {@code false} for unknown ids and separators
	 */
	public boolean dispatch(int id, Actions actions) {
		switch (id) {
			case SHOW_OVERLAY_ID -> actions.showOverlay().run();
			case OPEN_GRAVAAI_ID -> actions.openGravaai().run();
			case STOP_RECORDING_ID -> actions.stopRecording().run();
			case QUIT_ID -> actions.quit().run();
			default -> {
				return false;
			}
		}
		return true;
	}
}
