package dev.gravaai.services.extensions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * One loaded extension: its own Polyglot {@link Context}, the evaluated ES module and the
 * {@link ExtensionApi} handed to {@code activate(api)}.
 *
 * <p>The sandbox is {@code HostAccess.EXPLICIT} (only the injected API object's exported members),
 * with no host class lookup, no IO, no threads, no native access and no environment access. The
 * shared {@link Engine} is owned by {@link ExtensionHost}. Registered render hooks are wrapped by
 * {@link ExtensionHostInterop} so {@code ctx.width}/{@code ctx.ctx.fillStyle} work as documented in
 * {@code EXTENSIONS.md}.
 */
final class LoadedExtension implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(LoadedExtension.class.getName());

	private final Path root;
	private final ExtensionManifest manifest;
	private final Context context;
	private final Value module;
	private final Value activateFunction;
	private final ExtensionApi api;
	private final ExtensionRenderHookRegistry registry;
	private boolean activated;

	private LoadedExtension(
			Path root,
			ExtensionManifest manifest,
			Context context,
			Value module,
			Value activateFunction,
			ExtensionApi api,
			ExtensionRenderHookRegistry registry) {
		this.root = root;
		this.manifest = manifest;
		this.context = context;
		this.module = module;
		this.activateFunction = activateFunction;
		this.api = api;
		this.registry = registry;
	}

	static LoadedExtension load(
			Path root, ExtensionManifest manifest, Engine engine, ExtensionRenderHookRegistry registry) {
		Path mainPath = manifest.mainPath(root);
		if (!Files.isRegularFile(mainPath)) {
			throw new ExtensionManifestException(
					"Entry point \"" + manifest.main() + "\" does not exist in the extension directory");
		}
		String source;
		try {
			source = Files.readString(mainPath, StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new ExtensionManifestException("Could not read entry point \"" + manifest.main() + "\"", error);
		}

		Source jsSource;
		try {
			jsSource = Source.newBuilder("js", source, manifest.main())
					.mimeType("application/javascript+module")
					.build();
		} catch (IOException error) {
			throw new ExtensionManifestException("Could not read entry point \"" + manifest.main() + "\"", error);
		}

		Context context = Context.newBuilder("js")
				.engine(engine)
				.allowHostAccess(HostAccess.EXPLICIT)
				.allowHostClassLookup(className -> false)
				.allowIO(IOAccess.NONE)
				.allowCreateThread(false)
				.allowNativeAccess(false)
				.option("js.esm-eval-returns-exports", "true")
				.build();
		try {
			Value module = context.eval(jsSource);
			Value activate = module.getMember("activate");
			if (activate == null || !activate.canExecute()) {
				throw new ExtensionManifestException(
						"Entry point \"" + manifest.main() + "\" must export function activate(api)");
			}
			// GraalJS does not map Java getters/setters to JS properties; the interop shim restores
			// the documented ctx.ctx.fillStyle / ctx.width surface for every registered hook.
			ExtensionHostInterop interop = ExtensionHostInterop.install(context);
			return new LoadedExtension(
					root, manifest, context, module, activate,
					new ExtensionApi(manifest, registry, interop::wrapHook), registry);
		} catch (PolyglotException | ExtensionManifestException error) {
			context.close();
			if (error instanceof ExtensionManifestException manifestError) {
				throw manifestError;
			}
			throw new ExtensionManifestException(
					"Could not load \"" + manifest.main() + "\": " + error.getMessage(), error);
		}
	}

	ExtensionManifest manifest() {
		return manifest;
	}

	Path root() {
		return root;
	}

	/** Runs {@code activate(api)} (idempotent); failures surface as manifest exceptions. */
	synchronized void activate() {
		if (activated) {
			return;
		}
		try {
			activateFunction.execute(api);
			activated = true;
		} catch (PolyglotException error) {
			registry.disposeExtension(manifest.id());
			throw new ExtensionManifestException("activate(api) failed: " + error.getMessage(), error);
		}
	}

	synchronized boolean activated() {
		return activated;
	}

	/** Runs {@code deactivate()} when the module exports it, then disposes every registration. */
	synchronized void deactivate() {
		if (!activated) {
			registry.disposeExtension(manifest.id());
			return;
		}
		Value deactivate = module.getMember("deactivate");
		if (deactivate != null && deactivate.canExecute()) {
			try {
				deactivate.execute();
			} catch (PolyglotException error) {
				LOG.log(
						System.Logger.Level.WARNING,
						"[extensions] " + manifest.id() + " deactivate() failed: " + error.getMessage());
			}
		}
		registry.disposeExtension(manifest.id());
		activated = false;
	}

	@Override
	public void close() {
		try {
			deactivate();
		} finally {
			context.close();
		}
	}
}
