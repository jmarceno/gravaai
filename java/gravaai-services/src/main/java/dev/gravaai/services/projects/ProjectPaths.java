package dev.gravaai.services.projects;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Path helpers from {@code electron/ipc/utils.ts} and {@code src/lib/localMediaUrl.ts} used by the
 * project library.
 */
final class ProjectPaths {

	private static final String MEDIA_URL_PATH = "/video";

	private ProjectPaths() {
	}

	/**
	 * Port of {@code normalizeVideoSourcePath}: trims, turns the old loopback media-server URL back
	 * into the file path it served, unwraps {@code file://} URLs, and otherwise keeps the value.
	 *
	 * @param videoPath the raw stored path, or null when the project omits it
	 */
	static @Nullable String normalizeVideoSourcePath(@Nullable String videoPath) {
		if (videoPath == null) {
			return null;
		}

		String trimmed = videoPath.trim();
		if (trimmed.isEmpty()) {
			return null;
		}

		String mediaServerPath = getLocalMediaServerPath(trimmed);
		if (mediaServerPath != null) {
			return mediaServerPath;
		}

		if (trimmed.regionMatches(true, 0, "file://", 0, "file://".length())) {
			try {
				return Path.of(URI.create(trimmed)).toString();
			} catch (RuntimeException error) {
				// Fall through and keep the best-effort string path, like the TS code.
			}
		}

		return trimmed;
	}

	/** Port of {@code getLocalMediaServerPath}: only the loopback media-server video URL yields a path. */
	static @Nullable String getLocalMediaServerPath(String resource) {
		try {
			URI uri = URI.create(resource);
			String scheme = uri.getScheme();
			if (scheme == null
					|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
				return null;
			}
			String host = uri.getHost();
			if (host == null || !(host.equals("127.0.0.1") || host.equals("localhost"))) {
				return null;
			}
			if (!MEDIA_URL_PATH.equals(uri.getPath())) {
				return null;
			}

			String rawQuery = uri.getRawQuery();
			if (rawQuery == null) {
				return null;
			}
			String path = null;
			for (String pair : rawQuery.split("&")) {
				int equalsIndex = pair.indexOf('=');
				String key = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
				if (!"path".equals(urlDecode(key))) {
					continue;
				}
				path = equalsIndex >= 0 ? urlDecode(pair.substring(equalsIndex + 1)) : "";
			}
			return path != null && isAbsoluteMediaPath(path) ? path : null;
		} catch (RuntimeException error) {
			return null;
		}
	}

	/** {@code path.extname}: the last dot in the last path segment, ignored at position 0. */
	static String extname(String filePath) {
		int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
		String baseName = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
		int lastDot = baseName.lastIndexOf('.');
		return lastDot <= 0 ? "" : baseName.substring(lastDot);
	}

	static boolean exists(String filePath) {
		try {
			return Files.exists(Path.of(filePath));
		} catch (InvalidPathException error) {
			return false;
		}
	}

	/** JS {@code /^\//} or a Windows drive path or UNC prefix. */
	private static boolean isAbsoluteMediaPath(String path) {
		if (path.startsWith("/") || path.startsWith("\\\\")) {
			return true;
		}
		return path.length() >= 3
				&& Character.isLetter(path.charAt(0))
				&& path.charAt(1) == ':'
				&& (path.charAt(2) == '/' || path.charAt(2) == '\\');
	}

	private static String urlDecode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}
