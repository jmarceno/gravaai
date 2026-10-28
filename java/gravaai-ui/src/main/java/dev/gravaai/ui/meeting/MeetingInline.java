package dev.gravaai.ui.meeting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline tokenizer for meeting Markdown (port of renderInline in MeetingMarkdown.tsx): bold,
 * inline code and italics. Rendering lives in {@link MeetingMarkdownView}.
 */
public final class MeetingInline {

	/** Port of the TS inline pattern, in the same order. */
	private static final Pattern TOKEN =
			Pattern.compile("(\\*\\*[^*]+\\*\\*|\u0060[^\u0060]+\u0060|\\*[^*\\s][^*]*\\*|_[^_\\s][^_]*_)");

	/** One inline run. */
	public sealed interface Token permits Text, Bold, Code, Italic {
	}

	/** Literal text. */
	public record Text(String text) implements Token {
	}

	/** Bold text. */
	public record Bold(String text) implements Token {
	}

	/** Inline code. */
	public record Code(String text) implements Token {
	}

	/** Italic text. */
	public record Italic(String text) implements Token {
	}

	private MeetingInline() {
	}

	/** Splits one line into inline runs; unmatched text stays a single text run. */
	public static List<Token> tokenize(String text) {
		List<Token> tokens = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return tokens;
		}
		Matcher matcher = TOKEN.matcher(text);
		int last = 0;
		while (matcher.find()) {
			int index = matcher.start();
			if (index > last) {
				tokens.add(new Text(text.substring(last, index)));
			}
			String token = matcher.group();
			if (token.startsWith("**")) {
				tokens.add(new Bold(token.substring(2, token.length() - 2)));
			} else if (token.startsWith("\u0060")) {
				tokens.add(new Code(token.substring(1, token.length() - 1)));
			} else {
				tokens.add(new Italic(token.substring(1, token.length() - 1)));
			}
			last = matcher.end();
		}
		if (last < text.length()) {
			tokens.add(new Text(text.substring(last)));
		}
		return tokens;
	}
}
