package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * The meeting playback transport (plan: meeting audio player): play/pause/seek/rate/volume over
 * the mixed meeting audio, in the {@link PlaybackController} vocabulary but audio-only — meetings
 * have no frame sources, so there is no video tick and the blocking PulseAudio write on the pump
 * thread paces the clock (the audio is the timeline).
 *
 * <p>Threading: transport methods are safe from the JavaFX thread; the pump thread runs while
 * playing and publishes {@link PlaybackTime}/{@link PlaybackStatus} from there, so UI listeners
 * hop to the FX thread.
 */
public final class MeetingPlayback implements AutoCloseable {

	/** The mixed audio to play: every file starts at 0 and runs to {@code durationSeconds}. */
	public record Media(List<String> audioPaths, double durationSeconds) {

		public Media {
			if (audioPaths == null || audioPaths.isEmpty()) {
				throw new IllegalArgumentException("audioPaths must not be empty");
			}
			if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
				throw new IllegalArgumentException(
						"durationSeconds must be positive: " + durationSeconds);
			}
			audioPaths = List.copyOf(audioPaths);
		}
	}

	/** Opens the audio track for one media; injectable so tests avoid processes. */
	@FunctionalInterface
	public interface TrackFactory {
		MeetingAudioTrack open(Media media);
	}

	private static final System.Logger LOG = System.getLogger("gravaai.media.playback");
	/** How far short of the declared duration the last write may land before ENDED wins. */
	private static final double END_EPSILON_SECONDS = 0.05;

	private final TrackFactory factory;
	private final Object lock = new Object();
	private final List<Consumer<PlaybackStatus>> statusListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<PlaybackTime>> timeListeners = new CopyOnWriteArrayList<>();

	private @Nullable Media media;
	private @Nullable MeetingAudioTrack track;
	private PlaybackState state = PlaybackState.IDLE;
	/** The media position at {@link #baseSamples}: the last seek or rate change. */
	private double baseSeconds;
	/** The track's sample count at {@link #baseSeconds}. */
	private long baseSamples;
	private double rate = 1;
	private double volume = 1;
	private volatile boolean closed;
	/** The running pump thread's stop flag. */
	private @Nullable AtomicBoolean pumping;

	public MeetingPlayback(TrackFactory factory) {
		this.factory = factory;
	}

	/** A production transport: libpulse-simple output + the bundled ffmpeg CLI decoders. */
	public static MeetingPlayback openWithPulse(Path ffmpegPath) {
		return new MeetingPlayback(media -> MeetingAudioProducer.openWithPulse(media, ffmpegPath));
	}

	/** Loads (or replaces) the media and resets the playhead to the start. */
	public void setMedia(Media nextMedia) {
		if (nextMedia == null) {
			throw new IllegalArgumentException("media must not be null");
		}
		stopPump();
		MeetingAudioTrack previous;
		MeetingAudioTrack next = factory.open(nextMedia);
		next.setVolume(volume);
		next.setRate(rate);
		synchronized (lock) {
			previous = track;
			track = next;
			media = nextMedia;
			baseSeconds = 0;
			baseSamples = 0;
			state = PlaybackState.PAUSED;
		}
		closeQuietly(previous);
		publishStatus(status());
		publishTime(new PlaybackTime(0, 0.0, null));
	}

	public void play() {
		MeetingAudioTrack current;
		synchronized (lock) {
			if (closed || state == PlaybackState.PLAYING) {
				return;
			}
			current = track;
			if (current == null) {
				return;
			}
			if (state == PlaybackState.ENDED) {
				seekLocked(0);
			}
			state = PlaybackState.PLAYING;
		}
		current.resume();
		startPump();
		publishTime(time());
		publishStatus(status());
	}

	public void pause() {
		MeetingAudioTrack current;
		PlaybackStatus status;
		PlaybackTime time;
		synchronized (lock) {
			if (closed || state != PlaybackState.PLAYING) {
				return;
			}
			current = track;
			state = PlaybackState.PAUSED;
			status = statusLocked();
			time = timeLocked();
		}
		stopPump();
		if (current != null) {
			current.pause();
		}
		publishStatus(status);
		publishTime(time);
	}

	public void togglePlayPause() {
		if (isPlaying()) {
			pause();
		} else {
			play();
		}
	}

	public void seek(double positionSeconds) {
		synchronized (lock) {
			if (closed || track == null) {
				return;
			}
			seekLocked(positionSeconds);
		}
		publishStatus(status());
		publishTime(time());
	}

	private void seekLocked(double positionSeconds) {
		MeetingAudioTrack current = track;
		double target = Double.isFinite(positionSeconds)
				? Math.max(0, Math.min(positionSeconds, durationLocked()))
				: 0;
		baseSeconds = target;
		state = state == PlaybackState.PLAYING ? PlaybackState.PLAYING : PlaybackState.PAUSED;
		if (current != null) {
			current.seek(target);
		}
		// The track restarts its sample count at every seek.
		baseSamples = 0;
	}

	public void setRate(double rate) {
		synchronized (lock) {
			if (!Double.isFinite(rate) || rate <= 0) {
				return;
			}
			MeetingAudioTrack current = track;
			if (current != null) {
				// What was played so far was played at the old rate: fold it into the base.
				baseSeconds = positionLocked(current);
				baseSamples = current.samplesWritten();
			}
			this.rate = rate;
			if (current != null) {
				current.setRate(rate);
			}
		}
		publishStatus(status());
	}

	public double rate() {
		synchronized (lock) {
			return rate;
		}
	}

	public void setVolume(double volume) {
		double next = Double.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1;
		synchronized (lock) {
			this.volume = next;
			if (track != null) {
				track.setVolume(next);
			}
		}
	}

	public double volume() {
		synchronized (lock) {
			return volume;
		}
	}

	public boolean isPlaying() {
		synchronized (lock) {
			return state == PlaybackState.PLAYING;
		}
	}

	/** The current snapshot for the player controls; never null. */
	public PlaybackStatus status() {
		synchronized (lock) {
			return statusLocked();
		}
	}

	public double durationSeconds() {
		synchronized (lock) {
			return durationLocked();
		}
	}

	private double durationLocked() {
		Media current = media;
		return current == null ? 0 : current.durationSeconds();
	}

	/** Subscribes to playhead updates; invoked from the pump thread. */
	public AutoCloseable onTime(Consumer<PlaybackTime> listener) {
		timeListeners.add(listener);
		return () -> timeListeners.remove(listener);
	}

	/** Subscribes to status changes (play/pause/seek/rate/end). */
	public AutoCloseable onStatus(Consumer<PlaybackStatus> listener) {
		statusListeners.add(listener);
		return () -> statusListeners.remove(listener);
	}

	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		stopPump();
		MeetingAudioTrack current;
		synchronized (lock) {
			current = track;
			track = null;
			media = null;
			state = PlaybackState.IDLE;
		}
		if (current != null) {
			closeQuietly(current);
		}
		statusListeners.clear();
		timeListeners.clear();
	}

	// ── pump thread ────────────────────────────────────────────────────────

	private void startPump() {
		AtomicBoolean running = new AtomicBoolean(true);
		synchronized (lock) {
			if (pumping != null) {
				pumping.set(false);
			}
			pumping = running;
		}
		Thread.ofPlatform().daemon().name("gravaai-meeting-audio").start(() -> {
			boolean warned = false;
			long lastSamples = -1;
			while (running.get()) {
				MeetingAudioTrack current = track();
				if (current == null) {
					return;
				}
				try {
					current.pump();
				} catch (RuntimeException error) {
					if (!warned) {
						LOG.log(System.Logger.Level.WARNING, "meeting audio pump failed: " + error);
						warned = true;
					}
					LockSupport.parkNanos(20_000_000L);
					continue;
				}
				PlaybackStatus status = null;
				PlaybackTime time = null;
				boolean finished = false;
				synchronized (lock) {
					if (closed || state != PlaybackState.PLAYING) {
						return;
					}
					double position = positionLocked(current);
					time = new PlaybackTime(position, position, null);
					if (current.ended() || position >= durationLocked() - END_EPSILON_SECONDS) {
						state = PlaybackState.ENDED;
						baseSeconds = durationLocked();
						baseSamples = current.samplesWritten();
						status = statusLocked();
						finished = true;
					}
				}
				publishTime(time);
				if (finished) {
					stopPump();
					publishStatus(status);
					return;
				}
				// A drained-but-not-yet-reported track (or a blocked decoder) must not busy-spin.
				long samples = current.samplesWritten();
				if (samples == lastSamples) {
					LockSupport.parkNanos(20_000_000L);
				}
				lastSamples = samples;
			}
		});
	}

	private void stopPump() {
		synchronized (lock) {
			if (pumping != null) {
				pumping.set(false);
				pumping = null;
			}
		}
	}

	private @Nullable MeetingAudioTrack track() {
		synchronized (lock) {
			return track;
		}
	}

	private double positionLocked(MeetingAudioTrack current) {
		// One second of output covers `rate` seconds of the meeting (atempo): the playhead is a
		// media position, so the transcript highlight and a later seek/resume stay on the audio.
		double played = Math.max(0, current.samplesWritten() - baseSamples)
				/ (double) PlaybackAudioProducer.SAMPLE_RATE;
		return Math.min(durationLocked(), baseSeconds + played * rate);
	}

	private PlaybackStatus statusLocked() {
		MeetingAudioTrack current = track;
		double timeline = state == PlaybackState.ENDED
				? durationLocked()
				: current == null ? baseSeconds : positionLocked(current);
		return new PlaybackStatus(state, Math.max(0, timeline), durationLocked(), rate);
	}

	private PlaybackTime timeLocked() {
		MeetingAudioTrack current = track;
		double position = current == null ? baseSeconds : positionLocked(current);
		return new PlaybackTime(position, position, null);
	}

	/** The current playhead snapshot for listeners outside the transport lock. */
	private PlaybackTime time() {
		synchronized (lock) {
			return timeLocked();
		}
	}

	// ── delivery ───────────────────────────────────────────────────────────

	private void publishStatus(@Nullable PlaybackStatus status) {
		if (status == null) {
			return;
		}
		for (Consumer<PlaybackStatus> listener : statusListeners) {
			try {
				listener.accept(status);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "status listener failed: " + error);
			}
		}
	}

	private void publishTime(@Nullable PlaybackTime time) {
		if (time == null) {
			return;
		}
		for (Consumer<PlaybackTime> listener : timeListeners) {
			try {
				listener.accept(time);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "time listener failed: " + error);
			}
		}
	}

	private static void closeQuietly(@Nullable AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "could not close the meeting audio track: " + error);
		}
	}
}
