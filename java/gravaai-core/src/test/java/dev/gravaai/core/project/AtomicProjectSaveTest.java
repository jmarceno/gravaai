package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port of {@code electron/ipc/project/atomicSave.test.ts} (plan §5.2). */
class AtomicProjectSaveTest {

	@TempDir
	Path tempDir;

	private Path projectPath;

	@BeforeEach
	void setUp() {
		projectPath = tempDir.resolve("demo.gravaai");
	}

	@Test
	@DisplayName("commits a complete new project without creating a backup")
	void commitsNewProjectWithoutBackup() throws IOException {
		write("{\"version\":1}");

		assertEquals("{\"version\":1}", Files.readString(projectPath, StandardCharsets.UTF_8));
		assertFalse(Files.exists(AtomicProjectSave.getProjectBackupPath(projectPath)));
		assertNoTemporaryArtifacts();
	}

	@Test
	@DisplayName("removes a stale backup when the target has no previous generation")
	void removesStaleBackup() throws IOException {
		Files.writeString(AtomicProjectSave.getProjectBackupPath(projectPath), "stale-project");

		write("{\"version\":1}");

		assertFalse(Files.exists(AtomicProjectSave.getProjectBackupPath(projectPath)));
	}

	@Test
	@DisplayName("preserves the previous complete generation before replacement")
	void preservesPreviousGeneration() throws IOException {
		write("{\"version\":1,\"name\":\"old\"}");
		write("{\"version\":1,\"name\":\"new\"}");

		assertEquals("{\"version\":1,\"name\":\"new\"}", Files.readString(projectPath, StandardCharsets.UTF_8));
		assertEquals("{\"version\":1,\"name\":\"old\"}",
				Files.readString(AtomicProjectSave.getProjectBackupPath(projectPath), StandardCharsets.UTF_8));
		assertNoTemporaryArtifacts();
	}

	@Test
	@DisplayName("keeps the active generation unchanged when backup commit fails")
	void keepsActiveGenerationWhenBackupFails() throws IOException {
		write("{\"version\":1,\"name\":\"old\"}");
		Files.createDirectory(AtomicProjectSave.getProjectBackupPath(projectPath));

		assertThrows(CompletionException.class, () -> write("{\"version\":1,\"name\":\"new\"}"));

		assertEquals("{\"version\":1,\"name\":\"old\"}", Files.readString(projectPath, StandardCharsets.UTF_8));
		assertNoTemporaryArtifacts();

		deleteRecursively(AtomicProjectSave.getProjectBackupPath(projectPath));
		write("{\"version\":1,\"name\":\"retry\"}");
		assertEquals("{\"version\":1,\"name\":\"retry\"}", Files.readString(projectPath, StandardCharsets.UTF_8));
		assertEquals("{\"version\":1,\"name\":\"old\"}",
				Files.readString(AtomicProjectSave.getProjectBackupPath(projectPath), StandardCharsets.UTF_8));
		assertNoTemporaryArtifacts();
	}

	@Test
	@DisplayName("serializes overlapping writes to the same project")
	void serializesOverlappingWrites() throws IOException {
		write("{\"revision\":1}");

		// Each call enters the queue synchronously before returning, preserving invocation order.
		CompletableFuture<Void> second =
				AtomicProjectSave.writeProjectFileAtomically(projectPath, "{\"revision\":2}");
		CompletableFuture<Void> third =
				AtomicProjectSave.writeProjectFileAtomically(projectPath, "{\"revision\":3}");
		CompletableFuture.allOf(second, third).join();

		assertEquals("{\"revision\":3}", Files.readString(projectPath, StandardCharsets.UTF_8));
		assertEquals("{\"revision\":2}",
				Files.readString(AtomicProjectSave.getProjectBackupPath(projectPath), StandardCharsets.UTF_8));
		assertNoTemporaryArtifacts();
	}

	@Test
	@DisplayName("preserves exact project permission bits despite the process umask")
	void preservesPermissionBits() throws IOException {
		FileStore store = Files.getFileStore(tempDir);
		assumeTrue(File.separatorChar != '\\');
		assumeTrue(store.supportsFileAttributeView(PosixFileAttributeView.class));
		Files.writeString(projectPath, "{\"revision\":1}", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(projectPath, PosixFilePermissions.fromString("rw-rw-rw-"));

		write("{\"revision\":2}");

		assertEquals("rw-rw-rw-", PosixFilePermissions.toString(Files.getPosixFilePermissions(projectPath)));
	}

	@Test
	@DisplayName("places the backup next to the project file")
	void placesBackupNextToProject() {
		assertEquals(Path.of(projectPath + ".bak"), AtomicProjectSave.getProjectBackupPath(projectPath));
	}

	private void write(String contents) {
		AtomicProjectSave.writeProjectFileAtomically(projectPath, contents).join();
	}

	private void assertNoTemporaryArtifacts() throws IOException {
		try (var entries = Files.list(tempDir)) {
			List<Path> temporary = entries
					.filter(entry -> entry.getFileName().toString().endsWith(".tmp"))
					.toList();
			assertTrue(temporary.isEmpty(), () -> "temporary artifacts left behind: " + temporary);
		}
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (var entries = Files.list(path)) {
			for (Path entry : entries.toList()) {
				deleteRecursively(entry);
			}
		}
		Files.deleteIfExists(path);
	}
}
