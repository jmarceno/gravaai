package dev.gravaai.ui.agent;

/**
 * One highlight band the timeline overlay draws: the agent's {@link AgentHighlightUpdate} plus the
 * generated id and the clamped span the overlay actually shows.
 */
public record AgentHighlightView(String id, AgentHighlightUpdate.Kind kind, long startMs, long endMs,
		String label) {

	private static final int MAX_LABEL = 40;

	/** The pill text: {@code ✦ AI cut} for cuts, {@code ✦ AI added <name>} / changed for bands. */
	public String displayLabel() {
		return switch (kind) {
			case CUT -> "✦ AI cut";
			case ADDED -> labelled("AI added");
			case CHANGED -> labelled("AI changed");
		};
	}

	/** {@code ✦ AI added <name>} — the touched span's label (component name) when there is one. */
	private String labelled(String prefix) {
		if (label == null || label.isBlank()) {
			return "✦ " + prefix;
		}
		String name = label.trim();
		if (name.length() > MAX_LABEL) {
			name = name.substring(0, MAX_LABEL - 3) + "...";
		}
		return "✦ " + prefix + " " + name;
	}
}
