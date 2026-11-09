package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The area-source helper the recording pipeline uses. */
class SourceAreaTest {

	@Test
	@DisplayName("reads the rectangle back from an area source")
	void readsTheRectangleOfAnAreaSource() {
		SelectedSource source = SelectedSource.area(
				"1", new CaptureArea(10, 20, 640, 480), "Area 640×480");

		assertEquals(new CaptureArea(10, 20, 640, 480), SourceArea.getSourceArea(source));
	}

	@Test
	@DisplayName("ignores sources that are not areas")
	void ignoresSourcesThatAreNotAreas() {
		assertNull(SourceArea.getSourceArea(SelectedSource.screen("screen:1:0", "Screen", null)));
		assertNull(SourceArea.getSourceArea(SelectedSource.window("window:1:0", "W", "1", "app", "W")));
		assertNull(SourceArea.getSourceArea(null));
	}
}
