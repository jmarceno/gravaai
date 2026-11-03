package dev.gravaai.media.playback;

import dev.gravaai.core.project.ClickSoundStyle;
import dev.gravaai.media.audio.ClickSounds;

/**
 * Mixes the bundled click sound into preview chunks at the telemetry-derived hit times.
 *
 * <p>Stateless per chunk: each {@link #mix} locates the intersecting windows by binary search,
 * so seeks, rate changes and clip-sequence changes need no bookkeeping. Overlapping tails
 * (a click storm) clamp to ±1, so there is no wrap-around distortion.</p>
 */
public final class ClickSoundTrack {

	private final ClickSoundStyle sound;
	private final double volume;
	private final long[] timesMs;

	public ClickSoundTrack(ClickSoundStyle sound, double volume, long[] timesMs) {
		this.sound = sound;
		this.volume = Double.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1;
		this.timesMs = timesMs.clone();
	}

	public ClickSoundStyle sound() {
		return sound;
	}

	/** Adds the intersecting click windows to {@code interleaved} (stereo float32 48 kHz). */
	public void mix(float[] interleaved, int frames, double chunkStartSeconds) {
		if (timesMs.length == 0 || frames <= 0) {
			return;
		}
		float @org.jspecify.annotations.Nullable [] pcm = ClickSounds.pcm(sound);
		if (pcm == null || pcm.length == 0) {
			return;
		}
		int soundFrames = pcm.length / PlaybackAudioProducer.CHANNELS;
		double soundMs = soundFrames * 1000.0 / PlaybackAudioProducer.SAMPLE_RATE;
		double chunkStartMs = chunkStartSeconds * 1000.0;
		double chunkEndMs = chunkStartMs + frames * 1000.0 / PlaybackAudioProducer.SAMPLE_RATE;
		int index = firstSoundingAt(chunkStartMs - soundMs);
		float gain = (float) volume;
		for (; index < timesMs.length; index++) {
			long hitMs = timesMs[index];
			if (hitMs >= chunkEndMs || hitMs + soundMs <= chunkStartMs) {
				break;
			}
			int offsetFrames = (int) Math.round(
					(hitMs - chunkStartMs) * PlaybackAudioProducer.SAMPLE_RATE / 1000.0);
			int from = Math.max(0, offsetFrames);
			int to = Math.min(frames, soundFrames + offsetFrames);
			for (int frame = from; frame < to; frame++) {
				int sourceFrame = frame - offsetFrames;
				int base = frame * PlaybackAudioProducer.CHANNELS;
				interleaved[base] = clamp(interleaved[base]
						+ pcm[sourceFrame * PlaybackAudioProducer.CHANNELS] * gain);
				interleaved[base + 1] = clamp(interleaved[base + 1]
						+ pcm[sourceFrame * PlaybackAudioProducer.CHANNELS + 1] * gain);
			}
		}
	}

	/** First hit whose sound can still be audible at {@code timeMs} (binary search). */
	private int firstSoundingAt(double timeMs) {
		int low = 0;
		int high = timesMs.length;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (timesMs[middle] < timeMs) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		int index = low;
		while (index > 0 && timesMs[index - 1] >= timeMs) {
			index--;
		}
		return index;
	}

	private static float clamp(float value) {
		return value > 1 ? 1 : (value < -1 ? -1 : value);
	}
}
