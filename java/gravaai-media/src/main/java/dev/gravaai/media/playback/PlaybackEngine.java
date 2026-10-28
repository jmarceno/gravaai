package dev.gravaai.media.playback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.media.compose.RgbaFrame;

/**
 * The preview playback engine (plan §5.9, P2.3): a clock-driven transport that maps the editor
 * timeline through the clip sequence to source timestamps, drives the frame sources, coalesces
 * scrubs and publishes frames/status/time to the preview panel.
 *
 * <p>Threading and locks. Transport state lives under {@code lock}, which is only ever held for
 * bookkeeping, never for a decode: play/pause/status from the FX thread return immediately even
 * while a 1080p60 + webcam frame is decoding (pause used to wait half a second for the tick's
 * decode). Decoding is serialised by {@code decodeLock} and validated afterwards with a transport
 * generation, so a frame decoded for a position the user already left is dropped instead of
 * delivered. Frames are delivered while {@code decodeLock} is held because decoder buffers are only
 * valid until the next decode.
 *
 * <p>Audio runs on its own thread in production ({@link Dependencies#production}): its blocking
 * PulseAudio write paces itself and feeds the audio-master clock, so the video tick really runs at
 * 60 Hz instead of being throttled by each 40 ms write. Tests use the legacy mode where the tick
 * pumps the audio track, which keeps every step deterministic. Paused scrubs decode on the
 * {@link Dependencies#worker()} (latest wins), never on the caller's thread.
 */
public final class PlaybackEngine implements PlaybackController {

	private static final System.Logger LOG = System.getLogger("gravaai.media.playback");
	private static final double END_EPSILON_MS = 1e-7;
	/** Bounded first-frame wait: setMedia must never hang the editor/controls on a decode stall. */
	private static final long FIRST_FRAME_TIMEOUT_MS = 6_000;

	/** Everything the engine needs from its environment; {@link #production} is the app shape. */
	public record Dependencies(
			FrameSourceProvider frameSources,
			PlaybackClock clock,
			RenderScheduler scheduler,
			boolean clockIsRateScaled,
			Executor worker,
			boolean audioThread) {

		public Dependencies {
			if (frameSources == null || clock == null || scheduler == null || worker == null) {
				throw new IllegalArgumentException(
						"frameSources, clock, scheduler and worker are required");
			}
		}

		/** Deterministic shape for tests: scrubs decode inline and the tick pumps the audio. */
		public Dependencies(FrameSourceProvider frameSources, PlaybackClock clock,
				RenderScheduler scheduler, boolean clockIsRateScaled) {
			this(frameSources, clock, scheduler, clockIsRateScaled, Runnable::run, false);
		}

		/** Monotonic clock + 60 Hz thread scheduler (no audio track). */
		public static Dependencies defaults(FrameSourceProvider frameSources) {
			return new Dependencies(frameSources, new MonotonicMediaClock(System::nanoTime),
					new ThreadRenderScheduler(60), false, scrubWorker(), true);
		}

		/** The editor engine: 60 Hz video tick, dedicated audio thread, async paused scrubs. */
		public static Dependencies production(FrameSourceProvider frameSources, PlaybackClock clock,
				boolean clockIsRateScaled) {
			return new Dependencies(frameSources, clock, new ThreadRenderScheduler(60),
					clockIsRateScaled, scrubWorker(), true);
		}

		private static Executor scrubWorker() {
			return java.util.concurrent.Executors.newSingleThreadExecutor(
					Thread.ofPlatform().daemon().name("gravaai-playback-scrub").factory());
		}
	}

	private final FrameSourceProvider frameSources;
	private final PlaybackClock clock;
	private final RenderScheduler scheduler;
	private final boolean clockIsRateScaled;
	private final Executor worker;
	private final boolean audioThreadMode;
	private final Object lock = new Object();
	private final Object decodeLock = new Object();
	private final ScrubCoalescer scrub = new ScrubCoalescer();
	private final AtomicBoolean scrubScheduled = new AtomicBoolean();
	private final List<FrameConsumer> frameListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<PlaybackStatus>> statusListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<PlaybackTime>> timeListeners = new CopyOnWriteArrayList<>();

