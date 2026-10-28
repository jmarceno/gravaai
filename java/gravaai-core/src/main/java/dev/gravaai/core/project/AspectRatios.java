package dev.gravaai.core.project;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Aspect-ratio presets (port of the parts of {@code src/utils/aspectRatioUtils.ts} used by the
 *  project model). {@code AspectRatio} is a string in TS ({@code PresetAspectRatio | custom}); a
 *  Java enum cannot model the {@code "W:H"} custom form, so the type stays {@code String}. */
public final class AspectRatios {

	public static final List<String> PRESETS =
			List.of("native", "16:9", "9:16", "1:1", "4:3", "4:5", "16:10", "10:16");

	private static final Pattern CUSTOM = Pattern.compile("^(\\d+):(\\d+)$");

	private AspectRatios() {}

	/** {@code isCustomAspectRatio}. */
	public static boolean isCustomAspectRatio(String aspectRatio) {
		if (aspectRatio == null || PRESETS.contains(aspectRatio)) {
			return false;
		}
		Matcher matcher = CUSTOM.matcher(aspectRatio);
		if (!matcher.matches()) {
			return false;
		}
		long width = Long.parseLong(matcher.group(1));
		long height = Long.parseLong(matcher.group(2));
		return width > 0 && height > 0;
	}

	public static boolean isValidAspectRatio(String aspectRatio) {
		return aspectRatio != null && (PRESETS.contains(aspectRatio) || isCustomAspectRatio(aspectRatio));
	}
}
