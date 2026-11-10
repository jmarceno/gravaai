package dev.gravaai.media.superres;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the pinned super-resolution model for tests.
 *
 * <p>The model is not bundled in the app or the dist payload (the app downloads it on first use),
 * but a small copy is committed under {@code java/native/models/superres/} so the engine and
 * compositor tests run against the exact bytes users get. Surefire may run from {@code java/} or
 * from the module directory, so the path is walked up from the working directory; an explicit
 * {@code -Dgravaai.superres.model=<file>} always wins.</p>
 */
public final class SuperResolutionTestModel {

	private static final String RELATIVE = "native/models/superres/realesr-general-x4v3.onnx";

	private SuperResolutionTestModel() {}

	/**
	 * The model file, or a path that does not exist when it cannot be found (callers use
	 * {@link org.junit.jupiter.api.Assumptions} so a checkout without it skips instead of fails).
	 */
	public static Path resolve() {
		String configured = System.getProperty("gravaai.superres.model");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		Path directory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
		for (Path current = directory; current != null; current = current.getParent()) {
			Path candidate = current.resolve(RELATIVE);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return directory.resolve(RELATIVE);
	}
}
