package dev.gravaai.services.extensions;

/**
 * The closed permission set of {@code EXTENSIONS.md} ("Permissions"). A manifest may only request
 * these; every host API that needs one checks it before acting.
 */
public enum ExtensionPermission {
	/** Render hook registration. */
	RENDER("render"),
	/** Cursor telemetry and cursor effect registration. */
	CURSOR("cursor"),
	/** Bundled sound playback. */
	AUDIO("audio"),
	/** Playback and timeline events. */
	TIMELINE("timeline"),
	/** Settings panels and device frame registration. */
	UI("ui"),
	/** Bundled asset resolution plus wallpaper and cursor style registration. */
	ASSETS("assets"),
	/** Export lifecycle events. */
	EXPORT("export");

	private final String wire;

	ExtensionPermission(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	/** The permission for a manifest string, or null when it is not in the closed set. */
	public static ExtensionPermission fromWire(String value) {
		if (value == null) {
			return null;
		}
		for (ExtensionPermission permission : values()) {
			if (permission.wire.equals(value)) {
				return permission;
			}
		}
		return null;
	}

	/** Every permission wire string, for validation error messages. */
	public static String wireValues() {
		StringBuilder text = new StringBuilder();
		for (ExtensionPermission permission : values()) {
			if (text.length() > 0) {
				text.append(", ");
			}
			text.append(permission.wire);
		}
		return text.toString();
	}
}
