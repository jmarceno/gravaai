package dev.gravaai.platform.filemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import dev.gravaai.platform.dbus.SessionBus;
import dev.gravaai.platform.process.ProcessRunner;

/**
 * "Reveal in folder" / "open folder" through {@code org.freedesktop.FileManager1} (plan §5.5),
 * the replacement for Electron's {@code shell.showItemInFolder}/{@code shell.openPath}.
 *
 * <p>When the desktop has no FileManager1 service, the containing folder is opened with
 * {@code xdg-open} instead. Calls may block on the bus briefly; use a service executor.
 */
public final class FileManager {

	public static final String SERVICE = "org.freedesktop.FileManager1";
	public static final String PATH = "/org/freedesktop/FileManager1";

	private static final System.Logger LOG = System.getLogger("gravaai.platform.filemanager");
	private static final Duration XDG_OPEN_TIMEOUT = Duration.ofSeconds(10);

	/** The subset of {@code org.freedesktop.FileManager1} the app uses. */
	@DBusInterfaceName("org.freedesktop.FileManager1")
	public interface FileManagerInterface extends DBusInterface {
		void ShowItems(List<String> uris, String startupId);

		void ShowFolders(List<String> uris, String startupId);
	}

	private final SessionBus bus;
	private final FileManagerInterface fileManager;

	public FileManager(SessionBus bus) throws DBusException {
		this.bus = bus;
		this.fileManager = bus.connection().getRemoteObject(SERVICE, PATH, FileManagerInterface.class, false);
	}

	public static boolean isAvailable(SessionBus bus) {
		return bus.isServiceRunning(SERVICE);
	}

	/** Selects {@code file} in the desktop file manager, falling back to opening its folder. */
	public void reveal(Path file) {
		Path absolute = file.toAbsolutePath().normalize();
		if (isAvailable(bus)) {
			try {
				fileManager.ShowItems(List.of(toFileUri(absolute)), "");
				return;
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "FileManager1.ShowItems failed for " + absolute + ": " + error);
			}
		}
		Path parent = absolute.getParent();
		xdgOpen(parent == null ? absolute : parent);
	}

	/** Opens a folder in the desktop file manager, falling back to {@code xdg-open}. */
	public void openFolder(Path folder) {
		Path absolute = folder.toAbsolutePath().normalize();
		if (isAvailable(bus)) {
			try {
				fileManager.ShowFolders(List.of(toFileUri(absolute)), "");
				return;
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "FileManager1.ShowFolders failed for " + absolute + ": " + error);
			}
		}
		xdgOpen(absolute);
	}

	/** {@code file://} URI of a path, percent-encoded like the FileManager1 interface expects. */
	static String toFileUri(Path path) {
		return path.toAbsolutePath().normalize().toUri().toString();
	}

	private static void xdgOpen(Path target) {
		if (!Files.exists(target)) {
			LOG.log(System.Logger.Level.WARNING, "cannot open a missing path: " + target);
			return;
		}
		try {
			var result = ProcessRunner.of("xdg-open", target.toString()).timeout(XDG_OPEN_TIMEOUT).run();
			if (!result.succeeded()) {
				LOG.log(System.Logger.Level.WARNING, "xdg-open " + target + " failed: " + result.stderr().strip());
			}
		} catch (IOException | InterruptedException error) {
			if (error instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOG.log(System.Logger.Level.WARNING, "xdg-open " + target + " failed: " + error);
		}
	}
}
