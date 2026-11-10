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

	@Test
	@DisplayName("parses the cleanup of a track and inherits defaults for a missing one")
	void parsesCleanup() {
		SourceAudioTrackSettings settings = SourceAudioSettings.parse(json("""
				{"mic":{"volume":0.5,"noiseReduction":"balanced","highPassHz":120,"loudnessLufs":-16},
				 "system":{"volume":1}}
				"""));
		AudioCleanup mic = settings.track("mic").cleanup();
		assertEquals(AudioCleanup.NoiseReduction.BALANCED, mic.noiseReduction());
		assertEquals(120, mic.highPassHz());
		assertEquals(-16, mic.loudnessTargetLufs(), 0.001);
		assertEquals(AudioCleanup.NONE, settings.track("system").cleanup());

		assertEquals(mic, SourceAudioSettings.resolveCleanup(settings, "mic"));
		assertEquals(AudioCleanup.NONE, SourceAudioSettings.resolveCleanup(settings, "mixed"));
		assertEquals(AudioCleanup.NONE, SourceAudioSettings.resolveCleanup(null, "mic"));
	}

	@Test
	@DisplayName("merge writes the cleanup keys and drops them when the track is cleaned off")
	void mergeWritesCleanup() {
		ObjectNode raw = (ObjectNode) json("""
				{"clip-1":{"mic":{"volume":0.5,"normalize":false,"noiseReduction":"light"}}}
				""");
		SourceAudioTrackSettings settings = SourceAudioSettings.parse(raw.get("clip-1"));
		SourceAudioTrackSettings cleaned = SourceAudioSettings.withCleanup(settings, "mic",
				new AudioCleanup(AudioCleanup.NoiseReduction.STRONG, 80, -14));
		ObjectNode merged = SourceAudioSettings.merge(raw, "clip-1", cleaned);
		JsonNode mic = merged.path("clip-1").path("mic");
		assertEquals("strong", mic.path("noiseReduction").asString());
		assertEquals(80, mic.path("highPassHz").intValue());
		assertEquals(-14, mic.path("loudnessLufs").doubleValue(), 0.001);
		assertEquals(0.5, mic.path("volume").doubleValue(), 0.001);

		ObjectNode cleared = SourceAudioSettings.merge(merged, "clip-1",
				SourceAudioSettings.withCleanup(cleaned, "mic", AudioCleanup.NONE));
		JsonNode clearedMic = cleared.path("clip-1").path("mic");
		assertFalse(clearedMic.has("noiseReduction"));
		assertFalse(clearedMic.has("highPassHz"));
		assertFalse(clearedMic.has("loudnessLufs"));
		assertEquals(0.5, clearedMic.path("volume").doubleValue(), 0.001);
	}

	@Test
	@DisplayName("the programme loudness target comes from the first enabled track in priority order")
	void resolvesProgrammeLoudness() {
		SourceAudioTrackSettings settings = SourceAudioSettings.parse(json("""
				{"mic":{"loudnessLufs":-14},"system":{"loudnessLufs":-23}}
				"""));
		assertEquals(-14,
				SourceAudioSettings.resolveProgrammeLoudnessTarget(settings,
						java.util.List.of("mic", "system", "mixed")), 0.001);
		assertEquals(-23,
				SourceAudioSettings.resolveProgrammeLoudnessTarget(settings,
						java.util.List.of("system", "mixed")), 0.001);
		assertEquals(0,
				SourceAudioSettings.resolveProgrammeLoudnessTarget(settings,
						java.util.List.of("mixed")), 0.001);
		assertEquals(0, SourceAudioSettings.resolveProgrammeLoudnessTarget(null,
				java.util.List.of("mic")), 0.001);
	}

	@Test
	@DisplayName("merging default cleanup preserves unknown track keys and an absent node")
	void mergesDefaultCleanup() {
		ObjectNode raw = (ObjectNode) json("""
				{"mic":{"volume":0.4,"muted":false}}
				""");
		ObjectNode merged = SourceAudioSettings.mergeDefaultCleanup(raw, "mic",
				new AudioCleanup(AudioCleanup.NoiseReduction.LIGHT, 0, 0));
		assertEquals("light", merged.path("mic").path("noiseReduction").asString());
		assertEquals(0.4, merged.path("mic").path("volume").doubleValue(), 0.001);
		assertFalse(merged.path("mic").path("muted").booleanValue());
		assertFalse(raw.path("mic").has("noiseReduction"));

		ObjectNode fromNothing = SourceAudioSettings.mergeDefaultCleanup(null, "system",
				new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 120, 0));
		assertEquals(120, fromNothing.path("system").path("highPassHz").intValue());
		assertFalse(fromNothing.path("system").path("loudnessLufs").isNumber());
	}
}
