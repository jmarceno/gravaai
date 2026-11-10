package dev.gravaai.media.playback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.media.audio.AtempoChain;
import dev.gravaai.media.audio.PlaybackAudioSink;
import dev.gravaai.media.audio.PulseAudio;

/**
 * The meeting playback audio track: decodes 1..n audio files (the mic and system sidecars, or the
 * recording's own embedded mix when the sidecars are absent) per pump chunk and <em>sums</em> them
 * into one stereo stream, the way {@link ClickSoundTrack#mix} blends its tracks over the segment
 * PCM. Unlike {@link PlaybackAudioProducer} there are no clips or gaps: the meeting plays linearly
 * from its start to its duration, so the producer only tracks a play position and one set of open
 * decoders.
 *
 * <p>Every source is opened with the same {@code -ss/-t} span and the same atempo chain, so the
 * decoders stay in lockstep; a source that ends early simply contributes silence while the others
 * finish (sidecar lengths can differ slightly). Playback ends when every source is drained.
 *
 * <p>Threading mirrors {@link PlaybackAudioProducer}: the pump thread calls {@link #pump()};
 * transport changes (seek/rate/volume/pause) come from the facade on any thread. The producer owns
 * the sink and closes it in {@link #close()}.
 */
public final class MeetingAudioProducer implements MeetingAudioTrack {

	private final List<String> audioPaths;
	private final double durationSeconds;
	private final PlaybackAudioSink sink;
	private final SourceFactory sourceFactory;
	private final int chunkFrames;
	private final float[] chunk;
	/** Per-source decode buffers, parallel to {@link #sources}. */
	private final List<float[]> buffers = new ArrayList<>();
	private final List<PcmSource> sources = new ArrayList<>();
	/** True once {@code read} returned end-of-file for the matching source. */
	private final List<Boolean> drained = new ArrayList<>();

	private double baseSeconds;
	private long samplesWritten;
	private int latencyMicros;
	private double volume = 1;
	private double rate = 1;
	private boolean ended;
	private volatile boolean closed;
	private volatile boolean paused;

	/** Opens one meeting track's PCM source; injectable so tests avoid processes. */
	@FunctionalInterface
	public interface SourceFactory {
		PcmSource open(String mediaPath, double sourceStartSeconds, double durationSeconds,
				List<String> filters);
	}

