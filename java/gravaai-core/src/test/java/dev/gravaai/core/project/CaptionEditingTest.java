package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code captionEditing.test.ts}. */
class CaptionEditingTest {

	private static final CaptionEditing.EditTarget VISIBLE_TARGET = new CaptionEditing.EditTarget(
			"visible-page", 1_000, 2_400, "Hello Hello 你们好啊",
			List.of(
					new CaptionEditing.WordRef("a", 0, 1_000, 1_500, "Hello", false),
					new CaptionEditing.WordRef("a", 1, 1_500, 2_000, "Hello", true),
					new CaptionEditing.WordRef("b", 0, 2_000, 2_400, "你们好啊", true)));

	private static CaptionEditing.WordRef word(String cueId, int index, long startMs, long endMs,
			String text, boolean leadingSpace) {
		return new CaptionEditing.WordRef(cueId, index, startMs, endMs, text, leadingSpace);
	}

	@Test
	@DisplayName("normalizes edited caption text")
	void normalizesEditedCaptionText() {
		assertEquals("hello edited caption",
				CaptionEditing.normalizeCaptionEditText("  hello \n edited\tcaption  "));
		assertEquals("", CaptionEditing.normalizeCaptionEditText(" \n\t "));
	}

	@Test
	@DisplayName("keeps text-only captions text-only after editing")
	void keepsTextOnlyCaptionsTextOnly() {
		List<CaptionCue> updated = CaptionEditing.updateCaptionCuesForEditedTarget(
				List.of(new CaptionCue("a", 1_000, 2_000, "Hello Hello", null),
						new CaptionCue("b", 2_000, 3_000, "你们好啊 这个是我的屏幕", null)),
				VISIBLE_TARGET, "Hi 大家好");

		assertEquals(List.of("Hi", "大家好 这个是我的屏幕"),
				updated.stream().map(CaptionCue::text).toList());
		assertEquals(true, updated.stream().allMatch(caption -> caption.words() == null));

		CaptionLayout.ActiveCaptionLayout layout = CaptionLayout.buildActiveCaptionLayout(updated,
				1_500, AutoCaptionSettings.defaults(), 500, text -> text.length() * 10);
		assertNotNull(layout);
		assertFalse(layout.hasWordTimings());
	}

	@Test
	@DisplayName("preserves cue identity and timing when editing captions with word timings")
	void preservesCueIdentityAndTiming() {
		List<CaptionCue> cues = List.of(
				new CaptionCue("a", 1_000, 2_000, "Hello Hello",
						List.of(new CaptionCueWord("Hello", 1_000, 1_500, null),
								new CaptionCueWord("Hello", 1_500, 2_000, true))),
				new CaptionCue("b", 2_000, 3_000, "你们好啊 这个是我的屏幕",
						List.of(new CaptionCueWord("你们好啊", 2_000, 2_400, null),
								new CaptionCueWord("这个是我的屏幕", 2_400, 3_000, true))));

		List<CaptionCue> updated = CaptionEditing.updateCaptionCuesForEditedTarget(
				cues, VISIBLE_TARGET, "Hi 大家好");

		assertEquals(List.of("a:1000:2000", "b:2000:3000"),
				updated.stream()
						.map(caption -> caption.id() + ":" + caption.startMs() + ":" + caption.endMs())
						.toList());
		assertEquals(List.of(new CaptionCueWord("Hi", 1_000, 2_000, null)), updated.get(0).words());
		assertEquals(List.of(new CaptionCueWord("大家好", 2_000, 2_400, null),
						new CaptionCueWord("这个是我的屏幕", 2_400, 3_000, true)),
				updated.get(1).words());
	}

	@Test
	@DisplayName("does not update captions when edited text is blank")
	void doesNotUpdateOnBlankText() {
		List<CaptionCue> cues = List.of(new CaptionCue("a", 1_000, 2_000, "Hello Hello", null));
		assertSame(cues, CaptionEditing.updateCaptionCuesForEditedTarget(cues, VISIBLE_TARGET, " \n\t "));
	}

	@Test
	@DisplayName("keeps sound-effect style captions editable when word entries are blank")
	void keepsSoundEffectCaptionsEditable() {
		List<CaptionCue> cues = List.of(new CaptionCue("sound-effect", 1_000, 2_000,
				"clears throat", List.of(new CaptionCueWord("", 1_000, 2_000, null))));

		CaptionLayout.ActiveCaptionLayout layout = CaptionLayout.buildActiveCaptionLayout(cues,
				1_500, AutoCaptionSettings.defaults(), 500, text -> text.length() * 10);
		assertNotNull(layout);
		assertEquals("clears throat", layout.editTarget().text());

		List<CaptionCue> updated = CaptionEditing.updateCaptionCuesForEditedTarget(
				cues, layout.editTarget(), "coughs");
		assertEquals("coughs", updated.get(0).text());
		assertEquals(List.of(new CaptionCueWord("coughs", 1_000, 2_000, null)),
				updated.get(0).words());
		assertNull(word("x", 0, 0, 0, "x", false).leadingSpace() ? null : null);
	}
}
