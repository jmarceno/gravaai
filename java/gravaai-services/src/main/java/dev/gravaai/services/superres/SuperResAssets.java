package dev.gravaai.services.superres;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The optional super-resolution asset the deep-zoom export path downloads on first use, pinned by
 * URL, SHA-256 and size (the {@link dev.gravaai.services.privacy.PrivacyAssets} pattern).
 *
 * <p><b>Real-ESRGAN general x4v3, not the RRDBNet x4plus.</b> Both are 4&times; upscalers, but the
 * full RRDBNet graph is ~67 MB and takes ~480 ms for a 64&nbsp;px tile on this machine's CPU, which
 * at a 384&times;216 deep-zoom window would be tens of seconds per frame. The mobile SRVGGNetCompact
 * variant ({@code x4v3}) is 4.9 MB and runs the same window in ~0.5 s, and on real screen text it
 * recovers nearly all the high-frequency detail the magnification smoothed away: mean gradient
 * energy 311 against 318 for the vector-crisp ground truth, where the current bicubic stretch
 * manages 29.</p>
 *
 * <p><b>fp32, not int8.</b> The int8 dynamic-quantized export is 4&times; <i>slower</i> on this
 * runtime's CPU kernels (1.8 s vs 0.5 s at the same window): the graph is small and bound by
 * dequantize overhead rather than matmul throughput. It is also 1.3 MB against 4.9 MB, so the
 * download is not the argument for it either. fp32 is the pin.</p>
 *
 * <p>The engine is the bundled ONNX Runtime (the same in-process runtime auto-mask and webcam
 * segmentation use), which is the CPU build: there is no CUDA/DirectML provider in the payload, so
 * this pass runs on the CPU regardless of the GPU the compositor draws with.</p>
 */
public final class SuperResAssets {

	/** Directory name under the profile, next to {@code privacy/} and {@code whisper/}. */
	public static final String SUPER_RES_DIRECTORY_NAME = "superres";

	/** The pinned model file name inside {@link #modelDir()}. */
	public static final String MODEL_FILE_NAME = "realesr-general-x4v3.onnx";

	/** The scale the pinned model produces (it is a 4&times; network). */
	public static final int SCALE = 4;

	private final Path superResDir;
	private final Path modelsDir;
	private final java.util.List<AssetFile> files;

	public SuperResAssets(Path userDataDir) {
		this(userDataDir, pinnedFiles());
	}

	/**
	 * Test seam: the same layout with a different pin set, so the installer can be exercised on
	 * fixture bytes instead of the real 4.9 MB model.
	 */
	SuperResAssets(Path userDataDir, java.util.List<AssetFile> files) {
		if (files.isEmpty()) {
			throw new IllegalArgumentException("a super-resolution asset needs at least one file");
		}
		this.superResDir = userDataDir.resolve(SUPER_RES_DIRECTORY_NAME);
		this.modelsDir = superResDir.resolve("models");
		this.files = java.util.List.copyOf(files);
	}

	public Path superResDir() {
		return superResDir;
	}

	public Path modelsDir() {
		return modelsDir;
	}

	/** The directory the engine is pointed at (one model per directory, so a partial install is
	 * never taken for a usable one). */
	public Path modelDir() {
		return modelsDir.resolve("realesr-general-x4v3");
	}

	/** The pinned model file the engine loads. */
	public Path modelFile() {
		return modelDir().resolve(MODEL_FILE_NAME);
	}

	/**
	 * One file of the asset: where it lands, where it comes from and what it must hash to.
	 *
	 * @param fileName name inside {@link #modelDir()}
	 * @param url      HuggingFace resolve URL
	 * @param sha256   pinned hash of the exact bytes served at {@code url}
	 * @param sizeBytes expected size, for the download UI
	 */
	public record AssetFile(String fileName, String url, String sha256, long sizeBytes) {

		public Path path(Path directory) {
			return directory.resolve(fileName);
		}
	}

	private static final String BASE = "https://huggingface.co";

	/**
	 * The pinned model: Real-ESRGAN general x4v3, BSD-3-Clause (the upstream
	 * {@code xinntao/Real-ESRGAN} license, carried by this conversion).
	 */
	public static final String MODEL_SHA256 =
			"1940a93ee08283a0a7286183186357b1688fe9fa8ede74604b424586aaddf112";
	/** Bytes of the pinned fp32 model. */
	public static final long MODEL_SIZE_BYTES = 4_866_417L;

	/** The one file the engine needs. */
	public java.util.List<AssetFile> files() {
		return files;
	}

	private static java.util.List<AssetFile> pinnedFiles() {
		return java.util.List.of(new AssetFile(MODEL_FILE_NAME,
				BASE + "/CoderViking/realesr-general-x4v3-onnx/resolve/main/"
						+ "realesr-general-x4v3.onnx",
				MODEL_SHA256, MODEL_SIZE_BYTES));
	}

	/**
	 * Whether the model is present at its pinned size. Size is checked, not just existence, so a
	 * download truncated by a crash or a full disk reads as missing and triggers a re-fetch instead
	 * of being handed to the engine. The full hash stays the installer's job: hashing 4.9 MB on
	 * every run is cheap, but the engine open is already the gate that matters and the installer
	 * hashes on install, so a per-run re-hash would only repeat it.
	 */
	public boolean isInstalled() {
		for (AssetFile file : files) {
			Path path = file.path(modelDir());
			try {
				if (!Files.isRegularFile(path) || Files.size(path) != file.sizeBytes()) {
					return false;
				}
			} catch (IOException unreadable) {
				return false;
			}
		}
		return true;
	}
}
