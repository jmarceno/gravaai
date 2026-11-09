package dev.gravaai.core.os;

import java.util.Locale;
import java.util.Map;

/**
 * The operating system this process runs on.
 *
 * <p>Gravaai targets Linux only, so {@link #LINUX} is the expected answer everywhere. The other
 * constants exist because a handful of ported decisions still branch on the host OS — the
 * macOS shortcut modifier, the corner-radius default, the directory-fsync guard — and those
 * branches used to read {@code System.getProperty("os.name")} independently, each with its own
 * {@code contains} test and its own spelling of "is this a Mac".
 *
 * <p>Reading the property in one place means one answer, and a single place to delete when the
 * last non-Linux branch goes. Like {@link dev.gravaai.platform.compositor.SessionType}, this is a
 * classifier, not a capability probe: callers must still check whether the thing they want exists.
 */
public enum HostOs {

	/** Linux: the only supported host. */
	LINUX,

	/** macOS: kept only for ported parity decisions. */
	MACOS,

	/** Windows: kept only for ported parity decisions. */
	WINDOWS,

	/** A host this code has never seen; treated like Linux by every caller that has a fallback. */
	OTHER;

	/** The OS of this process. */
	public static HostOs current() {
		return detect(System.getProperty("os.name", ""));
	}

	/**
	 * Classifies an {@code os.name} value.
	 *
	 * @param osName the Java {@code os.name} property (may be null or blank)
	 */
	public static HostOs detect(String osName) {
		if (osName == null) {
			return OTHER;
		}
		String normalized = osName.toLowerCase(Locale.ROOT);
		if (normalized.contains("linux")) {
			return LINUX;
		}
		if (normalized.contains("mac") || normalized.contains("darwin")) {
			return MACOS;
		}
		if (normalized.contains("windows")) {
			return WINDOWS;
		}
		return OTHER;
	}

	/** True on macOS, where the primary shortcut modifier is Cmd rather than Ctrl. */
	public boolean isMac() {
		return this == MACOS;
	}

	/** True on Windows, where directory handles cannot be fsynced. */
	public boolean isWindows() {
		return this == WINDOWS;
	}
}
