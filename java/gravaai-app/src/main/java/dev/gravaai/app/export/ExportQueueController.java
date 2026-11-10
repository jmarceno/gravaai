package dev.gravaai.app.export;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;
import dev.gravaai.core.storage.StorageFiles;
import dev.gravaai.core.xdg.XdgDirectories;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.services.export.ExportQueue;
import dev.gravaai.ui.i18n.UiI18n;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/**
 * The app-side controller of the batch export queue (card "Batch export queue"): enqueues projects
 * from the dashboard and the editor, allocates a destination for each job, reveals published files
 * and reports queue changes and batch completion to the app.
 *
 * <p>The queue core ({@link ExportQueue}) owns ordering and state; this controller owns the
 * user-facing flows around it: the output-folder chooser, file naming, project filtering and the
 * batch summary. Methods that show a chooser must run on the FX thread; {@link #enqueue} and the
 * queue accessors are safe from any thread.
 */
public final class ExportQueueController implements AutoCloseable {

	/** App notifications: queue changed (badges, panels) and a batch reached its end. */
	public interface Listener {
		/** Wired to the FX thread by the app; fires after every queue change. */
		void onQueueChanged();

		/**
		 * Fires once when the last queued/running export of a batch finishes.
		 *
		 * @param completed how many exports of the batch were published
		 * @param failed how many exports of the batch failed
		 */
		void onBatchFinished(int completed, int failed);
	}

	private final ExportQueue queue;
	private final ExportPublisher publisher;
	private final Supplier<ExportPreferences> preferences;
	/**
	 * Ids already covered by a batch summary. The summary counts every finished entry that was not
	 * reported yet when the queue went idle, so an export that finishes before its enqueue returns
	 * (a project that cannot even load) is still reported exactly once.
	 */
	private final Set<Long> reported = new LinkedHashSet<>();
	private @Nullable Path lastFolder;
	private @Nullable Listener listener;
	private int lastActive;
	private boolean closed;

	/**
	 * @param queue the queue to feed
	 * @param publisher reveals published files (Show in folder)
	 * @param preferences the export settings new dashboard jobs use (the editor passes its own)
	 */
	public ExportQueueController(ExportQueue queue, ExportPublisher publisher,
			Supplier<ExportPreferences> preferences) {
		this.queue = Objects.requireNonNull(queue, "queue");
		this.publisher = Objects.requireNonNull(publisher, "publisher");
		this.preferences = Objects.requireNonNull(preferences, "preferences");
		queue.addChangeListener(this::onQueueChanged);
	}

	/** Installs the app listener (badges, batch summary). */
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	/** The queue itself (the panel and the editor read its snapshot). */
	public ExportQueue queue() {
		return queue;
	}

	/** {@code true} while at least one export is queued or running. */
	public boolean hasActive() {
		return queue.hasActive();
	}

	/** How many exports are queued or running (the header badge). */
	public int activeCount() {
		return queue.activeCount();
	}

	/**
	 * Queues every project of {@code paths} with the current export settings, asking once for the
	 * output folder. Non-project paths (raw recordings, meetings) are skipped.
	 *
	 * @return how many jobs were queued
	 */
	public int enqueueProjects(List<String> paths, Window owner) {
		List<Path> projects = new ArrayList<>();
		for (String path : paths) {
			Path candidate = Path.of(path);
			if (StorageFiles.hasProjectFileExtension(candidate) && Files.isRegularFile(candidate)) {
				projects.add(candidate);
			}
		}
		if (projects.isEmpty()) {
			return 0;
		}
		Optional<Path> folder = chooseFolder(owner, projects.get(0).getParent());
		if (folder.isEmpty()) {
			return 0;
		}
		ExportPreferences settings = preferences.get();
		for (Path project : projects) {
			enqueue(project, settings, folder.orElseThrow());
		}
		return projects.size();
	}

