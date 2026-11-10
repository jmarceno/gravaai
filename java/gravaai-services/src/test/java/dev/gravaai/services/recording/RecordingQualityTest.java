package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Settings → Recording quality values (card "Recording settings"). */
class RecordingQualityTest {

	@Test
	@DisplayName("keeps the known levels and falls back to the default")
	void keepsKnownLevelsAndFallsBack() {
		assertEquals(RecordingQuality.HIGH, RecordingQuality.DEFAULT);
		assertEquals(RecordingQuality.STANDARD, RecordingQuality.normalize("standard"));
		assertEquals(RecordingQuality.HIGH, RecordingQuality.normalize("high"));
		assertEquals(RecordingQuality.LOSSLESS, RecordingQuality.normalize("lossless"));
		assertEquals(RecordingQuality.DEFAULT, RecordingQuality.normalize("ultra"));
		assertEquals(RecordingQuality.DEFAULT, RecordingQuality.normalize(null));
	}

	@Test
	@DisplayName("a lower crf means a higher quality level")
	void lowerCrfMeansHigherQuality() {
		assertEquals(25, RecordingQuality.STANDARD.crf());
		assertEquals(18, RecordingQuality.HIGH.crf());
		assertEquals(1, RecordingQuality.LOSSLESS.crf());
	}
}
