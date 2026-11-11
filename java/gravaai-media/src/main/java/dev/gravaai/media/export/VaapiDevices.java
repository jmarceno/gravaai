package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The device nodes VA-API encodes through (card "GPU hardware encoding for export and recording").
 *
 * <p>Only presence is checked here. A render node that exists can still belong to a card without an
 * encoder, or one whose driver rejects the session; that is settled by the job itself, which falls
 * back to libx264 when the encoder exits early.
 */
final class VaapiDevices {

	private VaapiDevices() {}

	/** The first usable {@code /dev/dri/renderD*} node, or null when there is none. */
	static @Nullable Path firstRenderNode() {
		Path dri = Path.of("/dev/dri");
		if (!Files.isDirectory(dri)) {
			return null;
		}
		try (Stream<Path> nodes = Files.list(dri)) {
			Optional<Path> node = nodes
					.filter(path -> path.getFileName().toString().startsWith("renderD"))
					.filter(Files::exists)
					.sorted()
					.findFirst();
			return node.orElse(null);
		} catch (IOException error) {
			return null;
		}
	}

	/** True when there is a render node to try. */
	static boolean present() {
		return firstRenderNode() != null;
	}
}
