package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.playback.PlaybackFrame;

/** P2.11: the editor session attaches real fixture media and decodes a first frame. */
class EditorSessionRealMediaTest {

	private static final Path FIXTURE = Path
			.of("/home/jmarceno/Projects/gravaai/java/parity/fixtures/projects/03-zoom-motion-blur.gravaai");

	@Test
	@DisplayName("attaches fixture media and decodes the first frame")
	void attachesFixtureMediaAndDecodesFrame() throws Exception {
		assertTrue(Files.isRegularFile(FIXTURE), "fixture project is committed");
		try (EditorSession session = EditorSession.open(FIXTURE)) {
			CountDownLatch frame = new CountDownLatch(1);
			session.engine().onFrame(value -> frame.countDown());
			long started = System.nanoTime();
			session.attachMedia();
			boolean decoded = frame.await(25, TimeUnit.SECONDS);
			long elapsedMs = (System.nanoTime() - started) / 1_000_000;
			System.out.println("attachMedia first frame: " + decoded + " in " + elapsedMs + " ms, status="
					+ session.engine().status());
			assertTrue(decoded, "a first PlaybackFrame must arrive after attachMedia");
			assertTrue(session.engine().status().durationSeconds() > 0, "media duration must be probed");
		}
	}
}
