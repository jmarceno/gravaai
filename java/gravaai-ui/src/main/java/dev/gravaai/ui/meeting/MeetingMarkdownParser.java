package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the small Markdown subset meeting notes and transcripts use (port of the parsing
 * half of {@code MeetingMarkdown.tsx}): headings #{1,4}, bullets/checklists, blank-line separated
 * paragraphs and [HH:MM:SS] timestamps.
 *
 * <p>Rendering lives in {@link MeetingMarkdownView}; keeping the parser pure makes the block and
 * timestamp rules testable without JavaFX.
 */
public final class MeetingMarkdownParser {

	/** Port of the TS TIMESTAMP pattern: a leading [HH:MM:SS] plus optional whitespace. */
	private static final Pattern TIMESTAMP =
			Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})\\]\\s*");

	private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.*)$");
	private static final Pattern CHECKLIST =
			Pattern.compile("^\\s*[-*]\\s+\\[([ xX])\\]\\s+(.*)$");
	private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*]\\s+(.*)$");

	/** One rendered block; bullets/checklists are grouped by the view. */
	public sealed interface Block permits Heading, Bullet, Paragraph {
	}

	/** A heading level 1-4. */
	public record Heading(int level, String text) implements Block {
	}

	/** A bullet or checklist item; {@code checked} is null for plain bullets. */
	public record Bullet(int depth, @Nullable Boolean checked, String text) implements Block {
	}

	/** A plain paragraph line. */
	public record Paragraph(String text) implements Block {
	}

	private MeetingMarkdownParser() {
	}

	/** Port of parseTimestampSeconds: seconds for a leading [HH:MM:SS], else null. */
	public static @Nullable Integer parseTimestampSeconds(String text) {
		if (text == null) {
			return null;
		}
		Matcher match = TIMESTAMP.matcher(text);
		if (!match.find()) {
			return null;
		}
		return Integer.parseInt(match.group(1)) * 3600 + Integer.parseInt(match.group(2)) * 60
				+ Integer.parseInt(match.group(3));
	}

	/** The end offset of the leading timestamp token (label plus whitespace), or 0. */
	public static int timestampPrefixLength(String text) {
		if (text == null) {
			return 0;
		}
		Matcher match = TIMESTAMP.matcher(text);
		return match.find() ? match.end() : 0;
	}

	/** Port of the block loop in MeetingMarkdown: headings, bullets, paragraphs. */
	public static List<Block> parse(String source) {
		List<Block> blocks = new ArrayList<>();
		if (source == null || source.isEmpty()) {
			return blocks;
		}
		String[] lines = source.split("\n", -1);
		for (String rawLine : lines) {
			String line = trimEnd(rawLine);
			if (line.trim().isEmpty()) {
				continue;
			}
			Matcher heading = HEADING.matcher(line);
			if (heading.matches()) {
				blocks.add(new Heading(heading.group(1).length(), heading.group(2)));
				continue;
			}
			Matcher checklist = CHECKLIST.matcher(line);
			if (checklist.matches()) {
				boolean checked = !" ".equals(checklist.group(1));
				blocks.add(new Bullet(0, checked, checklist.group(2)));
				continue;
			}
			Matcher bullet = BULLET.matcher(line);
			if (bullet.matches()) {
				// TS: paddingLeft = min(3, floor(indent.length / 2)) * 16, so keep the multiplier.
				int depth = Math.min(3, Math.floorDiv(bullet.group(1).length(), 2));
				blocks.add(new Bullet(depth, null, bullet.group(2)));
				continue;
			}
			blocks.add(new Paragraph(line));
		}
		return List.copyOf(blocks);
	}

	/** JavaScript trimEnd(): whitespace at the end, including CR. */
	private static String trimEnd(String value) {
		int end = value.length();
		while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(0, end);
	}
}
