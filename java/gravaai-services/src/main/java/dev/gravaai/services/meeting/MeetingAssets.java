package dev.gravaai.services.meeting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything meeting mode downloads on first use: whisper models, the local LLM runtime
 * (llama.cpp's prebuilt server) and the GGUF models. Pinned URLs and SHA-256 so installs are
 * reproducible; bump the hash with the URL (electron/ipc/meeting/assets.ts).
 */
public final class MeetingAssets {

	/** llama.cpp release pinned by the Electron app. */
	public static final String LLAMA_RELEASE = "b11829";

	/**
	 * A downloadable asset.
	 *
	 * @param archive null for plain files, "tar.gz" for archives that extract into {@code path}
	 */
	public record MeetingAsset(String id, String label, String url, String sha256, long sizeBytes,
			Path path, String archive) {

		public boolean isArchive() {
			return archive != null;
		}
	}

	private final Path meetingDir;
	private final Path modelsDir;
	private final Path llamaDir;
	private final Path whisperModelDir;

	/** @param userDataDir the Gravaai profile directory (plan §5.2) */
	public MeetingAssets(Path userDataDir) {
		this.meetingDir = userDataDir.resolve("meeting");
		this.modelsDir = meetingDir.resolve("models");
		this.llamaDir = meetingDir.resolve("llama");
		this.whisperModelDir = userDataDir.resolve("whisper");
	}

	public Path meetingDir() {
		return meetingDir;
	}

	public Path modelsDir() {
		return modelsDir;
	}

	/** The Vulkan build also carries the CPU backends and falls back to them without a GPU. */
	public MeetingAsset llamaRuntime() {
		return new MeetingAsset(
				"llama-runtime",
				"llama.cpp " + LLAMA_RELEASE + " (Vulkan)",
				"https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_RELEASE
						+ "/llama-" + LLAMA_RELEASE + "-bin-ubuntu-vulkan-x64.tar.gz",
				"656bd1425bdde09565f2191c2a62940a1992441497fc5fb5fd45cdfa337e621f",
				34_989_558L,
				llamaDir.resolve(LLAMA_RELEASE),
				"tar.gz");
	}

	/** The whisper models a meeting can use, in menu order. */
	public Map<String, MeetingAsset> whisperModels() {
		Map<String, MeetingAsset> models = new LinkedHashMap<>();
		models.put("large-v3-turbo-q5_0", new MeetingAsset(
				"large-v3-turbo-q5_0",
				"Whisper large-v3-turbo (q5)",
				"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
				"394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
				574_041_195L,
				whisperModelDir.resolve("ggml-large-v3-turbo-q5_0.bin"),
				null));
		models.put("small", new MeetingAsset(
				"small",
				"Whisper small",
				"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
				"1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
				487_601_967L,
				whisperModelDir.resolve("ggml-small.bin"),
				null));
		return models;
	}

	/** Silero voice detection: whisper only decodes speech, so silence cannot turn into invented text. */
	public MeetingAsset whisperVadModel() {
		return new MeetingAsset(
				"whisper-vad",
				"Silero voice detection",
				"https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
				"29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf",
				885_098L,
				whisperModelDir.resolve("ggml-silero-v5.1.2.bin"),
				null);
	}

	/** The GGUF models the local summarizer can load. */
	public Map<String, MeetingAsset> localLlmModels() {
		Map<String, MeetingAsset> models = new LinkedHashMap<>();
		models.put("qwen3.5-2b", new MeetingAsset(
				"qwen3.5-2b",
				"Qwen3.5 2B (Q4_K_M)",
				"https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
				"aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
				1_280_835_840L,
				modelsDir.resolve("Qwen3.5-2B-Q4_K_M.gguf"),
				null));
		models.put("qwen3.5-4b", new MeetingAsset(
				"qwen3.5-4b",
				"Qwen3.5 4B (Q4_K_M)",
				"https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
				"00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
				2_740_937_888L,
				modelsDir.resolve("Qwen3.5-4B-Q4_K_M.gguf"),
				null));
		return models;
	}

	/** Every asset the settings screen lists, in the same order as Electron. */
	public List<MeetingAsset> all() {
		List<MeetingAsset> assets = new ArrayList<>();
		assets.add(llamaRuntime());
		assets.addAll(whisperModels().values());
		assets.add(whisperVadModel());
		assets.addAll(localLlmModels().values());
		return List.copyOf(assets);
	}

	public Optional<MeetingAsset> find(String id) {
		for (MeetingAsset asset : all()) {
			if (asset.id().equals(id)) {
				return Optional.of(asset);
			}
		}
		return Optional.empty();
	}

	/** llama-server binary inside the extracted archive. */
	public Path llamaServerPath() {
		return llamaRuntime().path().resolve("llama-" + LLAMA_RELEASE).resolve("llama-server");
	}

	/** Where an archive records that it was installed. */
	public static Path installedMarker(MeetingAsset asset) {
		return asset.path().resolve(".installed");
	}
}
