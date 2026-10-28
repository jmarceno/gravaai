package dev.gravaai.ui.editor.timeline;

import dev.gravaai.ui.editor.timeline.core.Span;

/**
 * One agent highlight band over the timeline (shell for {@code AgentTimelineHighlights.tsx}, plan
 * P3.2 fills the data; P2.7 reserves the drawing path so the overlay never needs restructuring).
 *
 * @param span the highlighted timeline range
 * @param label shown in the band when it is wide enough
 */
public record AgentHighlight(Span span, String label) {
}
