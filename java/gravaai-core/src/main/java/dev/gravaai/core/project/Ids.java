package dev.gravaai.core.project;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Id helpers shared by the editor model (port of {@code deriveNextId} in projectPersistence.ts). */
public final class Ids {

	private Ids() {}

	/** {@code deriveNextId}: highest {@code <prefix>-<n>} plus one; labels and other ids ignored. */
	public static int deriveNextId(String prefix, List<String> ids) {
		Pattern pattern = Pattern.compile("^" + Pattern.quote(prefix) + "-(\\d+)$");
		long max = 0;
		for (String id : ids) {
			Matcher matcher = pattern.matcher(id);
			if (!matcher.matches()) {
				continue;
			}
			try {
				long value = Long.parseLong(matcher.group(1));
				max = Math.max(max, value);
			} catch (NumberFormatException error) {
				// Number.isFinite(value) is false for absurdly large ids; ignore them.
			}
		}
		return (int) (max + 1);
	}
}
