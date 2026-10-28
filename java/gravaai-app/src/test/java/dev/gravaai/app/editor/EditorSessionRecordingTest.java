package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.project.ProjectDocument;

/**
 * A finished recording opens as a new, unsaved project (React {@code useInitialEditorSource}).
 * Before this the app refused any non-{@code .gravaai} file ("recording import is P2.8"), so the
 * recorder's stop, the dashboard's raw recording cards and agent {@code project new} opened
 * nothing.
 */
class EditorSessionRecordingTest {

	@Test
	@DisplayName("a real recording starts as one full-length clip (useTimelineProjection)")
	void realRecordingStartsAsOneFullLengthClip() throws Exception {
		Path video = Path.of("/home/jmarceno/Projects/gravaai/java/parity/fixtures/media/h264-1080p60.mp4");
		assertTrue(Files.isRegularFile(video), "parity fixture is committed");
		try (EditorSession session = EditorSession.openRecording(video, null, 0)) {
			var clips = session.store().state().clipRegions();
			assertEquals(1, clips.size(), "one clip spanning the recording");
			assertEquals(0, clips.get(0).startMs());
			assertEquals(2000, clips.get(0).endMs(), 20);
			assertFalse(session.store().state().webcam().enabled(), "no webcam without a session track");
		}
	}

	@Test
	@DisplayName("a clip edit in the store reaches the playback engine")
	void clipEditsReachThePlaybackEngine() throws Exception {
		// The preview kept the clips it was opened with: cut or sped-up footage played unchanged.
		Path video = Path.of("/home/jmarceno/Projects/gravaai/java/parity/fixtures/media/h264-1080p60.mp4");
		assertTrue(Files.isRegularFile(video), "parity fixture is committed");
		try (EditorSession session = EditorSession.openRecording(video, null, 0)) {
			session.attachMedia();
			assertEquals(2.0, session.engine().status().durationSeconds(), 0.05);
			String clipId = session.store().state().clipRegions().get(0).id();
			session.store().apply(new dev.gravaai.core.editor.ClipCommands.Select(clipId));
			session.store().apply(new dev.gravaai.core.editor.ClipCommands.ChangeSpeed(2));
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
			while (Math.abs(session.engine().status().durationSeconds() - 1.0) > 0.05
					&& System.nanoTime() < deadline) {
				Thread.sleep(50);
			}
			assertEquals(1.0, session.engine().status().durationSeconds(), 0.05,
					"a 2x clip plays the 2 s recording in 1 s");
		}
	}

	@Test
	@DisplayName("a recording opens unsaved, linked to its webcam, and Save As writes the project")
	void recordingOpensAsAnUnsavedProject(@TempDir Path dir) throws Exception {
		Path video = dir.resolve("recording-1793184468126.mp4");
		Path webcam = dir.resolve("recording-1793184468244-webcam.mkv");
		Files.write(video, new byte[] {0});
		Files.write(webcam, new byte[] {0});

		try (EditorSession session = EditorSession.openRecording(video, webcam.toString(), 672)) {
			assertTrue(session.isUnsaved());
			assertEquals("recording-1793184468126", session.displayName());
			assertEquals(video.toString(), session.videoPath());
			assertEquals(webcam, session.webcamPath(), "the session manifest's webcam is linked");
			assertTrue(session.store().state().webcam().enabled());
			assertEquals(672, session.store().state().webcam().timeOffsetMs());
			assertThrows(IllegalStateException.class, session::save,
					"an unsaved project has no file to save into");

			Path target = dir.resolve("My recording.gravaai");
			session.saveAs(target);
			assertFalse(session.isUnsaved());
			assertEquals("My recording", session.displayName());
			ProjectDocument saved = ProjectDocument.load(target);
			assertEquals(video.toString(), saved.videoPath());
			assertEquals(webcam.toString(), saved.editor().webcam().sourcePath());
		}
	}
}
