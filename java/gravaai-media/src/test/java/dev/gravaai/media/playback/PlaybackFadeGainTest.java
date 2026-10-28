package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipTransition;
import dev.gravaai.core.project.ClipTransitionKind;
import dev.gravaai.core.project.ClipTransitions;

/**
 * Preview fade gain of clip transitions (port of {@code useVideoEditorAudio.ts} applying
 * {@code getFadeGain} to the preview volume, using the core {@link ClipTransitions} math the
 * export envelope is also generated from).
 */
@Timeout(15)
class PlaybackFadeGainTest {

	private static ClipRegion clip(String id, long startMs, long endMs, Long fadeMs) {
		return new ClipRegion(id, startMs, endMs, null, null, null, 1, null, null, null,
				fadeMs == null ? null : new ClipTransition(ClipTransitionKind.FADE_BLACK, fadeMs));
	}

	/** Two clips joined by a 500 ms cut fade; the second ends with a 1000 ms fade-out. */
	private static List<ClipRegion> cutAndOut() {
		return List.of(clip("a", 0, 2_000, 500L), clip("b", 2_000, 4_000, 1_000L));
	}

	@Test
	@DisplayName("a muted clip plays silent in the preview (clipAudio.ts isClipMuted)")
	void mutedClipIsSilent() {
		ClipRegion muted = new ClipRegion("a", 0, 2_000, null, null, null, 1, true, null, null, null);
		PlaybackFadeGain gain = PlaybackFadeGain.forClips(List.of(muted, clip("b", 2_000, 4_000, null)));
		assertTrue(gain.active());
		assertEquals(0.0, gain.gainAt(0.5), 1e-9);
		assertEquals(0.0, gain.gainAt(1.999), 1e-9);
		assertEquals(1.0, gain.gainAt(2.0), 1e-9);
		float[] pcm = {0.5f, 0.5f, 0.5f, 0.5f};
		gain.apply(pcm, 2, 2, 1.0);
		assertEquals(0f, pcm[0]);
		assertEquals(0f, pcm[3]);
	}

	@Test
	@DisplayName("no fade-black transition means unity gain everywhere")
	void noTransitionsMeansUnity() {
		PlaybackFadeGain gain = PlaybackFadeGain.forClips(
				List.of(clip("a", 0, 2_000, null), clip("b", 2_000, 4_000, null)));
		assertFalse(gain.active());
		assertSame(PlaybackFadeGain.NONE, gain);
		for (double seconds : new double[] {0, 1, 1.999, 2, 4}) {
			assertEquals(1, gain.gainAt(seconds), 1e-12);
		}
	}

	@Test
	@DisplayName("gain dips to zero at the cut and returns, matching the picture fade")
	void cutFadeGain() {
		PlaybackFadeGain gain = PlaybackFadeGain.forClips(cutAndOut());
		assertTrue(gain.active());
		assertEquals(1, gain.gainAt(1.0), 1e-12);
		assertEquals(1, gain.gainAt(1.75), 1e-12);
		assertEquals(0.5, gain.gainAt(1.875), 1e-12);
		assertEquals(0, gain.gainAt(2.0), 1e-12);
		assertEquals(1, gain.gainAt(2.25), 1e-12);
		// Last-clip fade-out: starts at 3 s (half the 1 s transition before the end).
		assertEquals(1, gain.gainAt(3.0), 1e-12);
		assertEquals(0.5, gain.gainAt(3.5), 1e-12);
		assertEquals(0, gain.gainAt(4.0), 1e-12);
	}

	@Test
	@DisplayName("a gap holds the gain at zero until the next clip fades in")
	void gapHoldsZero() {
		PlaybackFadeGain gain = PlaybackFadeGain.forClips(
				List.of(clip("a", 0, 2_000, 500L), clip("b", 3_000, 5_000, null)));
		assertEquals(1, gain.gainAt(1.75), 1e-12);
		assertEquals(0, gain.gainAt(2.0), 1e-12);
		assertEquals(0, gain.gainAt(2.5), 1e-12);
		assertEquals(0, gain.gainAt(3.0), 1e-12);
		assertEquals(0.5, gain.gainAt(3.125), 1e-12);
		assertEquals(1, gain.gainAt(3.25), 1e-12);
	}

