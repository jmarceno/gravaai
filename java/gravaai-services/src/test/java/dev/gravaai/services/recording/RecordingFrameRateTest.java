package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Settings → Recording frame-rate values (card "Recording settings"). */
class RecordingFrameRateTest {

	@Test
	@DisplayName("keeps the known rates and falls back to the default")
	void keepsKnownRatesAndFallsBack() {
		assertEquals(RecordingFrameRate.FPS_60, RecordingFrameRate.DEFAULT);
		assertEquals(RecordingFrameRate.FPS_30, RecordingFrameRate.normalize("30"));
		assertEquals(RecordingFrameRate.FPS_60, RecordingFrameRate.normalize("60"));
		assertEquals(RecordingFrameRate.DEFAULT, RecordingFrameRate.normalize("120"));
		assertEquals(RecordingFrameRate.DEFAULT, RecordingFrameRate.normalize(null));
	}

	@Test
	@DisplayName("the wire value is the frames-per-second count")
	void wireValueIsTheFps() {
		for (RecordingFrameRate rate : RecordingFrameRate.values()) {
			assertEquals(Integer.parseInt(rate.wire()), rate.fps());
		}
	}
}
