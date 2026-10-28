package dev.gravaai.ui.dashboard;

/**
 * The sidebar selection of the dashboard (port of the {@code section} state in
 * {@code useDashboardModel.ts}).
 *
 * <p>React keeps one string: {@code "projects"}, {@code "raw"}, {@code "meetings"},
 * {@code "settings"} or a folder id. The Java port spells the union out so a view cannot select a
 * section that does not exist.
 */
public sealed interface DashboardSection {

	record Projects() implements DashboardSection {}

	record Raw() implements DashboardSection {}

	record Meetings() implements DashboardSection {}

	record Settings() implements DashboardSection {}

	/** A project folder, selected from the sidebar. */
	record Folder(String id) implements DashboardSection {

		public Folder {
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("folder id must not be blank");
			}
		}
	}

	Projects PROJECTS = new Projects();
	Raw RAW = new Raw();
	Meetings MEETINGS = new Meetings();
	Settings SETTINGS = new Settings();

	static DashboardSection folder(String id) {
		return new Folder(id);
	}

	/** The React section string ({@code "projects"}, a folder id, ...). */
	default String wire() {
		return switch (this) {
			case Projects ignored -> "projects";
			case Raw ignored -> "raw";
			case Meetings ignored -> "meetings";
			case Settings ignored -> "settings";
			case Folder folder -> folder.id();
		};
	}

	/** Same comparison as the React {@code section === id} checks. */
	default boolean is(String wire) {
		return wire() != null && wire().equals(wire);
	}
}
