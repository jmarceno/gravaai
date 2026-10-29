package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.gravaai.services.projects.ProjectLibraryEntry;

/**
 * Filtering and sorting for the dashboard grid (port of the {@code visible} memo and
 * {@code hasActiveFilters} in {@code src/components/video-editor/dashboard/useDashboardModel.ts}).
 */
public final class DashboardLibraryFilters {

	/** Port of the literal windows in {@code useDashboardModel.ts}. */
	public static final long WEEK_MILLIS = 7L * 86_400_000L;

	/** Port of the literal windows in {@code useDashboardModel.ts}. */
	public static final long MONTH_MILLIS = 30L * 86_400_000L;

	/** The time filter chips ("All", "Last 7 days", "Last 30 days"). */
	public enum Period {
		ALL("all"),
		WEEK("week"),
		MONTH("month");

		private final String wire;

		Period(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	/** The sort dropdown ("Last edited", "Last created", "Name"). */
	public enum Sort {
		RECENT("recent"),
		CREATED("created"),
		NAME("name");

		private final String wire;

		Sort(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		/** The raw library has no last-edited sort in React ({@code !isRaw} guard). */
		public static Sort wire(String wire) {
			for (Sort sort : values()) {
				if (sort.wire.equals(wire)) {
					return sort;
				}
			}
			return RECENT;
		}
	}

	private DashboardLibraryFilters() {
	}

	/** Port of the {@code cutoff} expression: the oldest {@code updatedAt} still visible. */
	public static long cutoffMillis(Period period, long now) {
		return switch (period) {
			case WEEK -> now - WEEK_MILLIS;
			case MONTH -> now - MONTH_MILLIS;
			case ALL -> 0;
		};
	}

	/**
	 * Port of the {@code visible} memo: time window, case-insensitive name search, folder membership,
	 * then the selected sort.
	 *
	 * @param folder the active project folder, or {@code null} for the Projects library
	 */
	public static List<ProjectLibraryEntry> visible(List<ProjectLibraryEntry> entries, String query,
			Period period, Sort sort, ProjectFolder folder, long now) {
		long cutoff = cutoffMillis(period, now);
		String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
		List<ProjectLibraryEntry> visible = new ArrayList<>();
		for (ProjectLibraryEntry entry : entries) {
			if (entry.updatedAt() < cutoff) {
				continue;
			}
			if (!entry.name().toLowerCase(Locale.getDefault()).contains(normalizedQuery)) {
				continue;
			}
			if (folder != null && !folder.paths().contains(entry.path())) {
				continue;
			}
			visible.add(entry);
		}
		visible.sort(comparator(sort));
		return List.copyOf(visible);
	}

	/** Port of the inline {@code sort} comparator, including {@code localeCompare} for names. */
	public static Comparator<ProjectLibraryEntry> comparator(Sort sort) {
		Collator collator = Collator.getInstance(Locale.getDefault());
		return switch (sort) {
			case NAME -> (left, right) -> collator.compare(left.name(), right.name());
			case CREATED -> Comparator.<ProjectLibraryEntry>comparingLong(ProjectLibraryEntry::createdAt)
					.reversed();
			case RECENT -> Comparator.<ProjectLibraryEntry>comparingLong(ProjectLibraryEntry::updatedAt)
					.reversed();
		};
	}

	/** Port of {@code hasActiveFilters}: a search, a time filter or a selected folder. */
	public static @Nullable boolean hasActiveFilters(String query, Period period, List<ProjectFolder> folders,
			DashboardSection section) {
		boolean folderSelected = section instanceof DashboardSection.Folder folder
				&& folders.stream().anyMatch(candidate -> candidate.id().equals(folder.id()));
		return (query != null && !query.trim().isEmpty()) || period != Period.ALL || folderSelected;
	}

	/** The folder selected by {@code section}, or {@code null}. */
	public static @Nullable ProjectFolder selectedFolder(List<ProjectFolder> folders, DashboardSection section) {
		if (!(section instanceof DashboardSection.Folder selected)) {
			return null;
		}
		return folders.stream().filter(folder -> folder.id().equals(selected.id())).findFirst().orElse(null);
	}
}
