package dev.gravaai.core.storage;

import java.util.Optional;

/**
 * Keeps a committed library operation from failing because its recent-project history update failed
 * (port of {@code electron/ipc/project/recentMetadata.ts}, plan §5.2).
 */
public final class RecentMetadata {

	private static final System.Logger LOG = System.getLogger("gravaai.storage");

	private RecentMetadata() {}

	/**
	 * Runs the metadata update; a failure is logged and returned as a warning string, matching the
	 * TS {@code Promise<string | undefined>}. The caller's operation has already been committed.
	 */
	public static Optional<String> persist(Runnable update) {
		try {
			update.run();
			return Optional.empty();
		} catch (RuntimeException error) {
			String warning = "Project operation completed, but recent-project history could not be updated: "
					+ error;
			LOG.log(System.Logger.Level.WARNING, warning);
			return Optional.of(warning);
		}
	}
}
