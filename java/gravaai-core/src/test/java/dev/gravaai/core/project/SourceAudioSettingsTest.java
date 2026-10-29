package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Typed source-audio settings (plan P2.7): parsing/clamping/defaults and raw round-trip parity. */
class SourceAudioSettingsTest {

	private static JsonNode json(String text) {
		return Json.parseJsonWithByteOrderMark(text);
	}

	@Test
	@DisplayName("parses known tracks with defaults and clamps the volume")
	void parsesTracks() {
		SourceAudioTrackSettings settings = SourceAudioSettings.parse(json("""
				{"mic":{"volume":0.5,"normalize":true},
				 "system":{"volume":5},
				 "mixed":{"normalize":false},
				 "custom":{"volume":-1}}
				"""));
		assertEquals(4, settings.tracks().size());
		assertEquals(new SourceAudioTrackSettings.Track(0.5, true), settings.track("mic"));
		assertEquals(new SourceAudioTrackSettings.Track(2.0, false), settings.track("system"));
		assertEquals(new SourceAudioTrackSettings.Track(1.0, false), settings.track("mixed"));
		assertEquals(new SourceAudioTrackSettings.Track(0.0, false), settings.track("custom"));
	}

	@Test
	@DisplayName("a clip without settings parses to empty and resolves to unity gain")
	void defaultsToUnityGain() {
		assertTrue(SourceAudioSettings.parse(null).isEmpty());
		assertTrue(SourceAudioSettings.parse(json("\"nope\"")).isEmpty());
		assertEquals(1.0, SourceAudioSettings.resolveGain(SourceAudioTrackSettings.EMPTY, "mic"),
				0.001);
		assertEquals(1.0, SourceAudioSettings.resolveGain(null, "mixed"), 0.001);
	}

	@Test
	@DisplayName("resolves the exporter gain with the normalize multiplier")
	void resolvesGain() {
		SourceAudioTrackSettings settings = SourceAudioSettings.parse(json("""
				{"mic":{"volume":0.5,"normalize":true},
				 "system":{"volume":0.5,"normalize":false},
				 "mixed":{"volume":2,"normalize":true}}
				"""));
		assertEquals(0.675, SourceAudioSettings.resolveGain(settings, "mic"), 0.001);
		assertEquals(0.5, SourceAudioSettings.resolveGain(settings, "system"), 0.001);
		assertEquals(2.0, SourceAudioSettings.resolveGain(settings, "mixed"), 0.001);
	}

	@Test
	@DisplayName("merge preserves unknown keys at clip and track level")
	void mergePreservesUnknownKeys() {
		ObjectNode raw = (ObjectNode) json("""
				{"clip-1":{"mic":{"volume":0.5,"unknownTrackField":7},"customTrack":{"x":1}},
				 "clip-2":{"mixed":{"volume":0.2}},"unknownClip":{"keep":true}}
				""");
		SourceAudioTrackSettings settings = SourceAudioSettings.parse(raw.get("clip-1"));
		ObjectNode merged = SourceAudioSettings.merge(raw, "clip-1", settings);

		assertEquals(0.5, merged.path("clip-1").path("mic").path("volume").doubleValue(), 0.001);
		assertFalse(merged.path("clip-1").path("mic").path("normalize").booleanValue());
		assertEquals(7, merged.path("clip-1").path("mic").path("unknownTrackField").intValue());
		assertEquals(1, merged.path("clip-1").path("customTrack").path("x").intValue());
		assertTrue(merged.has("unknownClip"));
	}

	@Test
	@DisplayName("merge writes only volume/normalize and keeps the raw input untouched")
	void mergeWritesKnownFieldsOnly() {
		ObjectNode raw = (ObjectNode) json("""
				{"clip-1":{"mic":{"volume":0.5,"normalize":true,"extra":"keep"}}}
				""");
		SourceAudioTrackSettings settings = SourceAudioTrackSettings.EMPTY
				.withTrack(SourceAudioTrackSettings.MIXED,
						new SourceAudioTrackSettings.Track(1.5, false));
		ObjectNode merged = SourceAudioSettings.merge(raw, "clip-1", settings);

		assertEquals(0.5, merged.path("clip-1").path("mic").path("volume").doubleValue(), 0.001);
		assertEquals("keep", merged.path("clip-1").path("mic").path("extra").asString());
		assertEquals(1.5, merged.path("clip-1").path("mixed").path("volume").doubleValue(), 0.001);
		assertFalse(merged.path("clip-1").path("mixed").path("normalize").booleanValue());
		assertFalse(raw.path("clip-1").has("mixed"));
	}

	@Test
	@DisplayName("parseAll exposes the typed map and the editor state accessor uses it")
	void parsesWholeMap() {
		ObjectNode raw = (ObjectNode) json("""
				{"clip-1":{"mic":{"volume":0.4}},"clip-2":{}}
				""");
		Map<String, SourceAudioTrackSettings> parsed = SourceAudioSettings.parseAll(raw);
		assertEquals(2, parsed.size());
		assertEquals(0.4, parsed.get("clip-1").track("mic").volume(), 0.001);
		assertTrue(parsed.get("clip-2").isEmpty());
	}
}