	private PlaybackMedia media;
	private volatile PlaybackAudioTrack audioTrack;
	private FrameSource videoSource;
	private FrameSource webcamSource;
	private double durationSeconds;
	private double anchorTimelineSeconds;
	private double anchorClockSeconds;
	private PlaybackState state = PlaybackState.IDLE;
	private double rate = 1;
	private boolean loop;
	private double volume = 1;
	private long frameIndex;
	private boolean closed;
	/** Bumped by every transport change; a decode for an older generation is dropped. */
	private long generation;
	/**
	 * Whether audio counters may feed the clock. Off between a rebase and the matching audio seek,
	 * so a write from before the seek never moves the freshly rebased clock.
	 */
	private boolean audioFeeds;
	/** The running audio pump thread's stop flag (audio-thread mode). */
	private AtomicBoolean audioRunning;

	public PlaybackEngine(Dependencies dependencies) {
		this.frameSources = dependencies.frameSources();
		this.clock = dependencies.clock();
		this.scheduler = dependencies.scheduler();
		this.clockIsRateScaled = dependencies.clockIsRateScaled();
		this.worker = dependencies.worker();
		this.audioThreadMode = dependencies.audioThread();
	}

	/** A production engine: monotonic clock, 60 Hz scheduler, decoder provider supplied by P2.5/app. */
	public static PlaybackEngine createDefault(FrameSourceProvider frameSources) {
		return new PlaybackEngine(Dependencies.defaults(frameSources));
	}

	/**
	 * Attaches the audio track (plan §5.9). When the clock is an {@link AudioMediaClock} in
	 * rate-scaled mode, the track's write/latency counters drive it while playing.
	 */
	public void setAudioTrack(PlaybackAudioTrack audioTrack) {
		double timeline;
		double currentRate;
		double currentVolume;
		boolean playing;
		synchronized (lock) {
			this.audioTrack = audioTrack;
			audioFeeds = false;
			timeline = timelineLocked();
			currentRate = rate;
			currentVolume = volume;
			playing = state == PlaybackState.PLAYING;
		}
		if (audioTrack != null) {
			audioTrack.setVolume(currentVolume);
			audioTrack.setRate(currentRate);
			audioTrack.seek(timeline);
			if (playing) {
				audioTrack.resume();
			}
		}
		synchronized (lock) {
			if (this.audioTrack == audioTrack) {
				rebaseLocked(timeline);
				audioFeeds = true;
			}
		}
	}

	// ── media ──────────────────────────────────────────────────────────────

	@Override
	public void setMedia(PlaybackMedia media) {
		if (media == null) {
			throw new IllegalArgumentException("media must not be null");
		}
		stopAudioThread();
		// Open the new decoders before taking any lock: opening probes the file.
		FrameSource nextVideo = frameSources.open(media.videoPath());
		FrameSource nextWebcam = media.webcamPath() == null ? null : frameSources.open(media.webcamPath());
		FramePlan plan;
		List<FrameSource> previous = new ArrayList<>(2);
		synchronized (decodeLock) {
			synchronized (lock) {
				previous.add(videoSource);
				previous.add(webcamSource);
				this.media = media;
				this.videoSource = nextVideo;
				this.webcamSource = nextWebcam;
				this.durationSeconds = Clips.getTimelineDurationMs(media.clips(), media.sourceDurationMs()) / 1000.0;
				this.frameIndex = 0;
				this.state = PlaybackState.PAUSED;
				this.generation++;
				this.audioFeeds = false;
				this.clock.setRunning(false);
				rebaseLocked(0);
				plan = planLocked(0);
			}
		}
		scheduler.stop();
		closeAll(previous);
		PlaybackAudioTrack track = audioTrack;
		if (track != null) {
			track.pause();
			track.setVolume(volume);
			track.setRate(rate);
			track.seek(0);
		}
		synchronized (lock) {
			audioFeeds = true;
		}
		// The first frame decodes outside the engine lock (PlaybackControls.refresh()/status() on
		// the FX thread must never wait for the decode worker: that was the packaged-app hang). The
		// wait is bounded, and a failure degrades to a gap frame instead of blocking setMedia.
		decodeAndDeliver(plan, FIRST_FRAME_TIMEOUT_MS, true);
	}

