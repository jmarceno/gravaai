package dev.gravaai.services.meeting;

import java.util.ArrayList;
import java.util.List;

/** Small string helpers shared by error reporting. */
public final class Texts {

	private Texts() {
	}

	/** The last {@code count} non-empty lines of {@code text}, joined with spaces. */
	public static String lastLines(String text, int count) {
		if (text == null) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		for (String line : text.strip().split("\n")) {
			if (!line.isEmpty()) {
				lines.add(line);
			}
		}
		List<String> tail = lines.subList(Math.max(0, lines.size() - count), lines.size());
		return String.join(" ", tail);
	}
}
