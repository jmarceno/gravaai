package dev.gravaai.ui.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.gravaai.services.projects.ProjectLibraryEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of the {@code visible} memo, sort comparators and {@code hasActiveFilters} in
 * {@code src/components/video-editor/dashboard/useDashboardModel.ts} (Playwright covers the UI in
 * {@code tests/ui/project-dashboard.spec.ts}).
 */
class DashboardLibraryFiltersTest {

	private static final long HOUR = 3_600_000L;
	private static final long DAY = 86_400_000L;
	private static final long NOW = 1_800_000_000_000L;

	private static ProjectLibraryEntry entry(String name, long createdAt, long updatedAt) {
		return new ProjectLibraryEntry("/projects/" + name + ".gravaai", name, createdAt, updatedAt, null, false,
				true);
	}

	private static List<String> names(List<ProjectLibraryEntry> entries) {
		return entries.stream().map(ProjectLibraryEntry::name).toList();
	}

	private static List<ProjectLibraryEntry> sample() {
		return List.of(
				entry("Alpha", NOW - DAY, NOW - 40 * DAY),
				entry("Beta", NOW - 2 * DAY, NOW - 20 * DAY),
				entry("Gamma", NOW - 3 * DAY, NOW - 3 * DAY),
				entry("Delta", NOW - 4 * DAY, NOW - 2 * HOUR));
	}

	@Test
	@DisplayName("uses the Electron 7 and 30 day windows")
	void usesElectronTimeWindows() {
		assertEquals(604_800_000L, DashboardLibraryFilters.WEEK_MILLIS);
		assertEquals(2_592_000_000L, DashboardLibraryFilters.MONTH_MILLIS);
		assertEquals(0, DashboardLibraryFilters.cutoffMillis(DashboardLibraryFilters.Period.ALL, NOW));
		assertEquals(NOW - 604_800_000L,
				DashboardLibraryFilters.cutoffMillis(DashboardLibraryFilters.Period.WEEK, NOW));
		assertEquals(NOW - 2_592_000_000L,
				DashboardLibraryFilters.cutoffMillis(DashboardLibraryFilters.Period.MONTH, NOW));
	}

	@Test
	@DisplayName("filters by the selected time window and sorts newest first by default")
	void filtersByTimeWindow() {
		assertEquals(List.of("Delta", "Gamma", "Beta", "Alpha"), names(DashboardLibraryFilters.visible(
				sample(), "", DashboardLibraryFilters.Period.ALL, DashboardLibraryFilters.Sort.RECENT, null, NOW)));
		assertEquals(List.of("Delta", "Gamma"), names(DashboardLibraryFilters.visible(
				sample(), "", DashboardLibraryFilters.Period.WEEK, DashboardLibraryFilters.Sort.RECENT, null, NOW)));
		assertEquals(List.of("Delta", "Gamma", "Beta"), names(DashboardLibraryFilters.visible(
				sample(), "", DashboardLibraryFilters.Period.MONTH, DashboardLibraryFilters.Sort.RECENT, null, NOW)));
	}

	@Test
	@DisplayName("matches the search trim-insensitively and keeps the chosen sort")
	void filtersBySearchAndSort() {
		assertEquals(List.of("Beta"), names(DashboardLibraryFilters.visible(
				List.of(entry("Alpha", NOW, NOW), entry("Beta", NOW, NOW), entry("Gamma", NOW, NOW)),
				"bet", DashboardLibraryFilters.Period.ALL, DashboardLibraryFilters.Sort.RECENT, null, NOW)));
		assertEquals(List.of("Beta"), names(DashboardLibraryFilters.visible(
				List.of(entry("Beta", NOW, NOW), entry("Alpha", NOW, NOW)),
				"  beta  ", DashboardLibraryFilters.Period.ALL, DashboardLibraryFilters.Sort.RECENT, null, NOW)));
		assertEquals(List.of("Alpha", "Beta", "Gamma"), names(DashboardLibraryFilters.visible(
				List.of(entry("Gamma", NOW, NOW), entry("Beta", NOW, NOW), entry("Alpha", NOW, NOW)),
				"", DashboardLibraryFilters.Period.ALL, DashboardLibraryFilters.Sort.NAME, null, NOW)));
	}

	@Test
	@DisplayName("sorts by creation time when Last created is chosen")
	void sortsByCreationTime() {
		List<ProjectLibraryEntry> entries = List.of(
				entry("EditedRecently", NOW - 5 * DAY, NOW - HOUR),
				entry("CreatedRecently", NOW - HOUR, NOW - 5 * DAY));

		assertEquals(List.of("CreatedRecently", "EditedRecently"), names(DashboardLibraryFilters.visible(
				entries, "", DashboardLibraryFilters.Period.ALL, DashboardLibraryFilters.Sort.CREATED, null, NOW)));
	}

	@Test
	@DisplayName("keeps only the projects of the selected folder")
	void filtersByFolder() {
		ProjectFolder folder = new ProjectFolder("f1", "Work", "#de85ac",
				List.of("/projects/Beta.gravaai", "/projects/Delta.gravaai"));

		assertEquals(List.of("Delta", "Beta"), names(DashboardLibraryFilters.visible(
				sample(), "", DashboardLibraryFilters.Period.ALL, DashboardLibraryFilters.Sort.RECENT, folder, NOW)));
	}

	@Test
	@DisplayName("reports active filters and resolves the selected folder")
	void reportsActiveFilters() {
		List<ProjectFolder> folders = List.of(new ProjectFolder("f1", "Work", "#de85ac", List.of()));

		assertFalse(DashboardLibraryFilters.hasActiveFilters("", DashboardLibraryFilters.Period.ALL, folders,
				DashboardSection.PROJECTS));
		assertFalse(DashboardLibraryFilters.hasActiveFilters("  ", DashboardLibraryFilters.Period.ALL, folders,
				DashboardSection.PROJECTS));
		assertTrue(DashboardLibraryFilters.hasActiveFilters("demo", DashboardLibraryFilters.Period.ALL, folders,
				DashboardSection.PROJECTS));
		assertTrue(DashboardLibraryFilters.hasActiveFilters("", DashboardLibraryFilters.Period.WEEK, folders,
				DashboardSection.PROJECTS));
		assertTrue(DashboardLibraryFilters.hasActiveFilters("", DashboardLibraryFilters.Period.ALL, folders,
				DashboardSection.folder("f1")));
		assertFalse(DashboardLibraryFilters.hasActiveFilters("", DashboardLibraryFilters.Period.ALL, folders,
				DashboardSection.folder("missing")));

		assertEquals("f1",
				DashboardLibraryFilters.selectedFolder(folders, DashboardSection.folder("f1")).id());
		assertNull(DashboardLibraryFilters.selectedFolder(folders, DashboardSection.PROJECTS));
		assertNull(DashboardLibraryFilters.selectedFolder(folders, DashboardSection.folder("missing")));
	}

	@Test
	@DisplayName("maps React section strings to the sealed section type")
	void mapsSectionStrings() {
		assertEquals("projects", DashboardSection.PROJECTS.wire());
		assertEquals("raw", DashboardSection.RAW.wire());
		assertEquals("meetings", DashboardSection.MEETINGS.wire());
		assertEquals("settings", DashboardSection.SETTINGS.wire());
		assertEquals("f1", DashboardSection.folder("f1").wire());
		assertTrue(DashboardSection.folder("f1").is("f1"));
	}
}