package dev.gravaai.services.shortcuts;

/**
 * A parse failure of the user's COSMIC shortcuts file. Port of
 * {@code CosmicShortcutsParseError} in {@code electron/globalShortcuts/cosmicShortcuts.ts}: the
 * message carries the offset, and any of these aborts before anything is written.
 */
public final class CosmicShortcutsParseException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int offset;

	public CosmicShortcutsParseException(String message, int offset) {
		super(message + " at offset " + offset);
		this.offset = offset;
	}

	/** Character offset into the file where the parse failed. */
	public int offset() {
		return offset;
	}
}
