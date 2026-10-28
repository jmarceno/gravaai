package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;

/** End-to-end {@link ExportRunner} tests: temp file, publish, caption sidecars, cancel cleanup. */
class ExportRunnerTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("publishes an MP4 and writes the caption sidecars next to it")
	void publishesWithSidecars() throws Exception {
		Path destination = tempDir.resolve("exports").resolve("clip.mp4");
		AtomicReference<Path> revealed = new AtomicReference<>();
		CaptionSidecars.Payload captions = new CaptionSidecars.Payload(CaptionSidecars.Format.BOTH,
				List.of(new CaptionSidecars.Cue(0, 500, "hello")));
		ExportRunner.Spec spec = new ExportRunner.Spec(ExportFormat.MP4, 160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 0.5, false, destination, true, false, captions,
				ExportAudioPlan.NONE);
		ExportRunner.Outcome outcome = ExportRunner.run(spec,
				new SyntheticFrameProvider(160, 90, 24), ExportProgressListener.NONE,
				ExportCancellation.NONE, new ExportPublisher(revealed::set));
		assertEquals(destination.toAbsolutePath(), outcome.path());
		assertTrue(Files.isRegularFile(destination));
		assertEquals(12, outcome.frames());
		assertTrue(outcome.captionSidecars().wroteAny());
		assertTrue(Files.isRegularFile(tempDir.resolve("exports").resolve("clip.srt")));
		assertTrue(Files.isRegularFile(tempDir.resolve("exports").resolve("clip.vtt")));
		assertEquals(destination.toAbsolutePath(), revealed.get());
		assertFalse(hasTempFiles(tempDir.resolve("exports")));
	}

	@Test
	@DisplayName("cancelling an export leaves no destination file and no temp files behind")
	void cancelCleansUp() throws Exception {
		Path destination = tempDir.resolve("exports").resolve("cancelled.mp4");
		ExportRunner.Spec spec = new ExportRunner.Spec(ExportFormat.MP4, 160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 2.0, false, destination, false, false, null,
				ExportAudioPlan.NONE);
		AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
		ExportFrameProvider slowProvider = new ExportFrameProvider() {
			@Override
			public int width() {
				return 160;
			}

			@Override
			public int height() {
				return 90;
			}

			@Override
			public int frameRate() {
				return 24;
			}

			@Override
			public void renderFrame(long frameIndex, double timeSeconds, java.nio.ByteBuffer rgba)
					throws Exception {
				Thread.sleep(20);
				if (frameIndex >= 3) {
					cancelled.set(true);
				}
				new SyntheticFrameProvider(160, 90, 24).renderFrame(frameIndex, timeSeconds, rgba);
			}
		};
		assertThrows(ExportCancelledException.class, () -> ExportRunner.run(spec, slowProvider,
				ExportProgressListener.NONE, cancelled::get, new ExportPublisher(null)));
		assertFalse(Files.exists(destination));
		assertFalse(hasTempFiles(destination.getParent()));
	}

	private static boolean hasTempFiles(Path directory) throws Exception {
		if (directory == null || !Files.isDirectory(directory)) {
			return false;
		}
		try (Stream<Path> files = Files.list(directory)) {
			return files.anyMatch(file -> file.getFileName().toString()
					.startsWith(".gravaai-export-"));
		}
	}
}
