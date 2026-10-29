package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from electron/ipc/meeting/settings.test.ts. */
class MeetingSettingsTest {

	private final MeetingAssets assets = new MeetingAssets(Path.of("/tmp/gravaai-settings-test"));

	@Test
	@DisplayName("returns the defaults for missing or broken input")
	void defaultsForMissingOrBrokenInput() {
		assertEquals(MeetingSettings.DEFAULT, MeetingSettings.normalize(null, assets));
		assertEquals(MeetingSettings.DEFAULT, MeetingSettings.normalize("nope", assets));
	}

	@Test
	@DisplayName("keeps valid values and replaces invalid ones")
	void keepsValidValuesReplacesInvalidOnes() {
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("summarizationService", "api");
		raw.put("localModel", "qwen3.5-4b");
		raw.put("whisperModel", "huge");
		raw.put("apiBaseUrl", "ftp://example");
		raw.put("apiChatCapabilities", List.of("images"));
		raw.put("apiTimeoutMinutes", 0);
		raw.put("autoTitle", false);
		raw.put("unknown", 1);
		MeetingSettings settings = MeetingSettings.normalize(raw, assets);
		assertEquals(MeetingServiceKind.API, settings.summarizationService());
		assertEquals("qwen3.5-4b", settings.localModel());
		assertEquals(MeetingSettings.DEFAULT.whisperModel(), settings.whisperModel());
		assertEquals(MeetingSettings.DEFAULT.apiBaseUrl(), settings.apiBaseUrl());
		// Text is always required: a provider that takes images still takes text.
		assertEquals(List.of(SummarizerCapability.TEXT), settings.apiChatCapabilities());
		assertEquals(MeetingSettings.DEFAULT.apiTimeoutMinutes(), settings.apiTimeoutMinutes());
		assertFalse(settings.autoTitle());
	}

	@Test
	@DisplayName("turns saved capability strings back into capabilities")
	void parsesWireCapabilities() {
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("apiChatCapabilities", List.of("text", "images"));
		MeetingSettings settings = MeetingSettings.normalize(raw, assets);
		assertEquals(List.of(SummarizerCapability.TEXT, SummarizerCapability.IMAGES),
				settings.apiChatCapabilities());
	}

	@Test
	@DisplayName("keeps every field through repeated edits, the path that once crashed on capabilities")
	void repeatedEditsRoundTrip() {
		MeetingSettingsService service = new MeetingSettingsService(
				new MeetingTestSupport.FakeSettingsStore(), new MeetingTestSupport.FakeApiKeyStore(), assets);
		service.update(s -> s.withApiChatCapabilities(
				List.of(SummarizerCapability.TEXT, SummarizerCapability.IMAGES)));
		service.update(s -> s.withLanguage("pt"));
		MeetingSettings settings = service.update(s -> s.withLanguage("auto"));
		assertEquals("auto", settings.language());
		assertEquals(List.of(SummarizerCapability.TEXT, SummarizerCapability.IMAGES),
				settings.apiChatCapabilities());
		assertEquals(settings, service.read());
	}

	@Test
	@DisplayName("runs edited settings through the same checks as a stored file")
	void editsAreNormalized() {
		MeetingSettingsService service = new MeetingSettingsService(
				new MeetingTestSupport.FakeSettingsStore(), new MeetingTestSupport.FakeApiKeyStore(), assets);
		MeetingSettings settings = service.update(s -> s.withApiTimeoutMinutes(0).withLanguage(" "));
		assertEquals(MeetingSettings.DEFAULT.apiTimeoutMinutes(), settings.apiTimeoutMinutes());
		assertEquals(MeetingSettings.DEFAULT.language(), settings.language());
	}

	@Test
	@DisplayName("leaves room for the prompt and the answer")
	void transcriptCharBudgetLeavesRoom() {
		assertEquals((16_384 - 3_500) * 3, Summarizer.transcriptCharBudget(16_384));
		assertEquals(4_000, Summarizer.transcriptCharBudget(2_000));
	}
}
