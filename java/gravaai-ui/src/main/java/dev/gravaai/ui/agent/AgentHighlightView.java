package dev.gravaai.ui.agent;

/**
 * One highlight band the timeline overlay draws: the agent's {@link AgentHighlightUpdate} plus the
 * generated id and the clamped span the overlay actually shows.
 */
public record AgentHighlightView(String id, AgentHighlightUpdate.Kind kind, long startMs, long endMs,
		String label) {

	/** The label shown on a cut marker ({@code ✦ AI cut} in the React source). */
	public String displayLabel() {
		// The React cut pill always reads "✦ AI cut"; the touched span's label lives in the tooltip.
		if (kind == AgentHighlightUpdate.Kind.CUT) {
			return "✦ AI cut";
		}
		return label == null ? "" : label;
	}
}
