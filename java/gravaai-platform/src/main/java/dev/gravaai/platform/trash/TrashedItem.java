package dev.gravaai.platform.trash;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * One entry of a freedesktop trash directory.
 *
 * @param trashDirectory the trash root ({@code files/} and {@code info/} live below it)
 * @param file           the payload inside {@code files/}
 * @param infoFile       the {@code .trashinfo} sidecar inside {@code info/}
 * @param originalPath   where the file was when it was trashed
 * @param deletionDate   the {@code DeletionDate} from the sidecar (second precision)
 */
public record TrashedItem(Path trashDirectory, Path file, Path infoFile, Path originalPath,
		LocalDateTime deletionDate) {

	/** File name inside the trash {@code files/} directory. */
	public String name() {
		return file.getFileName().toString();
	}
}