	/**
	 * Queues one project (the editor's current project) with the given settings, asking for the
	 * output folder.
	 *
	 * @return the queued job, or empty when the user cancelled the folder chooser
	 */
	public Optional<Long> enqueueProject(Path project, ExportPreferences settings, Window owner) {
		Optional<Path> folder = chooseFolder(owner, project.getParent());
		if (folder.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(enqueue(project, settings, folder.orElseThrow()));
	}

	/**
	 * Queues one project into {@code folder} (no chooser): the destination file name carries the
	 * format, quality and frame rate, and a numeric suffix when that name is already taken.
	 *
	 * @return the queue id of the new job
	 */
	public long enqueue(Path project, ExportPreferences settings, Path folder) {
		Objects.requireNonNull(project, "project");
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(folder, "folder");
		Path source = project.toAbsolutePath().normalize();
		Path destination = destinationFor(source, settings, folder);
		String label = projectName(source);
		return queue.enqueue(label, detail(settings), source, destination, settings);
	}

	/** Reveals a published export in the file manager. */
	public void reveal(Path file) {
		publisher.reveal(file);
	}

	/**
	 * Asks for the output folder, remembering the last choice. Defaults to the project's folder on
	 * the first use.
	 *
	 * @return the chosen folder, or empty when the user cancelled
	 */
	public Optional<Path> chooseFolder(Window owner, @Nullable Path initial) {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle(UiI18n.t("dialogs:export.queue.chooseFolder"));
		Path start = lastFolder != null ? lastFolder
				: initial != null && Files.isDirectory(initial) ? initial
						: defaultFolder();
		if (Files.isDirectory(start)) {
			chooser.setInitialDirectory(start.toFile());
		}
		java.io.File chosen = chooser.showDialog(owner);
		if (chosen == null) {
			return Optional.empty();
		}
		Path folder = chosen.toPath().toAbsolutePath().normalize();
		lastFolder = folder;
		return Optional.of(folder);
	}

	/** Cancels every export and refuses new jobs (app shutdown). */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		queue.close();
	}

	// ---- internals ----

	/** The batch summary: counts the finished exports the previous summary did not cover. */
	private void onQueueChanged() {
		int active = queue.activeCount();
		int completed = 0;
		int failed = 0;
		boolean finishing = false;
		synchronized (reported) {
			if (lastActive > 0 && active == 0) {
				Set<Long> live = new LinkedHashSet<>();
				for (ExportQueue.Entry entry : queue.entries()) {
					live.add(entry.id());
					if (reported.contains(entry.id())) {
						continue;
					}
					switch (entry.state()) {
						case COMPLETED -> {
							completed++;
							reported.add(entry.id());
						}
						case FAILED -> {
							failed++;
							reported.add(entry.id());
						}
						case CANCELLED -> reported.add(entry.id());
						default -> {
							// Still pending: the batch is not over.
						}
					}
				}
				reported.retainAll(live);
				finishing = completed + failed > 0;
			}
			lastActive = active;
		}
		Listener current = listener;
		if (current == null) {
			return;
		}
		current.onQueueChanged();
		if (finishing) {
			current.onBatchFinished(completed, failed);
		}
	}

	/** The file name: project name plus the format/quality/frame-rate tag, deduplicated. */
	private static Path destinationFor(Path project, ExportPreferences settings, Path folder) {
		ExportFormat format = settings.exportFormat() == null ? ExportFormat.MP4
				: settings.exportFormat();
		String extension = format == ExportFormat.GIF ? ".gif" : ".mp4";
		int frameRate = format == ExportFormat.GIF ? settings.gifFrameRate()
				: settings.mp4FrameRate();
		String tag = qualityLabel(settings.exportQuality()) + " " + frameRate + "fps";
		String base = sanitize(projectName(project)) + " (" + sanitize(tag) + ")";
		Path candidate = folder.resolve(base + extension);
		for (int suffix = 2; Files.exists(candidate); suffix++) {
			candidate = folder.resolve(base + " " + suffix + extension);
		}
		return candidate;
	}

	/** The panel's second line: format, quality and frame rate. */
	private static String detail(ExportPreferences settings) {
		ExportFormat format = settings.exportFormat() == null ? ExportFormat.MP4
				: settings.exportFormat();
		int frameRate = format == ExportFormat.GIF ? settings.gifFrameRate()
				: settings.mp4FrameRate();
		return UiI18n.t(format == ExportFormat.GIF ? "settings:export.gif" : "settings:export.mp4")
				+ " · " + qualityLabel(settings.exportQuality()) + " · " + frameRate + " fps";
	}

	/** The localized quality name of the export quality preset (same mapping as the export menu). */
	private static String qualityLabel(ExportQuality quality) {
		return UiI18n.t(switch (quality) {
			case MEDIUM -> "settings:export.quality.low";
			case GOOD -> "settings:export.quality.medium";
			case HIGH -> "settings:export.quality.high";
			case SOURCE -> "settings:export.quality.original";
		});
	}

	/** The project display name: the file name without its project extension. */
	private static String projectName(Path project) {
		String fileName = project.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	/** File-system-safe form of a generated name part (no separators or control characters). */
	private static String sanitize(String name) {
		String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
		return normalized.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ")
				.trim();
	}

	/** {@code ~/Videos} when it exists, otherwise the home directory. */
	private static Path defaultFolder() {
		XdgDirectories xdg = XdgDirectories.current();
		Path videos = xdg.home().resolve("Videos");
		return Files.isDirectory(videos) ? videos : xdg.home();
	}
}
