package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.media.audio.AtempoChain;
import dev.gravaai.media.audio.PlaybackAudioSink;
import dev.gravaai.media.audio.PulseAudio;

/**
 * The real preview audio track (plan §5.9): walks {@link PlaybackAudioPlan} segments, decodes each
 * one through a {@link PcmSource} (bundled ffmpeg CLI with the segment's {@code atempo} chain),
 * writes silence over timeline gaps, applies the volume gain and writes to the
 * {@link PlaybackAudioSink}. Its sample/latency counters feed the engine's {@link AudioMediaClock}.
 *
 * <p>The audio thread is the engine render tick: every method is called from there except
 * {@code seek}/{@code setRate}/{@code setVolume}/{@code close}, which the engine calls on transport
 * changes. The producer owns the sink and closes it in {@link #close()}.
 */
public final class PlaybackAudioProducer implements PlaybackAudioTrack {

	public static final int SAMPLE_RATE = 48_000;
	public static final int CHANNELS = 2;
	public static final int DEFAULT_CHUNK_FRAMES = 1_920;

	/** Opens one segment's PCM source; injectable so tests avoid processes. */
	@FunctionalInterface
	public interface PcmSourceFactory {
		PcmSource open(
				String mediaPath,
				double sourceStartSeconds,
				double durationSeconds,
				List<String> atempoFilters);
	}

	private final String mediaPath;
	private final PlaybackAudioSink sink;
	private final PcmSourceFactory sourceFactory;
	private final List<PlaybackAudioPlan.AudioSegment> plan;
	/** Clip-transition fade gain, applied per frame like the export's fade-black envelope. */
	private final PlaybackFadeGain fadeGain;
	private final int chunkFrames;
	private final float[] chunk;
	private final float[] silence;

	private double timelineSeconds;
	private @Nullable PcmSource source;
	private double segmentTimelineEnd;
	private long samplesWritten;
	private int latencyMicros;
	private double volume = 1;
	private double rate = 1;
	private volatile boolean closed;
	private volatile boolean paused;

	public PlaybackAudioProducer(
			String mediaPath,
			List<ClipRegion> clips,
			PlaybackAudioSink sink,
			PcmSourceFactory sourceFactory) {
		this(mediaPath, clips, sink, sourceFactory, DEFAULT_CHUNK_FRAMES);
	}

	public PlaybackAudioProducer(
			String mediaPath,
			List<ClipRegion> clips,
			PlaybackAudioSink sink,
			PcmSourceFactory sourceFactory,
			int chunkFrames) {
		if (mediaPath == null || mediaPath.isBlank()) {
			throw new IllegalArgumentException("mediaPath must not be blank");
		}
		if (sink == null || sourceFactory == null) {
			throw new IllegalArgumentException("sink and sourceFactory are required");
		}
		if (chunkFrames <= 0) {
			throw new IllegalArgumentException("chunkFrames must be positive: " + chunkFrames);
		}
		this.mediaPath = mediaPath;
		this.plan = PlaybackAudioPlan.plan(clips);
		this.fadeGain = PlaybackFadeGain.forClips(clips);
		this.sink = sink;
		this.sourceFactory = sourceFactory;
		this.chunkFrames = chunkFrames;
		this.chunk = new float[chunkFrames * CHANNELS];
		this.silence = new float[chunkFrames * CHANNELS];
	}

	/** Production factory: libpulse-simple sink + the bundled ffmpeg CLI PCM source. */
	public static PlaybackAudioProducer openWithPulse(
			String mediaPath, List<ClipRegion> clips, Path ffmpegPath) {
		if (ffmpegPath == null) {
			throw new IllegalArgumentException("ffmpegPath must not be null");
		}
		Path ffmpeg = ffmpegPath;
		return new PlaybackAudioProducer(
				mediaPath,
				clips,
				PulseAudio.openPlayback(),
				(path, start, duration, filters) -> FfmpegCliPcmSource.open(
						ffmpeg, path, start, duration, filters));
	}

	@Override
	public synchronized void seek(double timelineSeconds) {
		if (closed) {
			return;
		}
		this.timelineSeconds = clampTimeline(timelineSeconds);
		this.samplesWritten = 0;
		this.latencyMicros = 0;
		closeSourceLocked();
		sink.flush();
	}

