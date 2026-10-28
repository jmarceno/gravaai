package dev.gravaai.services.extensions;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

/**
 * Render hook registrations of every active extension, in registration order. Hooks run on the
 * render thread; a hook that throws is logged and skipped so one broken extension cannot break
 * preview or export (the same isolation the automation engine gives rules).
 */
public final class ExtensionRenderHookRegistry {

	private static final System.Logger LOG = System.getLogger(ExtensionRenderHookRegistry.class.getName());

	record Registration(String extensionId, RenderHookPhase phase, Value hook) {}

	private final List<Registration> registrations = new ArrayList<>();

	Registration register(String extensionId, RenderHookPhase phase, Value hook) {
		Registration registration = new Registration(extensionId, phase, hook);
		synchronized (registrations) {
			registrations.add(registration);
		}
		return registration;
	}

	void dispose(Registration registration) {
		synchronized (registrations) {
			registrations.remove(registration);
		}
	}

	/** Removes every registration of one extension (deactivate/disable). */
	void disposeExtension(String extensionId) {
		synchronized (registrations) {
			registrations.removeIf(registration -> registration.extensionId().equals(extensionId));
		}
	}

	/** Number of hooks registered for a phase; for tests and diagnostics. */
	public int count(RenderHookPhase phase) {
		synchronized (registrations) {
			return (int) registrations.stream().filter(registration -> registration.phase() == phase).count();
		}
	}

	/** Runs every hook for the phase; never throws. */
	public void render(RenderHookPhase phase, RenderHookContext context) {
		if (!phase.dispatched()) {
			return;
		}
		List<Registration> snapshot;
		synchronized (registrations) {
			snapshot = List.copyOf(registrations);
		}
		for (Registration registration : snapshot) {
			if (registration.phase() != phase) {
				continue;
			}
			try {
				registration.hook().execute(context);
			} catch (PolyglotException error) {
				LOG.log(
						System.Logger.Level.WARNING,
						"[extensions] " + registration.extensionId() + " " + phase.wire()
								+ " hook failed: " + error.getMessage());
			} catch (RuntimeException error) {
				LOG.log(
						System.Logger.Level.WARNING,
						"[extensions] " + registration.extensionId() + " " + phase.wire()
								+ " hook failed: " + error);
			}
		}
	}
}
