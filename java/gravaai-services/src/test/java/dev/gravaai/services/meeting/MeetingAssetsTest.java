package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pinned meeting asset tables (electron/ipc/meeting/assets.ts). */
class MeetingAssetsTest {

	@Test
	@DisplayName("lists the whisper models in menu order")
	void listsWhisperModelsInMenuOrder() {
		MeetingAssets assets = new MeetingAssets(Path.of("/tmp/profile"));
		assertEquals(List.of("large-v3-turbo-q5_0", "small"),
				List.copyOf(assets.whisperModels().keySet()));
		assertEquals(Path.of("/tmp/profile/whisper/ggml-small.bin"),
				assets.whisperModels().get("small").path());
	}

	@Test
	@DisplayName("lists the local models in menu order")
	void listsLocalModelsInMenuOrder() {
		MeetingAssets assets = new MeetingAssets(Path.of("/tmp/profile"));
		assertEquals(List.of("qwen3.5-2b", "qwen3.5-4b"),
				List.copyOf(assets.localLlmModels().keySet()));
	}

	@Test
	@DisplayName("lists every downloadable asset once")
	void listsEveryAssetOnce() {
		MeetingAssets assets = new MeetingAssets(Path.of("/tmp/profile"));
		assertEquals(List.of("llama-runtime", "large-v3-turbo-q5_0", "small", "whisper-vad",
				"qwen3.5-2b", "qwen3.5-4b"),
				assets.all().stream().map(MeetingAssets.MeetingAsset::id).toList());
		assertTrue(assets.all().stream().allMatch(asset -> asset.sha256().length() == 64));
	}

	@Test
	@DisplayName("resolves the llama-server binary inside the pinned release")
	void resolvesLlamaServerBinary() {
		MeetingAssets assets = new MeetingAssets(Path.of("/tmp/profile"));
		assertEquals(Path.of("/tmp/profile/meeting/llama/b11829/llama-b11829/llama-server"),
				assets.llamaServerPath());
		assertEquals(Path.of("/tmp/profile/meeting/llama/b11829/.installed"),
				MeetingAssets.installedMarker(assets.llamaRuntime()));
	}

	@Test
	@DisplayName("finds assets by id")
	void findsAssetsById() {
		MeetingAssets assets = new MeetingAssets(Path.of("/tmp/profile"));
		assertTrue(assets.find("small").isPresent());
		assertTrue(assets.find("nope").isEmpty());
	}
}
