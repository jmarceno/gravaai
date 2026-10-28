package dev.gravaai.services.projects;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * Identity of one project-file revision, used to discard thumbnails decoded from a stale file.
 *
 * <p>Port of the {@code Stats} comparison in {@code sameProjectRevision} in
 * {@code electron/ipc/project/manager.ts}: mtime, ctime, size, inode and device. Java exposes the
 * last two through {@link BasicFileAttributes#fileKey()}.
 */
public record ProjectRevision(FileTime modified, FileTime changed, long size, Object fileKey) {

	/** {@code fs.stat(projectPath)}: the current revision of a file. */
	public static ProjectRevision read(Path file) throws IOException {
		return of(file, Files.readAttributes(file, BasicFileAttributes.class));
	}

	/** Builds a revision from an attributes snapshot already read by the caller. */
	static ProjectRevision of(Path file, BasicFileAttributes attributes) {
		return new ProjectRevision(attributes.lastModifiedTime(), changedTime(file, attributes),
				attributes.size(), attributes.fileKey());
	}

	/** {@code ctimeMs} (not exposed by {@code BasicFileAttributes} on every platform). */
	private static FileTime changedTime(Path file, BasicFileAttributes attributes) {
		try {
			Object changed = Files.getAttribute(file, "unix:ctime");
			if (changed instanceof FileTime value) {
				return value;
			}
		} catch (IOException | RuntimeException ignored) {
			// Fall back to the creation time; the comparison only needs a stable value.
		}
		return attributes.creationTime();
	}
}
