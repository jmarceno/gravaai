package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * File-system side of the automation actions (port of {@code electron/automations/fileActions.ts}).
 * Kept free of Electron/platform imports so it can be unit tested on its own.
 */
public final class FileActions {

	/** Port of {@code MAX_TARGET_ATTEMPTS}. */
	private static final int MAX_TARGET_ATTEMPTS = 1000;

	private FileActions() {}

	/** Port of {@code FileActionOptions}. */
	public record FileActionOptions(AutomationContext context, String folder,
			@Nullable String fileName, Path homedir) {}

	/** Port of {@code RunDetachedCommandOptions}. */
	public record RunDetachedCommandOptions(String program, List<String> args, Path cwd, Path homedir) {}

	/** Port of {@code expandHomeDirectory}: only a leading tilde is expanded. */
	public static String expandHomeDirectory(String target, Path homedir) {
		return AutomationText.expandHomeDirectory(target, homedir);
	}

	/** Port of {@code resolveFolderPath}: expands templates and home, and rejects relative folders. */
	public static Path resolveFolderPath(String folder, AutomationContext context, Path homedir) {
		String expanded = expandHomeDirectory(
				AutomationText.expandAutomationTemplate(folder, context).trim(), homedir);
		if (expanded.isEmpty()) {
			throw new IllegalArgumentException("No folder configured for this action.");
		}
		Path path = Path.of(expanded);
		if (!path.isAbsolute()) {
			throw new IllegalArgumentException("Folder must be an absolute path: " + expanded);
		}
		return path;
	}

	/** Port of {@code copyFileToFolder}: creates the folder and never overwrites. */
	public static String copyFileToFolder(FileActionOptions options) throws IOException {
		Path folder = resolveFolderPath(options.folder(), options.context(), options.homedir());
		Files.createDirectories(folder);
		String base = targetBaseName(options.fileName(), options.context());
		String extension = targetExtension(options.context());
		return copyWithAvailableName(Path.of(options.context().path()), folder, base, extension).toString();
	}

	/** Port of {@code moveFileToFolder}: creates the folder and never overwrites. */
	public static String moveFileToFolder(FileActionOptions options) throws IOException {
		Path folder = resolveFolderPath(options.folder(), options.context(), options.homedir());
		Files.createDirectories(folder);
		String base = targetBaseName(options.fileName(), options.context());
		String extension = targetExtension(options.context());
		return moveWithAvailableName(Path.of(options.context().path()), folder, base, extension).toString();
	}

	/**
	 * Port of {@code runDetachedCommand}: starts a program without a shell and returns once it has
	 * spawned (a missing program throws instead of vanishing).
	 */
	public static void runDetachedCommand(RunDetachedCommandOptions options) throws IOException {
		String program = expandHomeDirectory(options.program().trim(), options.homedir());
		if (program.isEmpty()) {
			throw new IllegalArgumentException("No program configured for this action.");
		}
		Path cwd = options.cwd() != null && Files.isDirectory(options.cwd()) ? options.cwd() : options.homedir();
		List<String> command = new ArrayList<>();
		command.add(program);
		command.addAll(options.args());
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(cwd.toFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
		builder.start();
	}

	/** @param fileName the optional target file-name template, or null to keep the source name */
	private static String targetBaseName(@Nullable String fileName, AutomationContext context) {
		String template = fileName != null && !fileName.trim().isEmpty() ? fileName : "{name}";
		return AutomationText.sanitizeFileName(AutomationText.expandAutomationTemplate(template, context));
	}

	private static String targetExtension(AutomationContext context) {
		String extension = AutomationText.splitAutomationPath(context.path()).ext();
		return extension.isEmpty() ? "" : "." + extension;
	}

	private static String candidateName(String base, String extension, int attempt) {
		return (attempt == 1 ? base : base + " (" + attempt + ")") + extension;
	}

	private static boolean pathExists(Path targetPath) {
		return Files.exists(targetPath);
	}

	private static Path copyWithAvailableName(Path sourcePath, Path folder, String base, String extension)
			throws IOException {
		for (int attempt = 1; attempt <= MAX_TARGET_ATTEMPTS; attempt += 1) {
			Path targetPath = folder.resolve(candidateName(base, extension, attempt));
			try {
				Files.copy(sourcePath, targetPath);
				return targetPath;
			} catch (FileAlreadyExistsException | DirectoryNotEmptyException error) {
				continue;
			}
		}
		throw new IOException("Could not find a free file name in the target folder.");
	}

	private static Path moveWithAvailableName(Path sourcePath, Path folder, String base, String extension)
			throws IOException {
		for (int attempt = 1; attempt <= MAX_TARGET_ATTEMPTS; attempt += 1) {
			Path targetPath = folder.resolve(candidateName(base, extension, attempt));
			if (pathExists(targetPath)) {
				continue;
			}
			try {
				Files.move(sourcePath, targetPath);
				return targetPath;
			} catch (FileAlreadyExistsException | DirectoryNotEmptyException error) {
				continue;
			} catch (FileSystemException error) {
				// Moving across devices falls back to copy + delete, like the TS EXDEV branch.
				try {
					Files.copy(sourcePath, targetPath);
				} catch (FileAlreadyExistsException | DirectoryNotEmptyException copyError) {
					continue;
				}
				Files.delete(sourcePath);
				return targetPath;
			}
		}
		throw new IOException("Could not find a free file name in the target folder.");
	}
}
