package dev.gravaai.platform.trash;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Freedesktop.org Trash specification implementation (plan §5.5): trash, list, recover and empty.
 *
 * <p>The task uses the spec directly instead of shelling out to {@code gio trash}, because the
 * library needs list/recover/empty as well and those are not exposed by {@code gio}. Both the home
 * trash ({@code $XDG_DATA_HOME/Trash}) and the per-filesystem trashes the spec defines
 * ({@code <top>/.Trash/<uid>} or {@code <top>/.Trash-<uid>}) are written and listed; {@link #empty()}
 * empties the home trash like {@code gio trash --empty}.
 *
 * <p>All operations are blocking file IO and are meant to run on a service executor.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class Trash {

	private static final System.Logger LOG = System.getLogger("gravaai.platform.trash");

	private static final String INFO_HEADER = "[Trash Info]";
	private static final String INFO_FILE_EXTENSION = ".trashinfo";
	private static final String DATE_PATTERN = "uuuu-MM-dd'T'HH:mm:ss";
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);
	private static final String MOUNTS_FILE = "/proc/self/mounts";

	private final Path dataHome;
	private final Path homeDirectory;
	private final Path homeTrashDirectory;

	/** Trash for the current user: {@code $XDG_DATA_HOME/Trash}, defaulting to the XDG spec default. */
	public static Trash defaultTrash() {
		Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
		String xdgDataHome = System.getenv("XDG_DATA_HOME");
		Path dataHome = xdgDataHome == null || xdgDataHome.isBlank()
				? home.resolve(".local/share")
				: Path.of(xdgDataHome);
		return new Trash(dataHome, home);
	}

	/**
	 * @param dataHome      the XDG data home (the home trash is {@code <dataHome>/Trash})
	 * @param homeDirectory the user's home directory, used to name per-filesystem trashes
	 */
	public Trash(Path dataHome, Path homeDirectory) {
		this.dataHome = dataHome.toAbsolutePath().normalize();
		this.homeDirectory = homeDirectory.toAbsolutePath().normalize();
		this.homeTrashDirectory = this.dataHome.resolve("Trash");
	}

	public Path homeTrashDirectory() {
		return homeTrashDirectory;
	}

	/** All trash entries, newest first. */
	public List<TrashedItem> list() throws IOException {
		List<TrashedItem> items = new ArrayList<>();
		for (Path trashDirectory : trashDirectories()) {
			Path infoDirectory = trashDirectory.resolve("info");
			if (!Files.isDirectory(infoDirectory)) {
				continue;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(infoDirectory, "*" + INFO_FILE_EXTENSION)) {
				for (Path infoFile : stream) {
					TrashedItem item = parse(infoFile);
					if (item != null) {
						items.add(item);
					}
				}
			}
		}
		items.sort(Comparator.comparing(TrashedItem::deletionDate).reversed()
				.thenComparing(item -> item.originalPath().toString()));
		return items;
	}

	/** Moves {@code path} to the trash and returns the entry that was created. */
	public TrashedItem trash(Path path) throws IOException {
		Path source = path.toAbsolutePath().normalize();
		if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
			throw new NoSuchFileException(source.toString());
		}
		if (source.getFileName() == null) {
			throw new IllegalArgumentException("cannot trash a filesystem root: " + source);
		}
		if (homeTrashDirectory.startsWith(source)) {
			throw new IllegalArgumentException("refusing to trash the trash directory itself: " + source);
		}

		Path trashDirectory = trashDirectoryFor(source);
		Path filesDirectory = trashDirectory.resolve("files");
		Path infoDirectory = trashDirectory.resolve("info");
		createPrivateDirectories(trashDirectory, filesDirectory, infoDirectory);

		LocalDateTime deletionDate = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
		String name = uniqueName(filesDirectory, infoDirectory, source.getFileName().toString());
		Path payload = filesDirectory.resolve(name);
		Path infoFile = infoDirectory.resolve(name + INFO_FILE_EXTENSION);

		String info = INFO_HEADER + "\n"
				+ "Path=" + encodePath(source) + "\n"
				+ "DeletionDate=" + DATE_FORMATTER.format(deletionDate) + "\n";
		Files.writeString(infoFile, info, StandardCharsets.UTF_8);
		setPermissions(infoFile, "rw-------");
		try {
			move(source, payload);
		} catch (IOException | RuntimeException error) {
			Files.deleteIfExists(infoFile);
			throw error;
		}
		return new TrashedItem(trashDirectory, payload, infoFile, source, deletionDate);
	}

	/** Moves a trashed entry back to its original path. */
	public void recover(TrashedItem item) throws IOException {
		Path target = item.originalPath();
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new FileAlreadyExistsException(target.toString());
		}
		if (!Files.exists(item.file(), LinkOption.NOFOLLOW_LINKS)) {
			throw new NoSuchFileException(item.file().toString());
		}
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		move(item.file(), target);
		Files.deleteIfExists(item.infoFile());
	}

	/** Removes every entry of the home trash without restoring anything. */
	public void empty() throws IOException {
		deleteContents(homeTrashDirectory.resolve("files"));
		deleteContents(homeTrashDirectory.resolve("info"));
	}

	private List<Path> trashDirectories() {
		LinkedHashSet<Path> directories = new LinkedHashSet<>();
		directories.add(homeTrashDirectory);
		String uid = unixUid();
		if (uid != null) {
			for (Path mountPoint : mountPoints()) {
				Path dotTrash = mountPoint.resolve(".Trash").resolve(uid);
				if (isDirectoryQuietly(dotTrash)) {
					directories.add(dotTrash);
				}
				Path dashTrash = mountPoint.resolve(".Trash-" + uid);
				if (isDirectoryQuietly(dashTrash)) {
					directories.add(dashTrash);
				}
			}
		}
		return List.copyOf(directories);
	}

	private Path trashDirectoryFor(Path source) throws IOException {
		Long homeDevice = deviceId(dataHome);
		Long sourceDevice = deviceId(source);
		if (homeDevice != null && homeDevice.equals(sourceDevice)) {
			return homeTrashDirectory;
		}
		String uid = unixUid();
		if (uid == null) {
			throw new IOException("cannot determine the user id for a per-filesystem trash of " + source);
		}
		Path topDirectory = topDirectoryOf(source);
		Path dotTrash = topDirectory.resolve(".Trash");
		if (Files.isDirectory(dotTrash)) {
			return dotTrash.resolve(uid);
		}
		return topDirectory.resolve(".Trash-" + uid);
	}

	private static void createPrivateDirectories(Path... directories) throws IOException {
		for (Path directory : directories) {
			Files.createDirectories(directory);
			setPermissions(directory, "rwx------");
		}
	}

	private static String uniqueName(Path filesDirectory, Path infoDirectory, String baseName) {
		String candidate = baseName;
		for (int suffix = 2; Files.exists(filesDirectory.resolve(candidate), LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(infoDirectory.resolve(candidate + INFO_FILE_EXTENSION)); suffix++) {
			candidate = baseName + "." + suffix;
		}
		return candidate;
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException error) {
			Files.move(source, target);
		}
	}

	private static void deleteContents(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) {
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
			for (Path child : stream) {
				deleteRecursively(child);
			}
		}
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
				for (Path child : stream) {
					deleteRecursively(child);
				}
			}
		}
		Files.deleteIfExists(path);
	}

	private static void setPermissions(Path path, String permissions) {
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
		} catch (IOException | UnsupportedOperationException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not set permissions on " + path + ": " + error);
		}
	}

	private static @Nullable boolean isDirectoryQuietly(Path path) {
		try {
			return Files.isDirectory(path);
		} catch (SecurityException error) {
			return false;
		}
	}

	static @Nullable Long deviceId(Path path, LinkOption... options) {
		try {
			Path existing = nearestExisting(path);
			Object value = Files.getAttribute(existing, "unix:dev", options);
			return value instanceof Number number ? number.longValue() : null;
		} catch (IOException | UnsupportedOperationException | SecurityException | IllegalArgumentException error) {
			return null;
		}
	}

	private static @Nullable Path nearestExisting(Path path) {
		Path current = path;
		while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
			current = current.getParent();
		}
		return current;
	}

	static @Nullable Path topDirectoryOf(Path path) {
		Long device = deviceId(path);
		Path top = path.toAbsolutePath().normalize();
		while (top.getParent() != null) {
			Long parentDevice = deviceId(top.getParent());
			if (device != null ? !device.equals(parentDevice) : parentDevice == null) {
				break;
			}
			top = top.getParent();
		}
		return top;
	}

	private static @Nullable String unixUid() {
		try {
			Object value = Files.getAttribute(Path.of(System.getProperty("user.home", ".")), "unix:uid");
			return value instanceof Number number ? String.valueOf(number.longValue()) : null;
		} catch (IOException | UnsupportedOperationException | SecurityException | IllegalArgumentException error) {
			return null;
		}
	}

	private static List<Path> mountPoints() {
		Path mounts = Path.of(MOUNTS_FILE);
		if (!Files.isReadable(mounts)) {
			return List.of();
		}
		List<Path> mountPoints = new ArrayList<>();
		try (var lines = Files.lines(mounts, StandardCharsets.UTF_8)) {
			for (String line : lines.toList()) {
				String[] fields = line.split(" ");
				if (fields.length < 2) {
					continue;
				}
				try {
					mountPoints.add(Path.of(unescapeMountPoint(fields[1])));
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.DEBUG, "skipping mount point " + fields[1] + ": " + error);
				}
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not read " + mounts + ": " + error);
		}
		return mountPoints;
	}

	/** Undoes the octal escapes (e.g. {@code \040} for a space) used in {@code /proc/self/mounts}. */
	static String unescapeMountPoint(String value) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '\\' && index + 3 < value.length()
					&& isOctal(value.charAt(index + 1)) && isOctal(value.charAt(index + 2))
					&& isOctal(value.charAt(index + 3))) {
				bytes.write((octalValue(value.charAt(index + 1)) << 6)
						| (octalValue(value.charAt(index + 2)) << 3)
						| octalValue(value.charAt(index + 3)));
				index += 3;
			} else {
				bytes.write(String.valueOf(character).getBytes(StandardCharsets.UTF_8), 0,
						String.valueOf(character).getBytes(StandardCharsets.UTF_8).length);
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	private static boolean isOctal(char character) {
		return character >= '0' && character <= '7';
	}

	private static int octalValue(char character) {
		return character - '0';
	}

	/** URL-encodes an absolute path the way the Trash specification (and gio) writes it. */
	static @Nullable String encodePath(Path path) {
		byte[] bytes = path.toString().getBytes(StandardCharsets.UTF_8);
		StringBuilder encoded = new StringBuilder(bytes.length + 16);
		for (byte value : bytes) {
			int character = value & 0xff;
			if (isUnreserved(character)) {
				encoded.append((char) character);
			} else {
				encoded.append('%');
				encoded.append(Character.toUpperCase(Character.forDigit(character >> 4, 16)));
				encoded.append(Character.toUpperCase(Character.forDigit(character & 0x0f, 16)));
			}
		}
		return encoded.toString();
	}

	private static @Nullable boolean isUnreserved(int character) {
		return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z')
				|| (character >= '0' && character <= '9') || character == '-' || character == '.'
				|| character == '_' || character == '~' || character == '/';
	}

	/** Decodes a {@code Path=} value from a trash info file. */
	static @Nullable Path decodePath(String encoded) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
		for (int index = 0; index < encoded.length(); index++) {
			char character = encoded.charAt(index);
			if (character == '%' && index + 2 < encoded.length()) {
				int high = Character.digit(encoded.charAt(index + 1), 16);
				int low = Character.digit(encoded.charAt(index + 2), 16);
				if (high >= 0 && low >= 0) {
					bytes.write((high << 4) | low);
					index += 2;
					continue;
				}
			}
			bytes.write(String.valueOf(character).getBytes(StandardCharsets.UTF_8), 0,
					String.valueOf(character).getBytes(StandardCharsets.UTF_8).length);
		}
		return Path.of(bytes.toString(StandardCharsets.UTF_8));
	}

	private @Nullable TrashedItem parse(Path infoFile) {
		try {
			List<String> lines = Files.readAllLines(infoFile, StandardCharsets.UTF_8);
			if (lines.isEmpty() || !INFO_HEADER.equals(lines.getFirst().trim())) {
				LOG.log(System.Logger.Level.DEBUG, "ignoring trash info without header: " + infoFile);
				return null;
			}
			Path originalPath = null;
			LocalDateTime deletionDate = null;
			for (String line : lines.subList(1, lines.size())) {
				int equals = line.indexOf('=');
				if (equals < 0) {
					continue;
				}
				String key = line.substring(0, equals).trim();
				String value = line.substring(equals + 1).trim();
				switch (key) {
					case "Path" -> originalPath = decodePath(value);
					case "DeletionDate" -> deletionDate = parseDeletionDate(value);
					default -> {
						// Unknown keys are ignored; newer spec versions may add fields.
					}
				}
			}
			if (originalPath == null || !originalPath.isAbsolute() || deletionDate == null) {
				LOG.log(System.Logger.Level.DEBUG, "ignoring incomplete trash info: " + infoFile);
				return null;
			}
			String fileName = infoFile.getFileName().toString();
			String name = fileName.substring(0, fileName.length() - INFO_FILE_EXTENSION.length());
			Path trashDirectory = infoFile.getParent().getParent();
			Path payload = trashDirectory.resolve("files").resolve(name);
			if (!Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
				LOG.log(System.Logger.Level.DEBUG, "ignoring trash info with missing payload: " + infoFile);
				return null;
			}
			return new TrashedItem(trashDirectory, payload, infoFile, originalPath, deletionDate);
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "could not read trash info " + infoFile + ": " + error);
			return null;
		}
	}

	private static @Nullable LocalDateTime parseDeletionDate(String value) {
		try {
			return LocalDateTime.parse(value, DATE_FORMATTER);
		} catch (DateTimeParseException error) {
			try {
				return LocalDateTime.parse(value).truncatedTo(ChronoUnit.SECONDS);
			} catch (DateTimeParseException ignored) {
				return null;
			}
		}
	}
}