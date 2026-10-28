package dev.gravaai.platform.trash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrashTest {

	@TempDir
	Path tempDir;

	private Path dataHome;
	private Path home;
	private Trash trash;

	@BeforeEach
	void setUp() throws IOException {
		dataHome = tempDir.resolve("data");
		home = Files.createDirectories(tempDir.resolve("home"));
		trash = new Trash(dataHome, home);
	}

	@Test
	@DisplayName("trashes a file into the home trash and lists it")
	void trashAndList() throws IOException {
		Path file = writeFile("note.txt", "hello");

		TrashedItem item = trash.trash(file);

		assertFalse(Files.exists(file));
		assertEquals(trash.homeTrashDirectory(), item.trashDirectory());
		assertEquals("note.txt", item.name());
		assertTrue(Files.isRegularFile(item.file()));
		assertEquals("hello", Files.readString(item.file()));
		String info = Files.readString(item.infoFile());
		assertTrue(info.startsWith("[Trash Info]\n"), info);
		assertTrue(info.contains("Path=" + Trash.encodePath(file)), info);
		assertTrue(info.contains("DeletionDate="), info);
		assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(item.infoFile())));

		List<TrashedItem> items = trash.list();
		assertEquals(1, items.size());
		assertEquals(file, items.getFirst().originalPath());
		assertEquals(item.deletionDate(), items.getFirst().deletionDate());
		assertEquals(item.file(), items.getFirst().file());
	}

	@Test
	@DisplayName("percent-encodes paths with spaces and non-ASCII characters")
	void encodesPaths() {
		assertEquals("/tmp/a%20b/c%2Bd.txt", Trash.encodePath(Path.of("/tmp/a b/c+d.txt")));
		assertEquals(Path.of("/tmp/a b/c+d.txt"), Trash.decodePath("/tmp/a%20b/c%2Bd.txt"));
		assertEquals(Path.of("/tmp/100%"), Trash.decodePath("/tmp/100%25"));
		assertEquals(Path.of("/tmp/héllo"), Trash.decodePath(Trash.encodePath(Path.of("/tmp/héllo"))));
	}

	@Test
	@DisplayName("keeps non-ASCII file names readable in the metadata")
	void trashesUnicodeFileName() throws IOException {
		Path file = writeFile("héllo wörld.txt", "x");

		TrashedItem item = trash.trash(file);

		String info = Files.readString(item.infoFile());
		assertTrue(info.contains("%20"), info);
		assertFalse(info.contains("héllo"), info);
		assertEquals(file, trash.list().getFirst().originalPath());
	}

	@Test
	@DisplayName("recovers a trashed file to its original location")
	void recoverRestoresFile() throws IOException {
		Path file = writeFile("recover.txt", "data");
		TrashedItem item = trash.trash(file);

		trash.recover(item);

		assertEquals("data", Files.readString(file));
		assertTrue(trash.list().isEmpty());
		assertFalse(Files.exists(item.file()));
	}

	@Test
	@DisplayName("recover fails when the original path exists again")
	void recoverFailsWhenTargetExists() throws IOException {
		Path file = writeFile("again.txt", "old");
		TrashedItem item = trash.trash(file);
		Files.writeString(file, "new");

		assertThrows(FileAlreadyExistsException.class, () -> trash.recover(item));
		assertEquals("new", Files.readString(file));
	}

	@Test
	@DisplayName("empty removes every entry without restoring")
	void emptyRemovesEverything() throws IOException {
		Path first = writeFile("a.txt", "a");
		Path second = writeFile("b.txt", "b");
		trash.trash(first);
		trash.trash(second);

		trash.empty();

		assertTrue(trash.list().isEmpty());
		assertFalse(Files.exists(first));
		assertFalse(Files.exists(second));
		assertTrue(isEmptyDirectory(trash.homeTrashDirectory().resolve("files")));
		assertTrue(isEmptyDirectory(trash.homeTrashDirectory().resolve("info")));
	}

	@Test
	@DisplayName("appends a suffix when a file name is already in the trash")
	void resolvesNameCollisions() throws IOException {
		Path first = writeFile("same.txt", "one");
		trash.trash(first);
		Path second = tempDir.resolve("originals/second").resolve("same.txt");
		Files.createDirectories(second.getParent());
		Files.writeString(second, "two");
		trash.trash(second);

		Set<String> names = new HashSet<>();
		for (TrashedItem item : trash.list()) {
			names.add(item.name());
		}
		assertEquals(Set.of("same.txt", "same.txt.2"), names);
	}

	@Test
	@DisplayName("ignores trash info whose payload is missing")
	void ignoresMissingPayload() throws IOException {
		Path file = writeFile("gone.txt", "x");
		TrashedItem item = trash.trash(file);
		Files.delete(item.file());

		assertTrue(trash.list().isEmpty());
	}

	@Test
	@DisplayName("ignores malformed trash info files")
	void ignoresMalformedInfo() throws IOException {
		Path infoDirectory = Files.createDirectories(trash.homeTrashDirectory().resolve("info"));
		Files.writeString(infoDirectory.resolve("broken.trashinfo"), "not a trash info file");

		assertTrue(trash.list().isEmpty());
	}

	@Test
	@DisplayName("rejects a path that does not exist")
	void rejectsMissingPath() {
		assertThrows(NoSuchFileException.class, () -> trash.trash(tempDir.resolve("nope.txt")));
	}

	@Test
	@DisplayName("refuses to trash the trash directory or its ancestors")
	void refusesToTrashTrashDirectory() throws IOException {
		Files.createDirectories(trash.homeTrashDirectory());

		assertThrows(IllegalArgumentException.class, () -> trash.trash(tempDir));
		assertThrows(IllegalArgumentException.class, () -> trash.trash(trash.homeTrashDirectory()));
	}

	@Test
	@DisplayName("uses a per-filesystem trash on another file store")
	void usesPerFilesystemTrash() throws IOException {
		Path otherDirectory = findOtherFilesystemDirectory();
		Assumptions.assumeTrue(otherDirectory != null, "no second filesystem is available");
		Path file = Files.createTempFile(otherDirectory, "gravaai-trash-", ".txt");
		Files.writeString(file, "cross-device");
		TrashedItem item = null;
		try {
			item = trash.trash(file);

			assertFalse(Files.exists(file));
			assertNotEquals(trash.homeTrashDirectory(), item.trashDirectory());
			assertTrue(Files.isRegularFile(item.file()));
			String trashDirectoryName = item.trashDirectory().getFileName().toString();
			assertTrue(trashDirectoryName.startsWith(".Trash"), trashDirectoryName);
			assertTrue(trash.list().stream().anyMatch(entry -> entry.originalPath().equals(file)));

			trash.recover(item);
			assertEquals("cross-device", Files.readString(file));
			item = null;
		} finally {
			if (item != null) {
				deleteRecursively(item.file());
				Files.deleteIfExists(item.infoFile());
			}
			Files.deleteIfExists(file);
			removeEmptyTrashDirectories(otherDirectory);
		}
	}

	private Path writeFile(String name, String content) throws IOException {
		Path file = tempDir.resolve("originals").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file;
	}

	private static boolean isEmptyDirectory(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) {
			return true;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
			return !stream.iterator().hasNext();
		}
	}

	private Path findOtherFilesystemDirectory() throws IOException {
		Long homeDevice = Trash.deviceId(dataHome);
		for (Path candidate : List.of(Path.of("/dev/shm"), Path.of(System.getProperty("user.home", "/")),
				Path.of("/"))) {
			if (!Files.isDirectory(candidate) || !Files.isWritable(candidate)) {
				continue;
			}
			Path probe;
			try {
				probe = Files.createTempFile(candidate, "gravaai-trash-probe-", ".tmp");
			} catch (IOException error) {
				continue;
			}
			try {
				Long device = Trash.deviceId(probe);
				if (device != null && homeDevice != null && !device.equals(homeDevice)) {
					return candidate;
				}
			} finally {
				Files.deleteIfExists(probe);
			}
		}
		return null;
	}

	/** Best-effort cleanup of the empty per-filesystem trash directories created by the test. */
	private static void removeEmptyTrashDirectories(Path root) {
		String uid = Trash.deviceId(Path.of(System.getProperty("user.home", "/"))) == null ? null : currentUid();
		if (uid == null) {
			return;
		}
		try {
			Files.deleteIfExists(root.resolve(".Trash-" + uid));
			Files.deleteIfExists(root.resolve(".Trash").resolve(uid));
			Files.deleteIfExists(root.resolve(".Trash"));
		} catch (IOException ignored) {
			// directories are not empty; nothing to clean up
		}
	}

	private static String currentUid() {
		try {
			Object value = Files.getAttribute(Path.of(System.getProperty("user.home", "/")), "unix:uid");
			return value instanceof Number number ? String.valueOf(number.longValue()) : null;
		} catch (IOException | UnsupportedOperationException error) {
			return null;
		}
	}

	private static void deleteRecursively(Path path) {
		if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
				try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
					for (Path child : children) {
						deleteRecursively(child);
					}
				}
			}
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// cleanup only
		}
	}
}