package dev.gravaai.media.audio;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.project.ClickSoundStyle;

/**
 * Plays one bundled click through the libpulse sink, so picking a sound in the inspector is
 * audible immediately. The bundled FFmpeg has no output device and JavaFX Media is absent, so
 * {@link PulseAudio#openPlayback()} is the only playback route (plan §5.9).
 *
 * <p>The write runs on a virtual thread and failures are swallowed: an audition is a nicety,
 * never a reason to fail the panel.</p>
 */
public final class ClickSoundAudition {

	private static final System.Logger LOG = System.getLogger(ClickSoundAudition.class.getName());

	private ClickSoundAudition() {}

	/** Schedules the sound on a virtual thread; returns as soon as the decode state is known. */
	public static void play(ClickSoundStyle style, double volume) {
		float @Nullable [] pcm = ClickSounds.pcm(style);
		if (pcm == null || pcm.length == 0) {
			return;
		}
		double gain = Double.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1;
		Thread.ofVirtual().start(() -> {
			try (PlaybackAudioSink sink = PulseAudio.openPlayback()) {
				float[] scaled = new float[pcm.length];
				for (int index = 0; index < pcm.length; index++) {
					scaled[index] = (float) Math.max(-1, Math.min(1, pcm[index] * gain));
				}
				sink.write(scaled, scaled.length / PlaybackAudioSink.CHANNELS);
			} catch (RuntimeException failure) {
				LOG.log(System.Logger.Level.DEBUG, "click audition unavailable: " + failure);
			}
		});
	}
}
