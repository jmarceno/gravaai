package dev.gravaai.services.privacy;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * The GLiNER asset auto masking downloads on first use, pinned by URL and SHA-256 (the
 * {@link dev.gravaai.services.meeting.MeetingAssets} pattern).
 *
 * <p><b>fp16, not int8.</b> The research expected the ~300 MB int8 export to be the shipped
 * asset. Measured against the real export, int8 returns near-uniform scores and detects nothing at
 * any threshold — the quantized-GLiNER failure the research flagged — while the fp16 export is
 * correct and runs at ~120 ms per 384-token window. The extra ~280 MB only costs disk, once, and
 * buys a working detector, so fp16 is the pin.</p>
 *
 * <p>The model is split across three files: the graph, the tokenizer (the hand-rolled Java
 * tokenizer loads it) and the GLiNER config the labels were validated against. They live in one
 * directory so a half-finished download can never be mistaken for a usable model.</p>
 */
public final class PrivacyAssets {

	/** Directory name under the profile, next to {@code whisper/} and {@code meeting/}. */
	public static final String PRIVACY_DIRECTORY_NAME = "privacy";

	private final Path privacyDir;
	private final Path modelsDir;

	public PrivacyAssets(Path userDataDir) {
		this.privacyDir = userDataDir.resolve(PRIVACY_DIRECTORY_NAME);
		this.modelsDir = privacyDir.resolve("models");
	}

	public Path privacyDir() {
		return privacyDir;
	}

	public Path modelsDir() {
		return modelsDir;
	}

	/** The directory {@code GlinerPiiDetector.open} is pointed at. */
	public Path glinerModelDir() {
		return modelsDir.resolve("gliner-multi-pii-v1");
	}

	/**
	 * One file of the GLiNER asset: where it lands, where it comes from, and what it must hash to.
	 *
	 * @param fileName name inside {@link #glinerModelDir()}
	 * @param url      HuggingFace resolve URL (the fp16 ONNX export of the PII checkpoint)
	 * @param sha256   pinned hash of the exact bytes served at {@code url}
	 * @param sizeBytes expected size, for the download UI
	 */
	public record AssetFile(String fileName, String url, String sha256, long sizeBytes) {

		public Path path(Path modelDir) {
			return modelDir.resolve(fileName);
		}
	}

	private static final String BASE =
			"https://huggingface.co/onnx-community/gliner_multi_pii-v1/resolve/main";

	/** The three files the detector needs, in download order (graph last: it is the big one). */
	public java.util.List<AssetFile> files() {
		return java.util.List.of(
				new AssetFile("model.onnx", BASE + "/onnx/model_fp16.onnx",
						"e756837e13d16ef832f6cd9fca473c2c3dd1eb232cab13e6443d958d608e797b",
						579_717_643L),
				new AssetFile("tokenizer.json", BASE + "/tokenizer.json",
						TOKENIZER_SHA256, 16_331_948L),
				new AssetFile("gliner_config.json", BASE + "/gliner_config.json",
						GLINER_CONFIG_SHA256, 732L));
	}

	/** sha256 of the checkpoint's {@code tokenizer.json} (16,331,948 bytes). */
	public static final String TOKENIZER_SHA256 =
			"914bd3c8fb7b525af9e23b60d0ec7b1248ddb2b99014efd9c02ebeb022f8cab7";
	/** sha256 of the checkpoint's {@code gliner_config.json} (732 bytes). */
	public static final String GLINER_CONFIG_SHA256 =
			"69e141f7fe1864e0d81ab0e542c68387d588db62393bcd93b471d54dcf0f5c16";

	/** Whether every file is present; the detector is only opened when this is true. */
	public boolean isInstalled() {
		Path dir = glinerModelDir();
		for (AssetFile file : files()) {
			if (!java.nio.file.Files.isRegularFile(file.path(dir))) {
				return false;
			}
		}
		return true;
	}

	/** The first file that is missing, or {@code null} when the asset is complete. */
	public @Nullable AssetFile missingFile() {
		Path dir = glinerModelDir();
		for (AssetFile file : files()) {
			if (!java.nio.file.Files.isRegularFile(file.path(dir))) {
				return file;
			}
		}
		return null;
	}
}