	/**
	 * Swaps the clip sequence of the current media (a cut, split, mute or clip speed in the
	 * editor) without reopening the decoders; the duration is recomputed and the frame at the
	 * clamped playhead is shown again. A no-op before media is attached or when nothing changed.
	 */
	public void updateClips(List<ClipRegion> clips) {
		double target;
		synchronized (lock) {
			if (closed || media == null) {
				return;
			}
			List<ClipRegion> next = clips == null ? List.of() : List.copyOf(clips);
			if (next.equals(media.clips())) {
				return;
			}
			target = timelineLocked();
			media = new PlaybackMedia(media.videoPath(), media.webcamPath(), media.webcamTimeOffsetMs(),
					next, media.sourceDurationMs(), media.webcamVisibleRanges());
			durationSeconds = Clips.getTimelineDurationMs(next, media.sourceDurationMs()) / 1000.0;
		}
		seek(target);
	}

	// ── transport ──────────────────────────────────────────────────────────

	@Override
	public void play() {
		PlaybackStatus status;
		double startTimeline;
		long token;
		synchronized (lock) {
			if (closed || media == null || state == PlaybackState.PLAYING) {
				return;
			}
			if (state == PlaybackState.ENDED) {
				rebaseLocked(0);
			} else {
				rebaseLocked(timelineLocked());
			}
			state = PlaybackState.PLAYING;
			token = ++generation;
			audioFeeds = false;
			clock.setRunning(true);
			startTimeline = timelineLocked();
			status = statusLocked();
		}
		PlaybackAudioTrack track = audioTrack;
		if (track != null) {
			track.setRate(rate);
			track.setVolume(volume);
			track.seek(startTimeline);
			track.resume();
		}
		synchronized (lock) {
			if (generation != token || state != PlaybackState.PLAYING) {
				return;
			}
			audioFeeds = true;
		}
		publishStatus(status);
		startAudioThread();
		scheduler.start(this::tick);
	}

	@Override
	public void pause() {
		PlaybackStatus status;
		PlaybackTime time;
		synchronized (lock) {
			if (state != PlaybackState.PLAYING) {
				return;
			}
			double timeline = timelineLocked();
			clock.setRunning(false);
			rebaseLocked(timeline);
			state = PlaybackState.PAUSED;
			generation++;
			audioFeeds = false;
			status = statusLocked();
			time = mapTimeLocked(timeline);
		}
		// Silence first: the flush is immediate, the decoder shutdown happens off this thread.
		stopAudioThread();
		PlaybackAudioTrack track = audioTrack;
		if (track != null) {
			track.pause();
		}
		scheduler.stop();
		publishStatus(status);
		publishTime(time);
	}

	@Override
	public void togglePlayPause() {
		if (isPlaying()) {
			pause();
		} else {
			play();
		}
	}

	@Override
	public void seek(double timelineSeconds) {
		FramePlan plan;
		boolean wasPlaying;
		double target;
		long token;
		synchronized (lock) {
			if (closed || media == null) {
				return;
			}
			wasPlaying = state == PlaybackState.PLAYING;
			scrub.takeLatest();
			target = clampTimelineLocked(timelineSeconds);
			rebaseLocked(target);
			state = wasPlaying ? PlaybackState.PLAYING : PlaybackState.PAUSED;
			token = ++generation;
			audioFeeds = false;
			plan = planLocked(target);
		}
		PlaybackAudioTrack track = audioTrack;
		if (track != null) {
			track.setVolume(volume);
			track.setRate(rate);
			track.seek(plan.timelineSeconds());
		}
		synchronized (lock) {
			if (generation == token) {
				rebaseLocked(target);
				audioFeeds = true;
			}
		}
		decodeAndDeliver(plan, FIRST_FRAME_TIMEOUT_MS, true);
		if (wasPlaying) {
			clock.setRunning(true);
			scheduler.start(this::tick);
		} else {
			clock.setRunning(false);
			scheduler.stop();
		}
	}