	@Override
	public synchronized void pump() {
		if (closed || paused) {
			return;
		}
		if (source == null) {
			openSegmentLocked();
		}
		if (source != null) {
			double chunkStartSeconds = timelineSeconds;
			int frames = source.read(chunk, chunkFrames);
			if (paused) {
				// Paused while this chunk decoded: never write it after the pause flush.
				return;
			}
			if (frames > 0) {
				applyVolumeLocked(frames);
				fadeGain.apply(chunk, frames, CHANNELS, chunkStartSeconds);
				sink.write(chunk, frames);
				samplesWritten += frames;
				latencyMicros = sink.latencyMicros();
				timelineSeconds += frames / (double) SAMPLE_RATE;
				if (timelineSeconds >= segmentTimelineEnd - 1e-9) {
					closeSourceLocked();
				}
				return;
			}
			// End of this segment's source before its planned end: treat as a segment boundary.
			closeSourceLocked();
			timelineSeconds = Math.max(timelineSeconds, segmentTimelineEnd);
		}
		writeSilenceLocked();
	}

	private void writeSilenceLocked() {
		int framesToWrite = chunkFrames;
		double nextStart = nextSegmentStartLocked(timelineSeconds);
		if (nextStart >= 0) {
			framesToWrite = (int) Math.max(1,
					Math.min(chunkFrames, Math.ceil((nextStart - timelineSeconds) * SAMPLE_RATE)));
		}
		sink.write(silence, framesToWrite);
		samplesWritten += framesToWrite;
		latencyMicros = sink.latencyMicros();
		timelineSeconds += framesToWrite / (double) SAMPLE_RATE;
	}

	private void openSegmentLocked() {
		int index = segmentAtLocked(timelineSeconds);
		if (index < 0) {
			return;
		}
		PlaybackAudioPlan.AudioSegment segment = plan.get(index);
		double timelineSpan = segment.timelineEndSeconds() - segment.timelineStartSeconds();
		double sourceSpan = segment.sourceEndSeconds() - segment.sourceStartSeconds();
		if (timelineSpan <= 0 || sourceSpan <= 0) {
			return;
		}
		double offsetInTimeline = Math.max(0, timelineSeconds - segment.timelineStartSeconds());
		double sourceStart = segment.sourceStartSeconds() + offsetInTimeline * (sourceSpan / timelineSpan);
		double duration = segment.sourceEndSeconds() - sourceStart;
		if (duration <= 0) {
			return;
		}
		List<String> filters = new ArrayList<>(segment.atempoFilters());
		if (Math.abs(rate - 1) > 1e-9) {
			filters.addAll(AtempoChain.filters(rate));
		}
		source = sourceFactory.open(mediaPath, sourceStart, duration, List.copyOf(filters));
		// atempo (clip speed and preview rate) shortens the audio to timeline time.
		double coveredTimeline = duration / (sourceSpan / timelineSpan) / rate;
		segmentTimelineEnd = Math.min(
				segment.timelineEndSeconds(), timelineSeconds + Math.max(0, coveredTimeline));
	}

	private int segmentAtLocked(double timeline) {
		for (int index = 0; index < plan.size(); index++) {
			PlaybackAudioPlan.AudioSegment segment = plan.get(index);
			if (timeline >= segment.timelineStartSeconds() - 1e-9
					&& timeline < segment.timelineEndSeconds() - 1e-9) {
				return index;
			}
		}
		return -1;
	}

	private double nextSegmentStartLocked(double timeline) {
		for (PlaybackAudioPlan.AudioSegment segment : plan) {
			if (segment.timelineStartSeconds() > timeline + 1e-9) {
				return segment.timelineStartSeconds();
			}
		}
		return -1;
	}

	private void applyVolumeLocked(int frames) {
		if (Math.abs(volume - 1) < 1e-9) {
			return;
		}
		float gain = (float) volume;
		for (int index = 0; index < frames * CHANNELS; index++) {
			chunk[index] *= gain;
		}
	}

	private void closeSourceLocked() {
		PcmSource current = source;
		source = null;
		if (current != null) {
			current.close();
		}
	}

	@Override
	public synchronized long samplesWritten() {
		return samplesWritten;
	}

	@Override
	public synchronized int latencyMicros() {
		return latencyMicros;
	}

	/**
	 * Silences the output at once: the flag stops the next pump and the first flush only waits for
	 * the write in flight (one 40 ms chunk), not for a pump that is still starting its decoder.
	 * Then the segment decoder is released (its shutdown runs in the background) and the output is
	 * flushed again in case that pump wrote one more chunk.
	 */
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
			closeSourceLocked();
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
		// The atempo chain depends on the rate; reopen from the current timeline on the next pump.
		closeSourceLocked();
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
		closeSourceLocked();
		sink.close();
	}

	private static double clampTimeline(double timelineSeconds) {
		if (!Double.isFinite(timelineSeconds)) {
			return 0;
		}
		return Math.max(0, timelineSeconds);
	}

	/** The plans the producer walks (diagnostics/tests). */
	public synchronized List<PlaybackAudioPlan.AudioSegment> plan() {
		return plan;
	}
}
