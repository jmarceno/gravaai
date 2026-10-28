package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Ported from {@code src/components/video-editor/videoPlayback/usePreviewVideoReady.test.ts}. */
@Timeout(15)
class PreviewVideoReadinessTest {

	private final List<Boolean> events = new ArrayList<>();
	private final PreviewVideoReadiness readiness = new PreviewVideoReadiness(events::add);

	private Boolean last() {
		return events.isEmpty() ? null : events.get(events.size() - 1);
	}

	@Test
	@DisplayName("becomes ready after every source change without a polling callback")
	void becomesReadyAfterEverySourceChange() {
		for (int index = 0; index < 10; index++) {
			readiness.detach();
			readiness.attach(new PreviewVideoReadiness.MediaState(0, 0, 0));
			assertEquals(Boolean.FALSE, last());
			readiness.onMediaState(new PreviewVideoReadiness.MediaState(2, 1920, 1080));
			readiness.onLoadedData();
			assertEquals(Boolean.TRUE, last());
		}
		readiness.detach();
		events.clear();
		readiness.onLoadedData();
		assertTrue(events.isEmpty());
	}

	@Test
	@DisplayName("recognizes an already loaded source when subscribing")
	void recognizesAnAlreadyLoadedSource() {
		readiness.attach(new PreviewVideoReadiness.MediaState(4, 1920, 1080));
		assertEquals(Boolean.TRUE, last());
		assertTrue(readiness.ready());
		readiness.onMediaState(new PreviewVideoReadiness.MediaState(0, 0, 0));
		readiness.onEmptied();
		assertEquals(Boolean.FALSE, last());
		assertFalse(readiness.ready());
	}

	@Test
	@DisplayName("retains the preview through transient readiness drops during boundary seeks")
	void retainsPreviewThroughTransientDrops() {
		readiness.attach(new PreviewVideoReadiness.MediaState(4, 1920, 1080));
		events.clear();
		for (int index = 0; index < 10; index++) {
			readiness.onMediaState(new PreviewVideoReadiness.MediaState(1, 1920, 1080));
			readiness.onSeeked();
			readiness.onMediaState(new PreviewVideoReadiness.MediaState(2, 1920, 1080));
			readiness.onLoadedData();
		}
		assertFalse(events.contains(Boolean.FALSE));
		readiness.onError();
		assertEquals(Boolean.FALSE, last());
	}
}
