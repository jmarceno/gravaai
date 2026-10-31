package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Group windows of the keystroke overlay: element spans win, legacy presses keep the linger. */
class KeystrokeRendererGroupsTest {

	private static KeystrokeSpec spec(List<KeystrokeSpec.Press> presses) {
		return new KeystrokeSpec(true, "bottom-center", 34, 900, 150, 600, 12, presses);
	}

	@Test
	@DisplayName("word elements inside the grouping window share a group spanning their windows")
	void wordElementsShareGroupWindow() {
		List<KeystrokeRenderer.Group> groups = KeystrokeRenderer.buildGroups(spec(List.of(
				new KeystrokeSpec.Press(1_000, 2_000, List.of("Hello")),
				new KeystrokeSpec.Press(1_100, 2_300, List.of("World")))));

		assertEquals(1, groups.size());
		assertEquals(1_000, groups.get(0).startMs());
		assertEquals(2_300, groups.get(0).endMs());
		assertEquals(2, groups.get(0).presses().size());
	}

	@Test
	@DisplayName("elements beyond the grouping window stay separate groups")
	void elementsBeyondWindowStaySeparate() {
		List<KeystrokeRenderer.Group> groups = KeystrokeRenderer.buildGroups(spec(List.of(
				new KeystrokeSpec.Press(1_000, 2_000, List.of("Hello")),
				new KeystrokeSpec.Press(5_000, 6_000, List.of("World")))));

		assertEquals(2, groups.size());
		assertEquals(2_000, groups.get(0).endMs());
		assertEquals(6_000, groups.get(1).endMs());
	}

	@Test
	@DisplayName("legacy presses without an explicit window end at time plus linger")
	void legacyPressesUseLinger() {
		List<KeystrokeRenderer.Group> groups = KeystrokeRenderer.buildGroups(spec(List.of(
				new KeystrokeSpec.Press(300, List.of("Ctrl", "S")),
				new KeystrokeSpec.Press(700, List.of("Enter")))));

		assertEquals(1, groups.size());
		assertEquals(300, groups.get(0).startMs());
		assertEquals(700 + 900, groups.get(0).endMs());
	}

	@Test
	@DisplayName("presses with empty chip labels are skipped")
	void emptyPressesAreSkipped() {
		List<KeystrokeRenderer.Group> groups = KeystrokeRenderer.buildGroups(spec(List.of(
				new KeystrokeSpec.Press(300, 1_000, List.of()),
				new KeystrokeSpec.Press(400, 1_100, List.of("A")))));

		assertEquals(1, groups.size());
		assertEquals(1, groups.get(0).presses().size());
		assertTrue(groups.get(0).startMs() == 400);
	}
}
