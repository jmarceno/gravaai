package dev.gravaai.services.extensions;

import org.jspecify.annotations.Nullable;

import java.util.function.UnaryOperator;

import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * The host API object injected into one extension's {@code activate(api)} ({@code
 * EXTENSIONS.md} "API Surface"). Every member is {@link HostAccess.Export exported} so the
 * sandbox exposes exactly this object and nothing else; each registration checks the manifest
 * permission first and returns a callable dispose function.
 *
 * <p>Increment 1 implements {@code log} and {@code registerRenderHook}; the remaining documented
 * registrations/queries land in the next increments (settings panels, cursor effects, assets,
 * events, drawIcon).
 */
public final class ExtensionApi {

	private static final System.Logger LOG = System.getLogger("gravaai.extensions");

	private final ExtensionManifest manifest;
	private final ExtensionRenderHookRegistry registry;
	/** Null when hooks are registered unchanged (the default). */
	private final @Nullable UnaryOperator<Value> hookWrapper;

	ExtensionApi(ExtensionManifest manifest, ExtensionRenderHookRegistry registry) {
		this(manifest, registry, null);
	}

	/**
	 * @param hookWrapper wraps a registered hook so the context it receives exposes Canvas 2D-style
	 *        properties (see {@link ExtensionHostInterop}); null registers hooks unchanged
	 */
	ExtensionApi(ExtensionManifest manifest, ExtensionRenderHookRegistry registry,
			@Nullable UnaryOperator<Value> hookWrapper) {
		this.manifest = manifest;
		this.registry = registry;
		this.hookWrapper = hookWrapper;
	}

	@HostAccess.Export
	public void log(String message) {
		LOG.log(System.Logger.Level.INFO, "[" + manifest.id() + "] " + (message == null ? "" : message));
	}

	/** {@code api.log(message, payload)}: the payload is formatted for the app log only. */
	@HostAccess.Export
	public void log(String message, Value payload) {
		LOG.log(
				System.Logger.Level.INFO,
				"[" + manifest.id() + "] " + (message == null ? "" : message) + " " + format(payload));
	}

	/**
	 * {@code api.registerRenderHook(phase, hook)}: requires the {@code render} permission and
	 * returns a function that removes the registration.
	 */
	@HostAccess.Export
	public ProxyExecutable registerRenderHook(String phase, Value hook) {
		requirePermission(ExtensionPermission.RENDER, "register a render hook");
		RenderHookPhase parsed = RenderHookPhase.fromWire(phase);
		if (parsed == null) {
			throw new IllegalArgumentException("Unknown render hook phase \"" + phase + "\"");
		}
		if (hook == null || !hook.canExecute()) {
			throw new IllegalArgumentException("Render hook for \"" + phase + "\" must be a function");
		}
		Value registeredHook = hookWrapper == null ? hook : hookWrapper.apply(hook);
		ExtensionRenderHookRegistry.Registration registration =
				registry.register(manifest.id(), parsed, registeredHook);
		return arguments -> {
			registry.dispose(registration);
			return null;
		};
	}

	/** Throws when the manifest did not request the permission the API call needs. */
	void requirePermission(ExtensionPermission permission, String action) {
		if (!manifest.hasPermission(permission)) {
			throw new SecurityException("Extension \"" + manifest.id() + "\" needs the \""
					+ permission.wire() + "\" permission to " + action);
		}
	}

	private static String format(Value payload) {
		if (payload == null || payload.isNull()) {
			return "null";
		}
		if (payload.isString()) {
			return payload.asString();
		}
		if (payload.isNumber() || payload.isBoolean()) {
			return payload.toString();
		}
		return payload.toString();
	}
}
