package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.project.ClickSoundStyle;
import dev.gravaai.media.audio.ClickSounds;

/** {@link ClickSoundTrack}: placement, overlap clamping and buffer hygiene against a real asset. */
@Timeout(15)
class ClickSoundTrackTest {

	private static final int CHUNK_FRAMES = PlaybackAudioProducer.DEFAULT_CHUNK_FRAMES;

	private static float[] pcm() {
		float[] pcm = ClickSounds.pcm(ClickSoundStyle.CRISP);
		assertNotNull(pcm, "the bundled crisp click must decode");
		assertTrue(pcm.length > 2 * 100, "the click should be a real transient");
		return pcm;
	}

	private static float peak(float[] interleaved, int frames) {
		float peak = 0;
		for (float sample : java.util.Arrays.copyOf(interleaved, frames * 2)) {
			peak = Math.max(peak, Math.abs(sample));
		}
		return peak;
	}

	@Test
	@DisplayName("the sound lands exactly at the hit time, scaled by volume")
	void placesTheSoundAtTheHit() {
		float[] pcm = pcm();
		ClickSoundTrack track = new ClickSoundTrack(ClickSoundStyle.CRISP, 0.5,
				new long[] {1_000});
		float[] chunk = new float[CHUNK_FRAMES * 2];
		track.mix(chunk, CHUNK_FRAMES, 1.0);

		// The chunk starts at the hit: the first frame is the first click sample at half gain.
		assertEquals(pcm[0] * 0.5f, chunk[0], 1e-6);
		assertEquals(pcm[1] * 0.5f, chunk[1], 1e-6);
	}

	@Test
	@DisplayName("a hit after the chunk leaves the buffer untouched")
	void futureHitsLeaveTheBufferAlone() {
		float[] filled = new float[CHUNK_FRAMES * 2];
		java.util.Arrays.fill(filled, 0.25f);
		new ClickSoundTrack(ClickSoundStyle.CRISP, 1, new long[] {5_000})
				.mix(filled, CHUNK_FRAMES, 0.0);
		for (float sample : filled) {
			assertEquals(0.25f, sample, "no click residue outside the hit window");
		}
	}

	@Test
	@DisplayName("overlapping tails clamp to ±1 instead of wrapping around")
	void denseHitsClamp() {
		float[] pcm = pcm();
		ClickSoundTrack track = new ClickSoundTrack(ClickSoundStyle.CRISP, 1,
				new long[] {0, 1, 2, 3, 4, 5});
		float[] chunk = new float[CHUNK_FRAMES * 2];
		track.mix(chunk, CHUNK_FRAMES, 0.0);

		for (float sample : chunk) {
			assertTrue(sample <= 1f + 1e-6 && sample >= -1f - 1e-6, "clamped samples");
		}
		assertTrue(peak(chunk, CHUNK_FRAMES) > Math.abs(pcm[0]), "overlaps add up to the clamp");
	}

	@Test
	@DisplayName("mixing adds onto existing audio and poisons nothing past the tail")
	void addsToAudioWithoutPoisoning() {
		float[] pcm = pcm();
		int soundFrames = pcm.length / 2;
		float[] filled = new float[(CHUNK_FRAMES + soundFrames) * 2];
		java.util.Arrays.fill(filled, 0.25f);
		new ClickSoundTrack(ClickSoundStyle.CRISP, 1, new long[] {0})
				.mix(filled, CHUNK_FRAMES + soundFrames, 0.0);

		assertTrue(peak(filled, soundFrames) > 0.5f, "the click adds onto the audio");
		assertEquals(0.25f + pcm[0], filled[0], 1e-6, "the click sums onto the original sample");
		// A click is an AC waveform: it dips below the original by design. The hygiene that
		// matters is that clamping keeps every sample finite and inside ±1.
		for (int index = 0; index < soundFrames * 2; index++) {
			float sample = filled[index];
			assertTrue(Float.isFinite(sample) && sample >= -1f - 1e-6 && sample <= 1f + 1e-6,
					"clamped within ±1, got " + sample + " at " + index);
		}
		// One frame past the tail the audio is untouched: no buffer poisoning.
		assertEquals(0.25f, filled[(soundFrames + 1) * 2], 1e-6);
	}
}