	/**
	 * Latest-wins scrub. While playing, the next tick jumps to the newest position. While paused,
	 * the playhead time is published at once and the frame decodes on the worker, so a timeline
	 * drag never blocks the FX thread and the preview follows the pointer.
	 */
	@Override
	public void scrub(double timelineSeconds) {
		PlaybackTime time;
		synchronized (lock) {
			if (closed || media == null) {
				return;
			}
			scrub.submit(timelineSeconds);
			if (state == PlaybackState.PLAYING) {
				return;
			}
			double target = clampTimelineLocked(timelineSeconds);
			rebaseLocked(target);
			state = PlaybackState.PAUSED;
			// No generation bump: during a continuous drag every new position would make the
			// worker's in-flight frame stale, so nothing showed until the pointer was released.
			// The worker decodes the newest position next; seek/play/pause still drop its frame.
			time = mapTimeLocked(target);
		}
		publishTime(time);
		if (scrubScheduled.compareAndSet(false, true)) {
			worker.execute(this::drainScrubs);
		}
	}

	/** Worker: decodes the newest pending scrub until none is left. */
	private void drainScrubs() {
		while (true) {
			scrubScheduled.set(false);
			FramePlan plan;
			PlaybackStatus status;
			synchronized (lock) {
				if (closed || media == null || state == PlaybackState.PLAYING) {
					return;
				}
				ScrubCoalescer.Pending pending = scrub.takeLatest();
				if (pending == null) {
					return;
				}
				double target = clampTimelineLocked(pending.timelineSeconds());
				rebaseLocked(target);
				plan = planLocked(target);
				status = statusLocked();
			}
			// A seek, play or pause bumps the generation, so this frame is dropped if stale; a
			// newer scrub does not, so the preview keeps following the pointer mid-drag.
			decodeAndDeliver(plan, 0, true);
			publishStatus(status);
		}
	}

	@Override
	public void setRate(double rate) {
		PlaybackStatus status;
		synchronized (lock) {
			if (!Double.isFinite(rate) || rate <= 0) {
				return;
			}
			double timeline = timelineLocked();
			rebaseLocked(timeline);
			this.rate = rate;
			status = statusLocked();
		}
		PlaybackAudioTrack track = audioTrack;
		if (track != null) {
			track.setRate(rate);
		}
		publishStatus(status);
	}

	@Override
	public double rate() {
		synchronized (lock) {
			return rate;
		}
	}

	@Override
	public boolean isPlaying() {
		synchronized (lock) {
			return state == PlaybackState.PLAYING;
		}
	}

	@Override
	public PlaybackStatus status() {
		synchronized (lock) {
			return statusLocked();
		}
	}

	@Override
	public void setLoop(boolean loop) {
		synchronized (lock) {
			this.loop = loop;
		}
	}

	@Override
	public boolean isLooping() {
		synchronized (lock) {
			return loop;
		}
	}

	@Override
	public void setVolume(double volume) {
		double next;
		synchronized (lock) {
			next = Double.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1;
			this.volume = next;
		}
		PlaybackAudioTrack track = audioTrack;
		if (track != null) {
			track.setVolume(next);
		}
	}

	@Override
	public double volume() {
		synchronized (lock) {
			return volume;
		}
	}

	// ── listeners ──────────────────────────────────────────────────────────

	@Override
	public AutoCloseable onFrame(FrameConsumer consumer) {
		frameListeners.add(consumer);
		return () -> frameListeners.remove(consumer);
	}

