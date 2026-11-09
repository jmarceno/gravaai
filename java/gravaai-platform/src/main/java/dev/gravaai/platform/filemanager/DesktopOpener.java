package dev.gravaai.platform.filemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import dev.gravaai.platform.process.ProcessRunner;

/**
 * Opens a file URL or a local path with the desktop's default application: {@code xdg-open}.
 *
 * <p>{@code FileManager} and {@code Portal} each had a private copy of this call, with the same
 * timeout, the same logging and the same non-fatal semantics. It lives in one place now so a change
 * to the fallback (a different opener, a stricter timeout, a portal-only file) is one edit.
 *
 * <p>Failure is always non-fatal: opening a URL is a convenience the user can repeat, and an
 * exception here would abort the caller's real work.
 */
public final class DesktopOpener {

	/** {@code xdg-open} may block while the desktop launches the handler. */
	static final Duration XDG_OPEN_TIMEOUT = Duration.ofSeconds(10);

	private static final System.Logger LOG = System.getLogger("gravaai.platform.filemanager");

	private DesktopOpener() {}

	/**
	 * Opens a target (a URL, a {@code mailto:}, an absolute path) with the default handler.
	 *
	 * @param target what {@code xdg-open} should open
	 * @return true when the opener reported success
	 */
	public static boolean open(String target) {
		try {
			var result = ProcessRunner.of("xdg-open", target).timeout(XDG_OPEN_TIMEOUT).run();
			if (!result.succeeded()) {
				LOG.log(System.Logger.Level.WARNING,
						"xdg-open " + target + " failed: " + result.stderr().strip());
				return false;
			}
			return true;
		} catch (IOException | InterruptedException error) {
			if (error instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOG.log(System.Logger.Level.WARNING, "xdg-open " + target + " failed: " + error);
			return false;
		}
	}

	/**
	 * Opens an existing path with the default handler.
	 *
	 * @return true when the opener reported success; false also when the path does not exist
	 */
	public static boolean openExisting(Path target) {
		Path absolute = target.toAbsolutePath().normalize();
		if (!Files.exists(absolute)) {
			LOG.log(System.Logger.Level.WARNING, "cannot open a missing path: " + absolute);
			return false;
		}
		return open(absolute.toString());
	}
}
