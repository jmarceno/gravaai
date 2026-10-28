package dev.gravaai.ui.agent;

import java.util.List;

/**
 * A span an agent touched, used for the transient timeline highlight (port of
 * {@code AgentHighlight} in {@code video-editor/agent/types.ts}).
 */
public record AgentHighlightUpdate(Kind kind, long startMs, long endMs, String label) {

	/** Agent highlight kinds and their timeline colours ({@code KIND_COLORS}). */
	public enum Kind {
		CUT("cut", 0xFFF97316),
		ADDED("added", 0xFF8B5CF6),
		CHANGED("changed", 0xFF2563EB);

		private final String wire;
		private final int argb;

		Kind(String wire, int argb) {
			this.wire = wire;
			this.argb = argb;
		}

		public String wire() {
			return wire;
		}

		/** The highlight colour as ARGB ({@code KIND_COLORS} in the React source). */
		public int argb() {
			return argb;
		}

		public static Kind fromWire(String value) {
			if (value != null) {
				for (Kind kind : values()) {
					if (kind.wire.equals(value)) {
						return kind;
					}
				}
			}
			return CHANGED;
		}
	}

	public AgentHighlightUpdate {
		if (endMs < startMs) {
			throw new IllegalArgumentException("highlight ends before it starts: " + startMs + " -> " + endMs);
		}
	}

	/** Convenience for the bridge: all highlights in one call. */
	public static List<AgentHighlightUpdate> of(AgentHighlightUpdate... updates) {
		return List.of(updates);
	}
}
