package dev.gravaai.services.extensions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.graalvm.polyglot.Engine;

/**
 * Discovery, lifecycle and render dispatch for installed extensions (plan §5.18).
 *
 * <p>Extensions live in {@code userData/extensions/<dir>/gravaai-extension.json}. Each loaded
 * extension runs in its own sandboxed GraalJS context (see {@link LoadedExtension}); the host owns
 * one shared Polyglot {@link Engine} so discovery stays cheap. Failures are per extension and
 * never throw: an extension that fails to load or activate is reported as
 * {@link ExtensionState#FAILED} with the cause, and the others keep running.
 *
 * <p>Threading: the host and its contexts are used from one thread at a time (the editor/render
 * thread), which is what the compositor already guarantees for its canvas.
 */
public final class ExtensionHost implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(ExtensionHost.class.getName());

	/** One discovered directory. */
	private static final class Entry {
		final Path root;
		final String key;
		ExtensionManifest manifest;
		ExtensionState state = ExtensionState.DISCOVERED;
		String error;
		LoadedExtension loaded;

		Entry(Path root, String key) {
			this.root = root;
			this.key = key;
		}

		ExtensionInfo info() {
			String id = manifest != null ? manifest.id() : root.getFileName().toString();
			return new ExtensionInfo(id, root, manifest, state, error);
		}
	}

	private final Path extensionsDir;
	private final ExtensionRenderHookRegistry renderHooks = new ExtensionRenderHookRegistry();
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private Engine engine;
	private boolean closed;

	public ExtensionHost(Path extensionsDir) {
		this.extensionsDir = Objects.requireNonNull(extensionsDir, "extensionsDir");
	}

	public Path extensionsDir() {
		return extensionsDir;
	}

	/**
	 * Port of the "Discovery" lifecycle step: scans the extensions directory and validates every
	 * manifest. Invalid directories are kept as {@link ExtensionState#FAILED} entries so the
	 * extension manager can show the error. Already active extensions are left untouched.
	 */
	public synchronized List<ExtensionInfo> scan() {
		Map<String, Entry> previous = new LinkedHashMap<>(entries);
		entries.clear();
		List<Path> directories = extensionDirectories();
		for (Path directory : directories) {
			Path manifestFile = directory.resolve(ExtensionManifest.FILE_NAME);
			if (!Files.isRegularFile(manifestFile)) {
				continue;
			}
			ExtensionManifest manifest = null;
			String error = null;
			try {
				manifest = ExtensionManifestParser.parse(directory);
			} catch (ExtensionManifestException failure) {
				error = failure.getMessage();
			}
			String key;
			if (manifest == null) {
				key = "dir:" + directory.getFileName();
			} else {
				key = manifest.id();
				if (entries.containsKey(key)) {
					Entry duplicate = new Entry(directory, key + "#" + directory.getFileName());
					duplicate.manifest = manifest;
					duplicate.state = ExtensionState.FAILED;
					duplicate.error = "Duplicate extension id \"" + manifest.id() + "\" (already provided by "
							+ entries.get(key).root + ")";
					entries.put(duplicate.key, duplicate);
					continue;
				}
			}
			Entry entry = new Entry(directory, key);
			entry.manifest = manifest;
			entry.error = error;
			if (error != null) {
				entry.state = ExtensionState.FAILED;
			}
			Entry old = previous.remove(key);
			if (old != null && old.loaded != null && old.root.equals(directory)) {
				entry.loaded = old.loaded;
				entry.state = old.state;
				entry.error = old.error;
			}
			entries.put(key, entry);
		}
		// Extensions whose directory disappeared are deactivated.
		for (Entry removed : previous.values()) {
			if (removed.loaded != null) {
				removed.loaded.close();
			}
		}
		return extensions();
	}

	/** Snapshot of every discovered extension, in discovery order. */
	public synchronized List<ExtensionInfo> extensions() {
		return entries.values().stream().map(Entry::info).toList();
	}

	/** One extension by manifest id, or null. */
	public synchronized ExtensionInfo extension(String id) {
		Entry entry = entries.get(id);
		if (entry != null) {
			return entry.info();
		}
		for (Entry candidate : entries.values()) {
			if (candidate.manifest != null && candidate.manifest.id().equals(id)) {
				return candidate.info();
			}
		}
		return null;
	}

	/**
	 * Loads and activates one extension (the "Activation" lifecycle step). Never throws: the
	 * returned info is {@code FAILED} with the cause when loading or {@code activate(api)} failed.
	 */
	public synchronized ExtensionInfo enable(String id) {
		Entry entry = entries.get(id);
		if (entry == null) {
			return new ExtensionInfo(id, null, null, ExtensionState.FAILED, "Unknown extension \"" + id + "\"");
		}
		if (entry.state == ExtensionState.ACTIVE && entry.loaded != null) {
			return entry.info();
		}
		if (entry.manifest == null) {
			entry.state = ExtensionState.FAILED;
			return entry.info();
		}
		try {
			LoadedExtension loaded = LoadedExtension.load(entry.root, entry.manifest, engine(), renderHooks);
			try {
				loaded.activate();
			} catch (RuntimeException failure) {
				loaded.close();
				throw failure;
			}
			entry.loaded = loaded;
			entry.state = ExtensionState.ACTIVE;
			entry.error = null;
			LOG.log(System.Logger.Level.INFO, "[extensions] activated " + entry.manifest.id());
		} catch (ExtensionManifestException failure) {
			entry.state = ExtensionState.FAILED;
			entry.error = failure.getMessage();
			LOG.log(System.Logger.Level.WARNING,
					"[extensions] could not activate " + entry.manifest.id() + ": " + failure.getMessage());
		} catch (RuntimeException failure) {
			entry.state = ExtensionState.FAILED;
			entry.error = failure.toString();
			LOG.log(System.Logger.Level.WARNING,
					"[extensions] could not activate " + entry.manifest.id() + ": " + failure);
		}
		return entry.info();
	}

	/** Scans, then activates every extension that was discovered and is not yet running. */
	public synchronized List<ExtensionInfo> enableAll() {
		scan();
		List<String> keys = entries.keySet().stream().toList();
		for (String key : keys) {
			Entry entry = entries.get(key);
			if (entry != null && entry.state == ExtensionState.DISCOVERED) {
				enable(key);
			}
		}
		return extensions();
	}

	/** Runs {@code deactivate()} and disposes the context (the "Deactivation" step). */
	public synchronized ExtensionInfo disable(String id) {
		Entry entry = entries.get(id);
		if (entry == null) {
			return new ExtensionInfo(id, null, null, ExtensionState.FAILED, "Unknown extension \"" + id + "\"");
		}
		if (entry.loaded != null) {
			entry.loaded.close();
			entry.loaded = null;
		}
		entry.state = ExtensionState.DISABLED;
		entry.error = null;
		return entry.info();
	}

	/** The registrations of every active extension; the compositor renders a phase through this. */
	public ExtensionRenderHookRegistry renderHooks() {
		return renderHooks;
	}

	/** Runs every registered hook for a phase; never throws. */
	public void render(RenderHookPhase phase, RenderHookContext context) {
		renderHooks.render(phase, context);
	}

	/** Number of active extensions; for tests and diagnostics. */
	public synchronized int activeCount() {
		return (int) entries.values().stream().filter(entry -> entry.state == ExtensionState.ACTIVE).count();
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		for (Entry entry : entries.values()) {
			if (entry.loaded != null) {
				entry.loaded.close();
				entry.loaded = null;
			}
			entry.state = ExtensionState.DISABLED;
		}
		if (engine != null) {
			engine.close();
			engine = null;
		}
	}

	private Engine engine() {
		if (engine == null) {
			engine = Engine.newBuilder("js")
					.option("engine.WarnInterpreterOnly", "false")
					.build();
		}
		return engine;
	}

	private List<Path> extensionDirectories() {
		if (!Files.isDirectory(extensionsDir)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(extensionsDir)) {
			List<Path> directories = new ArrayList<>();
			stream.filter(Files::isDirectory)
					.sorted((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
					.forEach(directories::add);
			return directories;
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "[extensions] could not list " + extensionsDir + ": " + error);
			return List.of();
		}
	}
}