	@Test
	@DisplayName("a seek into the middle of a fade gets the correct gain with no state")
	void seekMidFadeIsStateless() {
		PlaybackFadeGain gain = PlaybackFadeGain.forClips(cutAndOut());
		assertEquals(gain.gainAt(1.875), gain.gainAt(1.875), 0);
		assertEquals(0.5, gain.gainAt(1.875), 1e-12);
	}

	@Test
	@DisplayName("applies the gain per frame over the interleaved PCM chunk")
	void appliesPerFrame() {
		PlaybackFadeGain gain = PlaybackFadeGain.forClips(cutAndOut());
		int frames = 480; // 10 ms at 48 kHz
		float[] pcm = new float[frames * 2];
		for (int index = 0; index < pcm.length; index++) {
			pcm[index] = 1f;
		}
		gain.apply(pcm, frames, 2, 1.75); // fade starts at 1.75 s, full black at 2.0 s
		// Smoothstep(0) = 0 -> 1, smoothstep(0.5) = 0.5 -> 0.5, smoothstep(0.1) ~ 0.028.
		assertEquals(1f, pcm[0], 1e-6);
		assertEquals(1f, pcm[1], 1e-6);
		int middle = 240; // 5 ms in: progress 0.02
		double progress = middle / (double) PlaybackAudioProducer.SAMPLE_RATE / 0.25;
		double expected = 1 - smoothstep(progress);
		assertEquals(expected, pcm[middle * 2], 1e-6);
		assertEquals(pcm[middle * 2], pcm[middle * 2 + 1], 0);
		for (int frame = 0; frame < frames; frame++) {
			double time = 1.75 + frame / (double) PlaybackAudioProducer.SAMPLE_RATE;
			assertEquals(gain.gainAt(time), pcm[frame * 2], 1e-6);
		}
	}

	@Test
	@DisplayName("the result does not depend on the chunk size")
	void blockSizeIndependent() {
		PlaybackFadeGain gain = PlaybackFadeGain.forClips(cutAndOut());
		int frames = 960; // 20 ms spanning the fade start at 1.75 s
		float[] big = filled(frames);
		gain.apply(big, frames, 2, 1.74);
		// Same span in four 240-frame chunks.
		float[] small = filled(frames);
		for (int chunk = 0; chunk < 4; chunk++) {
			int offset = chunk * 240;
			float[] slice = new float[240 * 2];
			System.arraycopy(small, offset * 2, slice, 0, slice.length);
			gain.apply(slice, 240, 2, 1.74 + offset / (double) PlaybackAudioProducer.SAMPLE_RATE);
			System.arraycopy(slice, 0, small, offset * 2, slice.length);
		}
		for (int index = 0; index < big.length; index++) {
			assertEquals(big[index], small[index], 1e-7);
		}
	}

	@Test
	@DisplayName("preview gain is the same function the export envelope is generated from")
	void matchesExportEnvelopeSource() {
		// ExportAudioGraph.fadeGainExpression emits 1 - smoothstep(...) per window from these
		// exact windows, so preview and export agree by construction at every position.
		List<ClipTransitions.FadeWindow> windows = ClipTransitions.buildFadeWindows(cutAndOut());
		PlaybackFadeGain gain = new PlaybackFadeGain(windows);
		for (double ms : new double[] {1_750, 1_800, 1_875, 1_950, 2_000, 2_100, 3_100, 3_500,
				3_900}) {
			assertEquals(ClipTransitions.getFadeGain(windows, ms), gain.gainAt(ms / 1000.0),
					1e-12);
		}
	}

	private static float[] filled(int frames) {
		float[] pcm = new float[frames * 2];
		for (int index = 0; index < pcm.length; index++) {
			pcm[index] = 0.5f;
		}
		return pcm;
	}

	private static double smoothstep(double x) {
		double t = Math.min(1, Math.max(0, x));
		return t * t * (3 - 2 * t);
	}
}