	@Override
	public AutoCloseable onStatus(Consumer<PlaybackStatus> listener) {
		statusListeners.add(listener);
		return () -> statusListeners.remove(listener);
	}

	@Override
	public AutoCloseable onTime(Consumer<PlaybackTime> listener) {
		timeListeners.add(listener);
		return () -> timeListeners.remove(listener);
	}

	@Override
	public void close() {
		List<FrameSource> sources = new ArrayList<>();
		synchronized (lock) {
			closed = true;
			state = PlaybackState.IDLE;
			generation++;
		}
		stopAudioThread();
		scheduler.stop();
		clock.setRunning(false);
		synchronized (decodeLock) {
			synchronized (lock) {
				sources.add(videoSource);
				sources.add(webcamSource);
				videoSource = null;
				webcamSource = null;
				media = null;
			}
		}
		PlaybackAudioTrack track = audioTrack;
		audioTrack = null;
		if (track != null) {
			try {
				track.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "could not close the audio track: " + error);
			}
		}
		closeAll(sources);
		frameListeners.clear();
		statusListeners.clear();
		timeListeners.clear();
	}

	// ── audio ──────────────────────────────────────────────────────────────

	/** Pumps the audio track once and feeds the audio-master clock when allowed. */
	private void pumpAudioOnce() {
		PlaybackAudioTrack track = audioTrack;
		if (track == null) {
			return;
		}
		track.pump();
		if (clock instanceof AudioMediaClock audioClock) {
			synchronized (lock) {
				if (audioFeeds && state == PlaybackState.PLAYING) {
					audioClock.onSamplesWritten(track.samplesWritten(), track.latencyMicros());
				}
			}
		}
	}

	private void startAudioThread() {
		if (!audioThreadMode || audioTrack == null) {
			return;
		}
		AtomicBoolean running = new AtomicBoolean(true);
		synchronized (lock) {
			if (audioRunning != null) {
				audioRunning.set(false);
			}
			audioRunning = running;
		}
		Thread.ofPlatform().daemon().name("gravaai-playback-audio").start(() -> {
			boolean warned = false;
			while (running.get()) {
				try {
					// The PulseAudio write blocks for about one chunk, which paces this loop.
					pumpAudioOnce();
				} catch (RuntimeException error) {
					if (!warned) {
						LOG.log(System.Logger.Level.WARNING, "preview audio pump failed: " + error);
						warned = true;
					}
					java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
				}
			}
		});
	}

	private void stopAudioThread() {
		synchronized (lock) {
			if (audioRunning != null) {
				audioRunning.set(false);
				audioRunning = null;
			}
		}
	}

	// ── render loop ────────────────────────────────────────────────────────

	private void tick() {
		if (!audioThreadMode) {
			try {
				pumpAudioOnce();
			} catch (RuntimeException error) {
				// An audio failure must not stop the video.
				LOG.log(System.Logger.Level.DEBUG, () -> "preview audio pump failed: " + error);
			}
		}
		FramePlan plan;
		boolean ended = false;
		synchronized (lock) {
			if (closed || state != PlaybackState.PLAYING) {
				return;
			}
			ScrubCoalescer.Pending pending = scrub.takeLatest();
			if (pending != null) {
				double target = clampTimelineLocked(pending.timelineSeconds());
				rebaseLocked(target);
			}
			double timeline = timelineLocked();
			if (timeline >= durationSeconds) {
				if (loop) {
					rebaseLocked(0);
					plan = planLocked(0);
				} else {
					state = PlaybackState.ENDED;
					generation++;
					clock.setRunning(false);
					rebaseLocked(durationSeconds);
					plan = planLocked(durationSeconds);
					ended = true;
				}
			} else {
				timeline = skipGapLocked(timeline);
				plan = planLocked(timeline);
			}
		}
		if (ended) {
			stopAudioThread();
			PlaybackAudioTrack track = audioTrack;
			if (track != null) {
				track.pause();
			}
			scheduler.stop();
		}
		decodeAndDeliver(plan, 0, !ended);
	}

	/**
	 * Decodes outside the engine lock and delivers if the transport did not move meanwhile.
	 * {@code requireCurrent=false} delivers regardless (end-of-media frame, scrub worker).
	 */
	private void decodeAndDeliver(FramePlan plan, long timeoutMs, boolean requireCurrent) {
		synchronized (decodeLock) {
			Built built;
			try {
				built = decode(plan, timeoutMs);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING,
						"preview frame could not be decoded at " + plan.timelineSeconds() + " s: "
								+ error);
				built = new Built(PlaybackFrame.gap(plan.frameIndex(), plan.timelineSeconds()),
						new PlaybackTime(plan.timelineSeconds(), null, null));
			}
			PlaybackStatus status;
			synchronized (lock) {
				if (closed || (requireCurrent && generation != plan.generation())) {
					return;
				}
				status = statusLocked();
			}
			deliver(built);
			publishStatus(status);
		}
	}

	/** During playback an empty timeline span is skipped; paused seeks keep the gap position. */
	private double skipGapLocked(double timeline) {
		if (media == null || findPreviewClipLocked(timeline * 1000) != null) {
			return timeline;
		}
		List<ClipRegion> sorted = Clips.sortClipRegions(media.clips());
		for (ClipRegion clip : sorted) {
			if (clip.startMs() / 1000.0 > timeline) {
				rebaseLocked(clip.startMs() / 1000.0);
				return clip.startMs() / 1000.0;
			}
		}
		return durationSeconds;
	}

	// ── timeline/source mapping ────────────────────────────────────────────

	private double timelineLocked() {
		if (clockIsRateScaled) {
			return Math.max(0, clock.positionSeconds());
		}
		return anchorTimelineSeconds
				+ Math.max(0, clock.positionSeconds() - anchorClockSeconds) * rate;
	}

	private void rebaseLocked(double timelineSeconds) {
		double target = clampTimelineLocked(timelineSeconds);
		anchorTimelineSeconds = target;
		clock.resetTo(clockIsRateScaled ? target : 0);
		anchorClockSeconds = clock.positionSeconds();
	}

	private double clampTimelineLocked(double seconds) {
		if (!Double.isFinite(seconds)) {
			return 0;
		}
		return Math.max(0, Math.min(seconds, durationSeconds));
	}

	/** The playhead may hold the final clip at exactly the timeline end (not a black gap). */
	private ClipRegion findPreviewClipLocked(double timeMs) {
		if (media == null) {
			return null;
		}
		ClipRegion active = Clips.findClipAtTimelineTime(timeMs, media.clips());
		if (active != null) {
			return active;
		}
		List<ClipRegion> sorted = Clips.sortClipRegions(media.clips());
		if (sorted.isEmpty()) {
			return null;
		}
		ClipRegion last = sorted.get(sorted.size() - 1);
		return Math.abs(timeMs - last.endMs()) < END_EPSILON_MS ? last : null;
	}

	/** Maps a timeline position to what must be decoded; cheap, runs under the engine lock. */
	private FramePlan planLocked(double timelineSeconds) {
		double timelineMs = timelineSeconds * 1000;
		ClipRegion clip = findPreviewClipLocked(timelineMs);
		long index = frameIndex++;
		if (clip == null) {
			return new FramePlan(generation, index, timelineSeconds, null, 0, null, null, null, false);
		}
		double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		double sourceMs = Clips.getClipSourceStartMs(clip) + (timelineMs - clip.startMs()) * speed;
		if (timelineMs >= clip.endMs() - END_EPSILON_MS) {
			// Clip out-points are exclusive: request a frame just inside the clip.
			sourceMs = Math.max(Clips.getClipSourceStartMs(clip), sourceMs - 0.001);
		}
		double sourceSeconds = Math.max(0, sourceMs / 1000.0);
		Double webcamSourceSeconds = null;
		boolean webcamVisible = false;
		if (webcamSource != null) {
			webcamSourceSeconds = WebcamSync.getWebcamMediaTargetTimeSeconds(
					timelineSeconds, webcamSource.durationSeconds(), (double) media.webcamTimeOffsetMs());
			webcamVisible = WebcamSync.isWebcamVisibleAtSourceTime(
					media.webcamVisibleRanges(), webcamSourceSeconds);
		}
		return new FramePlan(generation, index, timelineSeconds, clip.id(), sourceSeconds,
				videoSource, webcamSource, webcamSourceSeconds, webcamVisible);
	}

	/** Decodes a plan; the caller holds {@code decodeLock}. */
	private static Built decode(FramePlan plan, long frameTimeoutMs) {
		if (plan.clipId() == null) {
			return new Built(PlaybackFrame.gap(plan.frameIndex(), plan.timelineSeconds()),
					new PlaybackTime(plan.timelineSeconds(), null, null));
		}
		RgbaFrame sourceFrame = null;
		if (plan.video() != null) {
			sourceFrame = frameTimeoutMs > 0
					? plan.video().frameAt(plan.sourceSeconds(), frameTimeoutMs)
					: plan.video().frameAt(plan.sourceSeconds());
		}
		RgbaFrame webcamFrame = null;
		if (plan.webcam() != null && plan.webcamVisible()) {
			try {
				webcamFrame = plan.webcam().frameAt(plan.webcamSeconds());
			} catch (RuntimeException error) {
				// A webcam decode error must not blank the screen video of the same frame.
				LOG.log(System.Logger.Level.WARNING, "webcam frame could not be decoded at "
						+ plan.webcamSeconds() + " s: " + error);
			}
		}
		PlaybackFrame frame = new PlaybackFrame(
				plan.frameIndex(),
				plan.timelineSeconds(),
				plan.sourceSeconds(),
				plan.clipId(),
				sourceFrame,
				webcamFrame,
				plan.webcamVisible());
		return new Built(frame, new PlaybackTime(plan.timelineSeconds(), plan.sourceSeconds(),
				plan.clipId()));
	}

	private PlaybackTime mapTimeLocked(double timelineSeconds) {
		double timelineMs = timelineSeconds * 1000;
		ClipRegion clip = findPreviewClipLocked(timelineMs);
		if (clip == null) {
			return new PlaybackTime(timelineSeconds, null, null);
		}
		double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		double sourceMs = Clips.getClipSourceStartMs(clip) + (timelineMs - clip.startMs()) * speed;
		return new PlaybackTime(timelineSeconds, Math.max(0, sourceMs / 1000.0), clip.id());
	}

	private PlaybackStatus statusLocked() {
		double timeline = state == PlaybackState.ENDED ? durationSeconds : timelineLocked();
		return new PlaybackStatus(state, Math.max(0, timeline), durationSeconds, rate);
	}

	// ── delivery ───────────────────────────────────────────────────────────

	private void deliver(Built built) {
		if (built == null) {
			return;
		}
		PlaybackFrame frame = built.frame();
		for (FrameConsumer consumer : frameListeners) {
			try {
				consumer.accept(frame);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "frame consumer failed: " + error);
			}
		}
		publishTime(built.time());
	}

	private void publishStatus(PlaybackStatus status) {
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

	private void publishTime(PlaybackTime time) {
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

	private static void closeAll(List<FrameSource> sources) {
		for (FrameSource source : sources) {
			if (source != null) {
				try {
					source.close();
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "could not close a frame source: " + error);
				}
			}
		}
	}

	/** What one frame needs: computed under the engine lock, decoded outside it. */
	private record FramePlan(
			long generation,
			long frameIndex,
			double timelineSeconds,
			String clipId,
			double sourceSeconds,
			FrameSource video,
			FrameSource webcam,
			Double webcamSeconds,
			boolean webcamVisible) {
	}

	private record Built(PlaybackFrame frame, PlaybackTime time) {
	}
}