	public MeetingAudioProducer(
			List<String> audioPaths,
			double durationSeconds,
			PlaybackAudioSink sink,
			SourceFactory sourceFactory,
			int chunkFrames) {
		if (audioPaths == null || audioPaths.isEmpty()) {
			throw new IllegalArgumentException("audioPaths must not be empty");
		}
		if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
			throw new IllegalArgumentException("durationSeconds must be positive: " + durationSeconds);
		}
		if (sink == null || sourceFactory == null) {
			throw new IllegalArgumentException("sink and sourceFactory are required");
		}
		if (chunkFrames <= 0) {
			throw new IllegalArgumentException("chunkFrames must be positive: " + chunkFrames);
		}
		this.audioPaths = List.copyOf(audioPaths);
		this.durationSeconds = durationSeconds;
		this.sink = sink;
		this.sourceFactory = sourceFactory;
		this.chunkFrames = chunkFrames;
		this.chunk = new float[chunkFrames * PlaybackAudioProducer.CHANNELS];
	}

	/** Production factory: libpulse-simple sink + the bundled ffmpeg CLI PCM source. */
	public static MeetingAudioProducer openWithPulse(MeetingPlayback.Media media, Path ffmpegPath) {
		if (ffmpegPath == null) {
			throw new IllegalArgumentException("ffmpegPath must not be null");
		}
		return new MeetingAudioProducer(
				media.audioPaths(),
				media.durationSeconds(),
				PulseAudio.openPlayback(),
				(path, start, duration, filters) -> FfmpegCliPcmSource.open(
						ffmpegPath, path, start, duration, filters),
				PlaybackAudioProducer.DEFAULT_CHUNK_FRAMES);
	}

	@Override
	public synchronized void seek(double positionSeconds) {
		if (closed) {
			return;
		}
		baseSeconds = clampPosition(positionSeconds);
		samplesWritten = 0;
		latencyMicros = 0;
		ended = false;
		closeSourcesLocked();
		sink.flush();
	}

	@Override
	public synchronized void pump() {
		if (closed || paused || ended) {
			return;
		}
		if (sources.isEmpty()) {
			openSourcesLocked();
			if (sources.isEmpty()) {
				return;
			}
		}
		int frames = readMixedLocked();
		if (frames <= 0) {
			// Every source hit EOF: playback has drained to the end.
			ended = true;
			return;
		}
		applyVolumeLocked(frames);
		sink.write(chunk, frames);
		samplesWritten += frames;
		latencyMicros = sink.latencyMicros();
	}

	/** Reads and sums one chunk from every source; 0 when all sources are drained. */
	private int readMixedLocked() {
		int sourceCount = sources.size();
		int[] framesPerSource = new int[sourceCount];
		int mixedFrames = 0;
		for (int index = 0; index < sourceCount; index++) {
			float[] buffer = buffers.get(index);
			int frames = drained.get(index)
					? 0
					: sources.get(index).read(buffer, chunkFrames);
			if (frames < 0) {
				frames = 0;
				drained.set(index, true);
			}
			framesPerSource[index] = frames;
			if (frames > mixedFrames) {
				mixedFrames = frames;
			}
		}
		if (mixedFrames == 0) {
			return 0;
		}
		// Sources that stopped early stay silent for the rest of the chunk: clear their stale
		// buffer tail before summing, so residue from an earlier pump cannot repeat.
		java.util.Arrays.fill(chunk, 0, mixedFrames * PlaybackAudioProducer.CHANNELS, 0f);
		for (int index = 0; index < sourceCount; index++) {
			float[] buffer = buffers.get(index);
			int frames = framesPerSource[index];
			java.util.Arrays.fill(buffer, frames * PlaybackAudioProducer.CHANNELS,
					mixedFrames * PlaybackAudioProducer.CHANNELS, 0f);
			for (int sample = 0; sample < mixedFrames * PlaybackAudioProducer.CHANNELS; sample++) {
				chunk[sample] = clamp(chunk[sample] + buffer[sample]);
			}
		}
		return mixedFrames;
	}

	private void openSourcesLocked() {
		double position = writePositionLocked();
		double remainingTimeline = durationSeconds - position;
		if (remainingTimeline <= 1e-9) {
			ended = true;
			return;
		}
		// atempo shortens the source to timeline time, so the span to decode scales with the rate.
		double sourceSpan = remainingTimeline * rate;
		List<String> filters = Math.abs(rate - 1) > 1e-9
				? AtempoChain.filters(rate)
				: List.of();
		closeSourcesLocked();
		for (String path : audioPaths) {
			sources.add(sourceFactory.open(path, position, sourceSpan, filters));
			buffers.add(new float[chunkFrames * PlaybackAudioProducer.CHANNELS]);
			drained.add(false);
		}
	}

	private void applyVolumeLocked(int frames) {
		if (Math.abs(volume - 1) < 1e-9) {
			return;
		}
		float gain = (float) volume;
		for (int index = 0; index < frames * PlaybackAudioProducer.CHANNELS; index++) {
			chunk[index] *= gain;
		}
	}

	private void closeSourcesLocked() {
		for (PcmSource source : sources) {
			source.close();
		}
		sources.clear();
		buffers.clear();
		drained.clear();
	}

	private static float clamp(float value) {
		return value > 1 ? 1 : (value < -1 ? -1 : value);
	}

	private double clampPosition(double positionSeconds) {
		if (!Double.isFinite(positionSeconds)) {
			return 0;
		}
		return Math.max(0, Math.min(positionSeconds, durationSeconds));
	}

	/** Where the next decode starts: what has been handed to the sink since the last seek. */
	private double writePositionLocked() {
		return baseSeconds + samplesWritten / (double) PlaybackAudioProducer.SAMPLE_RATE;
	}

	/** The play position: what has been handed to the sink minus its latency. */
	public synchronized double positionSeconds() {
		return writePositionLocked() - latencyMicros / 1_000_000.0;
	}

	@Override
	public synchronized long samplesWritten() {
		return samplesWritten;
	}

	@Override
	public synchronized int latencyMicros() {
		return latencyMicros;
	}

	@Override
	public boolean ended() {
		synchronized (this) {
			return ended || closed;
		}
	}

	@Override
	public void pause() {
		paused = true;
		if (closed) {
			return;
		}
		sink.flush();
		synchronized (this) {
			if (closed) {
				return;
			}
			closeSourcesLocked();
			sink.flush();
		}
	}

	@Override
	public void resume() {
		paused = false;
	}

	@Override
	public synchronized void setRate(double rate) {
		if (!Double.isFinite(rate) || rate <= 0 || Math.abs(this.rate - rate) < 1e-9) {
			return;
		}
		this.rate = rate;
		// The atempo chain depends on the rate; reopen from the current position on the next pump.
		closeSourcesLocked();
	}

	@Override
	public synchronized void setVolume(double volume) {
		this.volume = Double.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		closeSourcesLocked();
		sink.close();
	}

	/** The media this producer walks (diagnostics/tests). */
	public synchronized List<String> audioPaths() {
		return audioPaths;
	}
}
