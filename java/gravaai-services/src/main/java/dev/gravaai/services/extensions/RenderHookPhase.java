package dev.gravaai.services.extensions;

import org.jspecify.annotations.Nullable;

/**
 * Render pipeline phases of {@code EXTENSIONS.md} ("Render Pipeline"). {@code background} exists in
 * the type surface but is reserved and never dispatched.
 */
public enum RenderHookPhase {
	BACKGROUND("background", false),
	POST_VIDEO("post-video", true),
	POST_ZOOM("post-zoom", true),
	POST_CURSOR("post-cursor", true),
	POST_WEBCAM("post-webcam", true),
	POST_ANNOTATIONS("post-annotations", true),
	FINAL("final", true);

	private final String wire;
	private final boolean dispatched;

	RenderHookPhase(String wire, boolean dispatched) {
		this.wire = wire;
		this.dispatched = dispatched;
	}

	public String wire() {
		return wire;
	}

	/** False only for the reserved {@code background} phase. */
	public @Nullable boolean dispatched() {
		return dispatched;
	}

	public static @Nullable RenderHookPhase fromWire(String value) {
		if (value == null) {
			return null;
		}
		for (RenderHookPhase phase : values()) {
			if (phase.wire.equals(value)) {
				return phase;
			}
		}
		return null;
	}
}
