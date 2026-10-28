package dev.gravaai.media.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Loading the bundled FFmpeg libraries and the log bridge (plan §5.7). */
class FfmpegLibsTest {

	@Test
	@DisplayName("loads the bundled shared libraries in dependency order")
	void loadsLibrariesInDependencyOrder() {
		FfmpegLibs.ensureLoaded();
		Path directory = FfmpegLibs.libraryDir();
		assertNotNull(directory);
		for (String library : FfmpegLibs.LIBRARIES) {
			assertTrue(Files.isRegularFile(directory.resolve(library)),
					"missing " + library + " in " + directory);
		}
		assertTrue(FfmpegLibs.versionSummary().contains("avutil "));
		assertTrue(FfmpegLibs.versionSummary().contains("avcodec "));
	}

	@Test
	@DisplayName("loading the same directory twice is a no-op")
	void loadingTwiceIsANoOp() {
		FfmpegLibs.ensureLoaded();
		Path directory = FfmpegLibs.libraryDir();
		assertDoesNotThrow(() -> FfmpegLibs.load(directory));
	}

	@Test
	@DisplayName("loading a different directory is rejected")
	void loadingADifferentDirectoryIsRejected() {
		FfmpegLibs.ensureLoaded();
		Path other = Path.of("/tmp/not-the-loaded-ffmpeg");
		assertThrows(IllegalStateException.class, () -> FfmpegLibs.load(other));
	}

	@Test
	@DisplayName("av_log lines reach System.Logger with the message text")
	void avLogReachesSystemLogger() throws Exception {
		FfmpegLibs.ensureLoaded();
		Logger jul = Logger.getLogger("");
		java.util.List<LogRecord> records = new java.util.ArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		jul.addHandler(handler);
		try {
			Path garbage = Files.createTempFile("gravaai-not-media", ".mp4");
			Files.writeString(garbage, "this is not a media file");
			try {
				assertThrows(FfmpegException.class, () -> MediaProbe.probe(garbage));
			} finally {
				Files.deleteIfExists(garbage);
			}
		} finally {
			jul.removeHandler(handler);
		}
		assertTrue(records.stream().anyMatch(record ->
						record.getLevel().intValue() >= Level.WARNING.intValue()
								&& record.getMessage() != null),
				"no av_log line was bridged to java.util.logging: " + records);
	}
}
