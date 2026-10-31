package dev.gravaai.ui.editor.timeline;

/**
 * The selected timeline block (port of the per-kind selected ids in {@code TimelineCanvas.tsx}).
 *
 * @param kind {@code "zoom"}, {@code "clip"}, {@code "annotation"}, {@code "audio"},
 *     {@code "caption"} or {@code "keystroke"}
 * @param id the block id
 */
public record TimelineSelection(String kind, String id) {

	public static final String ZOOM = "zoom";
	public static final String CLIP = "clip";
	public static final String ANNOTATION = "annotation";
	public static final String AUDIO = "audio";
	public static final String CAPTION = "caption";
	public static final String KEYSTROKE = "keystroke";

	public TimelineSelection {
		if (kind == null || kind.isBlank()) {
			throw new IllegalArgumentException("selection kind must not be blank");
		}
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("selection id must not be blank");
		}
	}
}
