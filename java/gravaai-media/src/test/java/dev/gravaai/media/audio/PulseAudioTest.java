package dev.gravaai.media.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** PulseAudio discovery and the libpulse-simple record stream (plan §5.6.2, §5.6.9). */
class PulseAudioTest {

	@Test
	@DisplayName("parses the pactl JSON source list")
	void parsesPactlJson() {
		String json = "[{\"index\":1,\"name\":\"alsa_output.speakers.monitor\","
				+ "\"description\":\"Monitor of Speakers\",\"properties\":{\"a\":\"[1,2]\"}},"
				+ "{\"index\":2,\"name\":\"alsa_input.mic\",\"description\":\"Microphone\"}]";
		List<PulseSource> sources = PulseAudio.parseSources(json);
		assertEquals(2, sources.size());
		assertEquals("alsa_output.speakers.monitor", sources.get(0).name());
		assertEquals("Monitor of Speakers", sources.get(0).description());
		assertEquals("Microphone", sources.get(1).description());
	}

	@Test
	@DisplayName("ignores malformed pactl entries")
	void ignoresMalformedEntries() {
		List<PulseSource> sources = PulseAudio.parseSources(
				"[{\"description\":\"no name\"}, 42, \"text\", {\"name\":\"valid\"}]");
		assertEquals(1, sources.size());
		assertEquals("valid", sources.get(0).name());
		assertEquals("valid", sources.get(0).description());
	}

	@Test
	@DisplayName("rejects a blank source name")
	void rejectsBlankSourceName() {
		assertThrows(IllegalArgumentException.class, () -> PulseAudio.openRecord("  "));
	}

	@Test
	@DisplayName("lists real PulseAudio sources")
	void listsRealSources() {
		List<PulseSource> sources = PulseAudio.listSources();
		Assumptions.assumeFalse(sources.isEmpty(), "PulseAudio is not running");
		for (PulseSource source : sources) {
			assertFalse(source.name().isBlank());
			assertFalse(source.description().isBlank());
		}
	}

	@Test
	@Timeout(20)
	@DisplayName("records mono float32 from a real source")
	void recordsFromASource() {
		List<PulseSource> sources = PulseAudio.listSources();
		Assumptions.assumeFalse(sources.isEmpty(), "PulseAudio is not running");
		PulseSource source = sources.get(0);
		try (PulseRecordStream stream = PulseAudio.openRecord(source.name())) {
			float[] samples = new float[480];
			int read = stream.read(samples);
			Assumptions.assumeTrue(read > 0, "source produced no audio");
			assertEquals(480, read);
			for (float sample : samples) {
				assertTrue(Float.isFinite(sample), "non-finite sample from " + source.name());
			}
		} catch (IllegalStateException unavailable) {
			Assumptions.abort("cannot record from " + source.name() + ": " + unavailable.getMessage());
		}
	}
}
