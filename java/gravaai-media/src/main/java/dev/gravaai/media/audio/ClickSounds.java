package dev.gravaai.media.audio;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.project.ClickSoundStyle;
import dev.gravaai.core.project.KeySoundStyle;
import dev.gravaai.media.ffmpeg.AudioDecoder;

/**
 * The bundled click sounds decoded to interleaved float32 48 kHz stereo (the decoder output
 * shape, so playback and export share one cache).
 *
 * <p>Resources live at {@code /dev/gravaai/media/sounds/click-<name>.ogg} next to the cursor
 * sprites. A resource is copied to a temp file (the {@code PreviewBackgrounds.resolveVideo}
 * classpath→temp pattern) and decoded once through {@link AudioDecoder}; results are cached per
 * style. A decode failure logs once and marks that sound unavailable — the app never fails
 * because of a sound file.</p>
 */
public final class ClickSounds {

	private static final System.Logger LOG = System.getLogger(ClickSounds.class.getName());
	private static final String RESOURCE_ROOT = "/dev/gravaai/media/sounds/";
	private static final int STAGING_FRAMES = 4096;

	private static final Map<String, float[]> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> UNAVAILABLE = ConcurrentHashMap.newKeySet();

	private ClickSounds() {}

	/** Interleaved stereo samples, or {@code null} when the sound failed to load. */
	public static float @Nullable [] pcm(ClickSoundStyle style) {
		return pcm("click-" + style.wire());
	}

	/** Key-press sound samples ({@code key-<name>.ogg}); same cache and failure rules. */
	public static float @Nullable [] pcm(KeySoundStyle style) {
		return pcm("key-" + style.wire());
	}

	/** Stereo frames of a key-press sound; 0 when unavailable. */
	public static int frames(KeySoundStyle style) {
		float @Nullable [] pcm = pcm(style);
		return pcm == null ? 0 : pcm.length / AudioDecoder.OUTPUT_CHANNELS;
	}

	/** Samples of the bundled sound with this resource base name, or {@code null}. */
	public static float @Nullable [] pcm(String name) {
		float @Nullable [] cached = CACHE.get(name);
		if (cached != null) {
			return cached;
		}
		if (UNAVAILABLE.contains(name)) {
			return null;
		}
		synchronized (ClickSounds.class) {
			if (UNAVAILABLE.contains(name)) {
				return null;
			}
			float @Nullable [] decoded = decode(name);
			if (decoded == null) {
				UNAVAILABLE.add(name);
				return null;
			}
			CACHE.put(name, decoded);
			return decoded;
		}
	}

	/** Stereo frames of the sound; 0 when unavailable. */
	public static int frames(ClickSoundStyle style) {
		float @Nullable [] pcm = pcm(style);
		return pcm == null ? 0 : pcm.length / AudioDecoder.OUTPUT_CHANNELS;
	}

	/** Whether the sound decoded (and may therefore be played). */
	public static boolean available(ClickSoundStyle style) {
		return pcm(style) != null;
	}

	/** Decodes every bundled sound off the calling thread, so the first playback is not cold. */
	public static void preload() {
		Threads.startVirtual("gravaai-click-preload", () -> {
			for (ClickSoundStyle style : ClickSoundStyle.values()) {
				pcm(style);
			}
			for (KeySoundStyle style : KeySoundStyle.values()) {
				pcm(style);
			}
		});
	}

	private static float @Nullable [] decode(String name) {
		String resource = RESOURCE_ROOT + name + ".ogg";
		try (InputStream in = ClickSounds.class.getResourceAsStream(resource)) {
			if (in == null) {
				LOG.log(System.Logger.Level.WARNING, "click sound resource missing: " + resource);
				return null;
			}
			Path temp = Files.createTempFile("gravaai-" + name, ".ogg");
			Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
			temp.toFile().deleteOnExit();
			try (AudioDecoder decoder = AudioDecoder.open(temp)) {
				return readAll(decoder);
			}
		} catch (IOException | RuntimeException failure) {
			LOG.log(System.Logger.Level.WARNING,
					"click sound decode failed: " + resource + ": " + failure);
			return null;
		}
	}

	/** Streams the whole sound into a growing interleaved buffer. */
	private static float[] readAll(AudioDecoder decoder) {
		float[] staging = new float[STAGING_FRAMES * AudioDecoder.OUTPUT_CHANNELS];
		float[] pcm = new float[staging.length];
		int written = 0;
		while (true) {
			int frames = decoder.read(staging);
			if (frames < 0) {
				break;
			}
			if (frames == 0) {
				continue;
			}
			if ((written + frames) * AudioDecoder.OUTPUT_CHANNELS > pcm.length) {
				float[] grown = new float[Math.max(pcm.length * 2,
						(written + frames) * AudioDecoder.OUTPUT_CHANNELS)];
				System.arraycopy(pcm, 0, grown, 0, written * AudioDecoder.OUTPUT_CHANNELS);
				pcm = grown;
			}
			System.arraycopy(staging, 0, pcm,
					written * AudioDecoder.OUTPUT_CHANNELS, frames * AudioDecoder.OUTPUT_CHANNELS);
			written += frames;
		}
		if (written * AudioDecoder.OUTPUT_CHANNELS == pcm.length) {
			return pcm;
		}
		float[] trimmed = new float[written * AudioDecoder.OUTPUT_CHANNELS];
		System.arraycopy(pcm, 0, trimmed, 0, trimmed.length);
		return trimmed;
	}
}
