package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure part of ContentClamp (content-clamp.tsx truncation rule). */
class ContentClampTest {

	@DisplayName("text longer than the limit is cut and gets an ellipsis")
	@Test
	void truncates() {
		assertEquals("abc\u2026", KitText.truncate("abcdef", 3));
		assertEquals("abcdef", KitText.truncate("abcdef", 6));
		assertEquals("", KitText.truncate(null, 10));
		assertEquals("", KitText.truncate("", 10));
	}

	@DisplayName("default truncate length is 50 (content-clamp.tsx)")
	@Test
	void defaultLength() {
		assertEquals(50, KitText.DEFAULT_TRUNCATE_LENGTH);
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 60; i++) {
			text.append('x');
		}
		assertEquals(51, KitText.truncate(text.toString(), KitText.DEFAULT_TRUNCATE_LENGTH).length());
	}
}
