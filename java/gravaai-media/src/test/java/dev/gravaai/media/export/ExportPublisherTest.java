package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ExportPublisher} save/reveal semantics (plan §5.10 save policy). */
class ExportPublisherTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("moves the temp file into place and reveals the published file")
	void publishesAndReveals() throws Exception {
		Path temp = tempDir.resolve(".gravaai-export-123.mp4");
		Files.writeString(temp, "mp4-bytes");
		Path destination = tempDir.resolve("out").resolve("clip.mp4");
		AtomicReference<Path> revealed = new AtomicReference<>();
		ExportPublisher publisher = new ExportPublisher(revealed::set);
		ExportPublisher.PublishResult result = publisher.publish(temp,
				new ExportPublisher.Destination(destination, true), false);
		assertEquals(destination.toAbsolutePath(), result.path());
		assertEquals(9, result.bytes());
		assertFalse(Files.exists(temp));
		assertEquals("mp4-bytes", Files.readString(destination));
		assertEquals(destination.toAbsolutePath(), revealed.get());
	}

	@Test
	@DisplayName("refuses to overwrite unless the overwrite flag is set")
	void overwritePolicy() throws Exception {
		Path destination = tempDir.resolve("clip.mp4");
		Files.writeString(destination, "existing");
		Path temp = tempDir.resolve(".gravaai-export-456.mp4");
		Files.writeString(temp, "new");
		ExportPublisher publisher = new ExportPublisher(null);
		assertThrows(ExportException.class, () -> publisher.publish(temp,
				new ExportPublisher.Destination(destination, false), false));
		assertEquals("existing", Files.readString(destination));
		ExportPublisher.PublishResult result = publisher.publish(temp,
				new ExportPublisher.Destination(destination, false), true);
		assertEquals(3, result.bytes());
		assertEquals("new", Files.readString(destination));
	}

	@Test
	@DisplayName("reports a missing temp file instead of publishing")
	void missingTempFile() {
		assertThrows(ExportException.class, () -> new ExportPublisher(null).publish(
				tempDir.resolve("missing.mp4"),
				new ExportPublisher.Destination(tempDir.resolve("out.mp4"), false), false));
	}
}
