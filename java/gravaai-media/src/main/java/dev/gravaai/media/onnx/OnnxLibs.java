package dev.gravaai.media.onnx;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.gravaai.platform.nativehelpers.NativeHelpers;

/**
 * Loads the bundled ONNX Runtime CPU shared library for the webcam background-blur segmenter.
 * Mirrors {@code FfmpegLibs}: the library is bound to the process through
 * {@link SymbolLookup#libraryLookup(Path, Arena)} and registered with the class loader so the
 * jextract-generated bindings resolve their symbols, and the wrappers call
 * {@link #ensureLoaded()} before their first native call. Loading is one-shot; a second,
 * different directory is rejected because a process can only bind one ONNX Runtime build.
 */
public final class OnnxLibs {

	private static final System.Logger LOG = System.getLogger(OnnxLibs.class.getName());

	/** The single shared library (CPU build; no CUDA/OpenVINO providers are bundled). */
	public static final List<String> LIBRARIES = List.of("libonnxruntime.so");

	private static boolean loaded;
	/** The directory the library was loaded from; {@code null} until {@link #load(Path)}. */
	private static @Nullable Path libraryDir;

	private OnnxLibs() {}

	/**
	 * Loads {@code libonnxruntime.so} from a directory containing the bundled library.
	 *
	 * @param onnxRuntimeLibDir directory with libonnxruntime.so
	 * @throws IllegalStateException when the library is missing
	 */
	public static synchronized void load(Path onnxRuntimeLibDir) {
		Path dir = onnxRuntimeLibDir.toAbsolutePath().normalize();
		if (loaded) {
			if (dir.equals(libraryDir)) {
				return;
			}
			throw new IllegalStateException("ONNX Runtime is already loaded from " + libraryDir
					+ "; cannot also load " + dir);
		}
		for (String library : LIBRARIES) {
			Path file = dir.resolve(library);
			if (!Files.isRegularFile(file)) {
				throw new IllegalStateException("ONNX Runtime shared library not found in " + dir
						+ ": " + file + " (run java/native/build-onnxruntime.sh)");
			}
			// libraryLookup binds the shared object to the process and gives the generated
			// bindings access to its symbols; System.load additionally registers it with this
			// class loader (SymbolLookup.loaderLookup()), the pattern FfmpegLibs uses.
			SymbolLookup.libraryLookup(file, Arena.global());
			System.load(file.toAbsolutePath().toString());
		}
		libraryDir = dir;
		loaded = true;
		LOG.log(System.Logger.Level.DEBUG, () -> "ONNX Runtime loaded from " + libraryDir);
	}

	/** Loads the library from {@link #resolveLibraryDir()} unless already loaded. */
	public static synchronized void ensureLoaded() {
		if (!loaded) {
			load(resolveLibraryDir());
		}
	}

	/**
	 * The directory the runtime was loaded from, loading it first when needed.
	 *
	 * <p>The WebGPU plugin lives beside {@code libonnxruntime.so}, and the plugin probe runs before
	 * any session exists, so it needs the same directory {@link #ensureLoaded()} resolved.</p>
	 */
	public static synchronized Path ensureLoadedDirectory() {
		ensureLoaded();
		Path dir = libraryDir;
		if (dir == null) {
			throw new IllegalStateException("ONNX Runtime library directory is unknown after loading");
		}
		return dir;
	}

	/** The directory the library was loaded from, or {@code null} before loading. */
	public static synchronized @Nullable Path libraryDir() {
		return libraryDir;
	}

	/**
	 * Resolves the ONNX Runtime library directory: {@code -Dgravaai.onnxruntime.lib.dir=<dir>}
	 * (tests, tooling), the bundled native payload root ({@code onnxruntime/lib}), or the
	 * development {@code native/build/onnxruntime/lib} layout (Maven Surefire, IDE runs).
	 */
	public static Path resolveLibraryDir() {
		String explicit = System.getProperty("gravaai.onnxruntime.lib.dir");
		if (explicit != null && !explicit.isBlank()) {
			return Path.of(explicit);
		}
		Path resolved = NativeHelpers.payloadDirectory("onnxruntime/lib");
		if (resolved != null) {
			return resolved;
		}
		throw new IllegalStateException(
				"ONNX Runtime shared library not found. Run java/native/build-onnxruntime.sh, then"
						+ " pass -Dgravaai.onnxruntime.lib.dir=<dir> or -Dgravaai.nativeDir=<dir>."
						+ " Tried: " + NativeHelpers.nativeDir().resolve("onnxruntime/lib") + " and "
						+ NativeHelpers.developmentPayloadCandidates("onnxruntime/lib"));
	}

	/**
	 * Resolves the bundled selfie-segmenter model file (the fast path). The model ships in the
	 * native payload ({@code models/segmentation/}, staged by build-onnxruntime.sh like the
	 * rnnoise model); the same dev-layout fallbacks as {@link #resolveLibraryDir()} apply.
	 */
	public static Path resolveModelFile() {
		return resolveSegmentationModel("selfie-segmenter.onnx");
	}

	/**
	 * Resolves the bundled MODNet model file (the slow path, used for paused frames, scrubs and
	 * exports). Same payload layout and fallbacks as {@link #resolveModelFile()}.
	 */
	public static Path resolveModnetModelFile() {
		return resolveSegmentationModel("modnet.onnx");
	}

	/**
	 * Resolves the bundled YuNet face-detection model (auto masking). Lives in
	 * {@code models/detection/} in the native payload.
	 */
	public static Path resolveFaceModelFile() {
		return resolveBundledModel("detection", "face_detection_yunet_2023mar.onnx");
	}

	/** Resolves the bundled PP-OCR detection model (auto-mask screen text). */
	public static Path resolveOcrDetModelFile() {
		return resolveBundledModel("detection", "ch_PP-OCRv4_det_infer.onnx");
	}

	/** Resolves the bundled PP-OCR recognition model (auto-mask screen text). */
	public static Path resolveOcrRecModelFile() {
		return resolveBundledModel("detection", "ch_PP-OCRv4_rec_infer.onnx");
	}

	private static Path resolveSegmentationModel(String fileName) {
		return resolveBundledModel("segmentation", fileName);
	}

	/**
	 * Resolves a model under {@code models/<kind>/} in the native payload, falling back to the
	 * development {@code native/build/models/<kind>/} layout of the Maven/IDE runs.
	 */
	private static Path resolveBundledModel(String kind, String fileName) {
		String relative = "models/" + kind + "/" + fileName;
		Path resolved = NativeHelpers.payloadFile(relative);
		if (resolved != null) {
			return resolved;
		}
		throw new IllegalStateException(
				fileName + " not found. Run java/native/build-onnxruntime.sh, then pass"
						+ " -Dgravaai.nativeDir=<dir> (the models live in models/" + kind + ")."
						+ " Tried: " + NativeHelpers.nativeDir().resolve(relative) + " and "
						+ NativeHelpers.developmentPayloadCandidates(relative));
	}
}
