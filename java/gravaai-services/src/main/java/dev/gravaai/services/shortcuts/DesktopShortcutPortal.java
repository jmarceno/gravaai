package dev.gravaai.services.shortcuts;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.gravaai.platform.portal.GlobalShortcutsPortal;
import dev.gravaai.platform.portal.GlobalShortcutsPortal.BoundShortcut;
import dev.gravaai.platform.portal.GlobalShortcutsPortal.Shortcut;

/**
 * The desktop's own global-shortcut service (xdg-desktop-portal GlobalShortcuts), as
 * {@link GlobalShortcutsService} uses it. A seam, so the service is testable without a session bus.
 */
public interface DesktopShortcutPortal extends AutoCloseable {

	/**
	 * The id the portal knows Gravaai by: the basename of the {@code gravaai.desktop} entry the
	 * AppImage installs, which is also where the desktop takes the name it shows the user.
	 */
	String APP_ID = "gravaai";

	/** Opens the portal; empty when the desktop has none. The consumer receives pressed shortcut ids. */
	Function<Consumer<String>, Optional<DesktopShortcutPortal>> SYSTEM = onActivated ->
			GlobalShortcutsPortal.open(APP_ID, onActivated).map(DesktopShortcutPortal::of);

	/** An opener for desktops without the portal (and for tests). */
	Function<Consumer<String>, Optional<DesktopShortcutPortal>> NONE = onActivated -> Optional.empty();

	/** Replaces the registered shortcuts; returns what the desktop bound. Blocks until it answers. */
	List<BoundShortcut> bind(List<Shortcut> shortcuts) throws IOException;

	/** Stops every shortcut from firing. */
	void unbind();

	@Override
	void close();

	private static DesktopShortcutPortal of(GlobalShortcutsPortal portal) {
		return new DesktopShortcutPortal() {
			@Override
			public List<BoundShortcut> bind(List<Shortcut> shortcuts) throws IOException {
				return portal.bind(shortcuts);
			}

			@Override
			public void unbind() {
				portal.unbind();
			}

			@Override
			public void close() {
				portal.close();
			}
		};
	}
}
