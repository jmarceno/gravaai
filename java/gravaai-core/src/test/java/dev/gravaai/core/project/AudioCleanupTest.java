package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Edit-time cleanup model: defaults, clamping, JSON round-trip and the stage predicates. */
class AudioCleanupTest {

	private static JsonNode json(String text) {
		return Json.parseJsonWithByteOrderMark(text);
	}

	@Test
	@DisplayName("the default is off on every stage")
	void defaultsOff() {
		AudioCleanup none = AudioCleanup.NONE;
		assertEquals(AudioCleanup.NoiseReduction.OFF, none.noiseReduction());
		assertEquals(0, none.highPassHz());
		assertEquals(0, none.loudnessTargetLufs(), 0.001);
		assertFalse(none.isActive());
		assertFalse(none.hasHighPass());
		assertFalse(none.hasLoudnessTarget());
		assertEquals(none, new AudioCleanup(null, 0, Double.NaN));
	}

	@Test
	@DisplayName("clamps the high-pass and the loudness target, keeping 0 as off")
	void clamps() {
		assertEquals(AudioCleanup.MAX_HIGH_PASS_HZ,
				new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 9_000, 0).highPassHz());
		assertEquals(0, new AudioCleanup(AudioCleanup.NoiseReduction.OFF, -40, 0).highPassHz());
		assertEquals(AudioCleanup.MIN_LOUDNESS_LUFS,
				new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 0, -300).loudnessTargetLufs(),
				0.001);
		assertEquals(AudioCleanup.MAX_LOUDNESS_LUFS,
				new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 0, 30).loudnessTargetLufs(),
				0.001);
		assertEquals(0, AudioCleanup.clampLoudness(0), 0.001);
		assertEquals(0, AudioCleanup.clampLoudness(Double.NaN), 0.001);
	}

	@Test
	@DisplayName("parses the known wire values and falls back on anything else")
	void parsesWireValues() {
		AudioCleanup bundled = AudioCleanup.parse(json("""
				{"noiseReduction":"balanced","highPassHz":120,"loudnessLufs":-16}
				"""));
		assertEquals(AudioCleanup.NoiseReduction.BALANCED, bundled.noiseReduction());
		assertEquals(120, bundled.highPassHz());
		assertEquals(-16, bundled.loudnessTargetLufs(), 0.001);
		assertTrue(bundled.isActive());

		AudioCleanup unknown = AudioCleanup.parse(json("""
				{"noiseReduction":"loud","highPassHz":"high","loudnessLufs":true}
				"""));
		assertEquals(AudioCleanup.NONE, unknown);

		assertEquals(AudioCleanup.NONE, AudioCleanup.parse(null));
		assertEquals(AudioCleanup.NONE, AudioCleanup.parse(json("\"off\"")));
	}

	@Test
	@DisplayName("writes the keys only when the stage is on and removes stale ones")
	void writesAndRemovesKeys() {
		ObjectNode target = (ObjectNode) json("{\"volume\":0.5,\"extra\":\"keep\"}");
		AudioCleanup.NONE.writeTo(target);
		assertFalse(target.has("noiseReduction"));
		assertFalse(target.has("highPassHz"));
		assertFalse(target.has("loudnessLufs"));
		assertEquals("keep", target.path("extra").asString());

		new AudioCleanup(AudioCleanup.NoiseReduction.STRONG, 80, -16).writeTo(target);
		assertEquals("strong", target.path("noiseReduction").asString());
		assertEquals(80, target.path("highPassHz").intValue());
		assertEquals(-16, target.path("loudnessLufs").doubleValue(), 0.001);

		AudioCleanup.NONE.writeTo(target);
		assertFalse(target.has("noiseReduction"));
		assertFalse(target.has("highPassHz"));
		assertFalse(target.has("loudnessLufs"));
		assertEquals(0.5, target.path("volume").doubleValue(), 0.001);
		assertEquals("keep", target.path("extra").asString());
	}

	@Test
	@DisplayName("withX copies keep the other stages")
	void withHelpers() {
		AudioCleanup cleanup = AudioCleanup.NONE
				.withNoiseReduction(AudioCleanup.NoiseReduction.LIGHT)
				.withHighPassHz(120)
				.withLoudnessTargetLufs(AudioCleanup.LOUDNESS_PODCAST_LUFS);
		assertEquals(AudioCleanup.NoiseReduction.LIGHT, cleanup.noiseReduction());
		assertEquals(120, cleanup.highPassHz());
		assertEquals(-16, cleanup.loudnessTargetLufs(), 0.001);
		assertTrue(cleanup.hasLoudnessTarget());
		assertEquals(120, cleanup.withNoiseReduction(AudioCleanup.NoiseReduction.OFF).highPassHz());
		assertEquals(AudioCleanup.NONE, AudioCleanup.off()
				.withNoiseReduction(AudioCleanup.NoiseReduction.OFF));
	}
}
