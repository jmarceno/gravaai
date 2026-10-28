package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dev.gravaai.core.event.UiEvents;
import dev.gravaai.media.playback.FrameConsumer;
import dev.gravaai.media.playback.PlaybackController;
import dev.gravaai.media.playback.PlaybackMedia;
import dev.gravaai.media.playback.PlaybackState;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.media.playback.PlaybackTime;
import dev.gravaai.ui.editor.EditorPlaybackPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PlaybackControllerPort}: engine status -> EditorPlaybackPort state (P2.5). */
class PlaybackControllerPortTest {

	@DisplayName("status, seek/skip clamping and volume feed the playback bar")
	@Test
	void stateAndTransport() {
		FakeController engine = new FakeController(10);
		PlaybackControllerPort port = new PlaybackControllerPort(engine, Runnable::run);
		EditorPlaybackPort.PlaybackState state = port.state();
		assertEquals(false, state.isPlaying());
		assertEquals(0, state.currentTimeSeconds(), 1e-9);
		assertEquals(10, state.durationSeconds(), 1e-9);
		assertEquals(1, state.volume(), 1e-9);

		port.togglePlayPause();
		assertTrue(port.state().isPlaying());
		assertEquals(PlaybackState.PLAYING, engine.status().state());

		port.seek(4.5);
		assertEquals(4.5, port.state().currentTimeSeconds(), 1e-9);
		port.seek(-3);
		assertEquals(0, port.state().currentTimeSeconds(), 1e-9);
		port.seek(99);
		assertEquals(10, port.state().currentTimeSeconds(), 1e-9);

		port.skip(-2.5);
		assertEquals(7.5, port.state().currentTimeSeconds(), 1e-9);

		port.setVolume(0.4);
		assertEquals(0.4, port.state().volume(), 1e-9);
		port.setVolume(4);
		assertEquals(1, port.state().volume(), 1e-9);
	}

	@DisplayName("engine events are published through the port's UiEvents")
	@Test
	void changesPublished() {
		FakeController engine = new FakeController(5);
		PlaybackControllerPort port = new PlaybackControllerPort(engine, Runnable::run);
		List<EditorPlaybackPort.PlaybackState> published = new CopyOnWriteArrayList<>();
		port.changes().subscribe(published::add);
		engine.setPlaying(true);
		assertTrue(published.size() >= 1);
		assertTrue(published.get(published.size() - 1).isPlaying());
	}

	@DisplayName("the transport state is cached: a busy engine lock never reaches the FX refresh")
	@Test
	void cachedStateToleratesBusyEngine() {
		FakeController engine = new FakeController(10);
		PlaybackControllerPort port = new PlaybackControllerPort(engine, Runnable::run);
		// Warm the cache (startup read) and remember it.
		EditorPlaybackPort.PlaybackState warm = port.state();
		assertEquals(10, warm.durationSeconds(), 1e-9);
		engine.setBusy(true);
		int callsBefore = engine.statusCalls();
		// PlaybackControls.refresh() calls this on every engine event; it must not take the lock.
		EditorPlaybackPort.PlaybackState during = port.state();
		assertEquals(warm, during);
		assertEquals(callsBefore, engine.statusCalls());
		engine.setBusy(false);
	}

	@DisplayName("rate is passed to the engine and remembered")
	@Test
	void rate() {
		FakeController engine = new FakeController(5);
		PlaybackControllerPort port = new PlaybackControllerPort(engine, Runnable::run);
		port.setRate(2.5);
		assertEquals(2.5, engine.rate(), 1e-9);
		assertEquals(2.5, port.rate(), 1e-9);
	}

	/** Minimal engine double: no media, a fixed duration, synchronous listeners. */
	private static final class FakeController implements PlaybackController {
		private final double duration;
		private final List<Consumer<PlaybackStatus>> statusListeners = new CopyOnWriteArrayList<>();
		private final List<Consumer<PlaybackTime>> timeListeners = new CopyOnWriteArrayList<>();
		private final List<FrameConsumer> frameListeners = new CopyOnWriteArrayList<>();
		private PlaybackStatus status;
		private double volume = 1;
		private int statusCalls;
		private boolean busy;
		private boolean closed;

		FakeController(double duration) {
			this.duration = duration;
			this.status = new PlaybackStatus(PlaybackState.IDLE, 0, duration, 1);
		}

		void setPlaying(boolean playing) {
			status = new PlaybackStatus(playing ? PlaybackState.PLAYING : PlaybackState.PAUSED,
					status.timelineSeconds(), duration, status.rate());
			statusListeners.forEach(listener -> listener.accept(status));
			timeListeners.forEach(listener -> listener.accept(
					new PlaybackTime(status.timelineSeconds(), status.timelineSeconds(), "clip-1")));
		}

		@Override
		public void setMedia(PlaybackMedia media) {
			// not needed for the adapter tests
		}

		@Override
		public void play() {
			setPlaying(true);
		}

		@Override
		public void pause() {
			setPlaying(false);
		}

		@Override
		public void togglePlayPause() {
			setPlaying(status.state() != PlaybackState.PLAYING);
		}

		@Override
		public void seek(double timelineSeconds) {
			status = new PlaybackStatus(PlaybackState.PAUSED, timelineSeconds, duration, status.rate());
			statusListeners.forEach(listener -> listener.accept(status));
		}

		@Override
		public void scrub(double timelineSeconds) {
			seek(timelineSeconds);
		}

		@Override
		public void setRate(double rate) {
			status = new PlaybackStatus(status.state(), status.timelineSeconds(), duration, rate);
			statusListeners.forEach(listener -> listener.accept(status));
		}

		@Override
		public double rate() {
			return status.rate();
		}

		@Override
		public boolean isPlaying() {
			return status.state() == PlaybackState.PLAYING;
		}

		@Override
		public PlaybackStatus status() {
			statusCalls++;
			if (busy) {
				throw new IllegalStateException("engine lock busy");
			}
			return status;
		}

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
		public void setVolume(double nextVolume) {
			volume = Math.max(0, Math.min(1, nextVolume));
		}

		@Override
		public double volume() {
			return volume;
		}

		void setBusy(boolean busy) {
			this.busy = busy;
		}

		int statusCalls() {
			return statusCalls;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}