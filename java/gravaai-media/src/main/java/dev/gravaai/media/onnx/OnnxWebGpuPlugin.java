package dev.gravaai.media.onnx;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The process-wide WebGPU plugin-EP registration: one library load, one keeper environment, one
 * device list for the whole JVM.
 *
 * <p><b>Why a keeper.</b> {@code OrtApi::RegisterExecutionProviderLibrary} registers into a
 * process-wide registry, not into the environment it is called with. A second call for the same
 * plugin fails ("already registered under webgpu"), and after that failure session creation on the
 * runtime crashes instead of returning a status — verified here against ORT 1.31.1 and the 0.5.0
 * plugin. Releasing the environment that registered the plugin unregisters it process-wide, and
 * the device handles it enumerated become invalid with it. So the registration must happen exactly
 * once, from one environment that lives as long as the process, and every session — on any other
 * environment — is appended with a device handle from that keeper.</p>
 *
 * <p><b>Failure is not fatal.</b> A missing plugin file, a runtime/plugin version mismatch, a
 * machine without {@code libvulkan.so.1}, or a driver that finds no adapter all mean "no GPU
 * device": {@link #probe()} then reports the CPU provider and the caller constructs a CPU session.
 * The super-resolution engine's per-inference failure path is the second layer of that fallback.</p>
 *
 * <p>The visibility of the probe is deliberately asymmetric: it is a {@link Selection} snapshot
 * that a caller may log or show, and the handles in it are native and only valid while the JVM
 * lives.</p>
 */
public final class OnnxWebGpuPlugin {

	private static final System.Logger LOG =
			System.getLogger(OnnxWebGpuPlugin.class.getName());

	/** The plugin library's file name beside {@code libonnxruntime.so}. */
	public static final String LIBRARY_NAME = "libonnxruntime_providers_webgpu.so";

	/** The registration name the plugin documents (and the one Unregister takes). */
	public static final String REGISTRATION_NAME = "webgpu";

	private static final Object LOCK = new Object();
	private static @Nullable Selection probed;
	private static boolean probeStarted;

	/** Kept alive for the process lifetime: its env owns the plugin devices sessions use. */
	private static @Nullable OnnxRuntimeApi keeper;
	private static @Nullable MemorySegment keeperEnv;

	private OnnxWebGpuPlugin() {}

	/**
	 * One probe of this process's GPU provider.
	 *
	 * @param provider the provider sessions should run on: the first WebGPU device, or CPU
	 * @param device   the probed device when {@code provider} is WebGPU, else null
	 * @param detail   why the GPU is not in use, for logs and the settings panel; null when it is
	 */
	public record Selection(OnnxExecutionProvider provider, @Nullable OnnxEpDevice device,
			@Nullable String detail) {

		public Selection {
			if (provider.hardware() && device == null) {
				throw new IllegalArgumentException("a hardware provider needs its device");
			}
		}

		public static Selection cpu(String detail) {
			return new Selection(OnnxExecutionProvider.CPU, null, detail);
		}
	}

	/**
	 * The process's GPU provider selection, probing once on first use.
	 *
	 * <p>Safe to call from any thread; the first caller performs the registration and every later
	 * caller gets the same snapshot.</p>
	 */
	public static Selection probe() {
		synchronized (LOCK) {
			if (probeStarted) {
				return probed;
			}
			probeStarted = true;
			probed = probeOnce();
			return probed;
		}
	}

	private static Selection probeOnce() {
		Path library = resolveLibrary();
		if (library == null) {
			return Selection.cpu("the WebGPU plugin is not installed beside the bundled runtime");
		}
		OnnxRuntimeApi api = OnnxRuntimeApi.bind();
		try {
			MemorySegment env = api.createEnv("gravaai-webgpu-ep");
			api.registerExecutionProviderLibrary(env, REGISTRATION_NAME, library);
			List<OnnxEpDevice> devices = api.epDevices(env);
			Selection selection = OnnxProviderSelection.select(devices);
			if (!selection.provider().hardware()) {
				// Nothing usable: release the keeper so the plugin is unregistered again and no
				// environment is left holding a device list nothing will use.
				api.close();
				LOG.log(System.Logger.Level.INFO, "WebGPU EP registered but no GPU device: "
						+ selection.detail());
				return selection;
			}
			keeper = api;
			keeperEnv = env;
			LOG.log(System.Logger.Level.INFO,
					() -> "WebGPU EP active on " + selection.provider().label());
			return selection;
		} catch (RuntimeException | UnsatisfiedLinkError error) {
			// Missing loader, version mismatch, driver refusal: CPU only, never a crash. Close the
			// environment so a failed registration does not leave an OrtEnv behind.
			try {
				api.close();
			} catch (RuntimeException ignored) {
				// Releasing a half-initialized environment must not mask the real reason.
			}
			LOG.log(System.Logger.Level.INFO,
					"WebGPU EP unavailable, using the CPU provider: " + error);
			return Selection.cpu(error.getMessage() == null ? error.toString() : error.getMessage());
		}
	}

	/**
	 * The plugin library beside the loaded runtime, or null when it was not staged (a build made
	 * before the plugin existed, or a checkout that skipped the download).
	 */
	private static @Nullable Path resolveLibrary() {
		Path directory;
		try {
			directory = OnnxLibs.ensureLoadedDirectory();
		} catch (RuntimeException error) {
			// The runtime itself is missing: let the caller's CPU open() report that instead.
			return null;
		}
		Path library = directory.resolve(LIBRARY_NAME);
		return Files.isRegularFile(library) ? library : null;
	}

	/** The keeper environment's native handle, for diagnostics; null until a GPU probe succeeds. */
	static Optional<MemorySegment> keeperEnv() {
		synchronized (LOCK) {
			return Optional.ofNullable(probeStarted ? keeperEnv : null);
		}
	}

	/**
	 * Forgets the probe result and releases the keeper. Only for tests: a process that released the
	 * keeper could not re-register the plugin reliably (see the class note), so production code
	 * never calls this.
	 */
	static void resetForTests() {
		synchronized (LOCK) {
			OnnxRuntimeApi toClose = keeper;
			keeper = null;
			keeperEnv = null;
			probed = null;
			probeStarted = false;
			if (toClose != null) {
				toClose.close();
			}
		}
	}
}
