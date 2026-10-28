package dev.gravaai.services.shortcuts;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Port of {@code electron/globalShortcuts/dispatch.ts}: the debounce and the "queue until the HUD
 * is ready" behaviour.
 *
 * <p>A shortcut can arrive while the HUD window is still starting (or while it was closed by the
 * user, in which case the provider recreates it). The action is then queued and delivered once the
 * HUD reports itself ready, but never later than {@link #PENDING_MAX_AGE_MS} after it arrived.
 */
public final class GlobalShortcutDispatcher {

	/** The same action twice inside this window fires once. */
	public static final long DEBOUNCE_MS = 400;

	/** A queued action older than this is dropped instead of firing late. */
	public static final long PENDING_MAX_AGE_MS = 30_000;

	private static final System.Logger LOG = System.getLogger("gravaai.shortcuts");

	private Supplier<GlobalShortcutHud> hudProvider;
	private final LongSupplier clock;
	private final Consumer<String> log;

	private record PendingAction(GlobalShortcutAction action, long at) {}

	private record LastAction(GlobalShortcutAction action, long at) {}

	private PendingAction pendingAction;
	private LastAction lastAction;

	public GlobalShortcutDispatcher(Supplier<GlobalShortcutHud> hudProvider) {
		this(hudProvider, System::currentTimeMillis, GlobalShortcutDispatcher::defaultLog);
	}

	public GlobalShortcutDispatcher(
			Supplier<GlobalShortcutHud> hudProvider, LongSupplier clock, Consumer<String> log) {
		this.hudProvider = hudProvider;
		this.clock = clock;
		this.log = log;
	}

	/** main.ts supplies how to recreate the HUD when a shortcut arrives without one. */
	public synchronized void setHudProvider(Supplier<GlobalShortcutHud> hudProvider) {
		this.hudProvider = hudProvider;
	}

	/** Port of {@code dispatchGlobalShortcutAction}. */
	public synchronized void dispatch(GlobalShortcutAction action) {
		if (action == null) {
			return;
		}
		long now = clock.getAsLong();
		if (lastAction != null && lastAction.action() == action && now - lastAction.at() < DEBOUNCE_MS) {
			return;
		}
		lastAction = new LastAction(action, now);

		GlobalShortcutHud hud = hudProvider == null ? null : hudProvider.get();
		if (hud != null && hud.isAlive() && hud.isReady()) {
			hud.deliver(action);
			return;
		}
		// Delivered once the HUD's LaunchWindow subscribes.
		pendingAction = new PendingAction(action, now);
		log.accept("[global-shortcuts] Queued " + action.wire() + " until the HUD is ready");
	}

	/** Called when the HUD's launch view subscribes (port of the READY_CHANNEL handler). */
	public synchronized void hudReady() {
		if (pendingAction == null) {
			return;
		}
		PendingAction pending = pendingAction;
		pendingAction = null;
		if (clock.getAsLong() - pending.at() < PENDING_MAX_AGE_MS) {
			GlobalShortcutHud hud = hudProvider == null ? null : hudProvider.get();
			if (hud != null) {
				hud.deliver(pending.action());
			}
		}
	}

	/** Whether an action is waiting for the HUD. For tests and diagnostics. */
	public synchronized boolean hasPendingAction() {
		return pendingAction != null;
	}

	private static void defaultLog(String message) {
		LOG.log(System.Logger.Level.INFO, message);
	}
}
