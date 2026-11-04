package dev.gravaai.services.elements;

import tools.jackson.databind.node.ObjectNode;

/**
 * What a component module declares through its exports.
 *
 * @param name the component name
 * @param fps nominal frames per second (export {@code fps}, default 30); frames handed to
 *        {@code render} are {@code localMs * fps / 1000}, independent of the export rate
 * @param durationInFrames default length for new elements (export {@code durationInFrames},
 *        default 90)
 * @param defaultProps export {@code defaultProps} ({@code {}} when absent)
 * @param propsSchema export {@code propsSchema} ({@code {}} when absent): {@code name: type}
 *        where type is {@code "string"}, {@code "number"}, {@code "boolean"}, {@code "color"} or an
 *        object such as {@code {type:"number", min:0, max:1}} / {@code {type:"enum", values:[…]}}
 * @param description export {@code description} (empty when absent)
 */
public record ComponentInfo(String name, double fps, double durationInFrames,
		ObjectNode defaultProps, ObjectNode propsSchema, String description) {

	public static final double DEFAULT_FPS = 30;
	public static final double DEFAULT_DURATION_IN_FRAMES = 90;

	public ComponentInfo {
		defaultProps = defaultProps.deepCopy();
		propsSchema = propsSchema.deepCopy();
	}

	/** Default element length in milliseconds. */
	public long defaultDurationMs() {
		return Math.max(100, Math.round(durationInFrames * 1000.0 / fps));
	}
}
