package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.media.audio.AudioPeaks;
import dev.gravaai.ui.editor.timeline.CachingWaveformProvider;
import javafx.scene.Node;

/**
 * The real {@link MeetingMediaHost}: builds a {@link MeetingPlayerPanel} per meeting (the previous
 * panel's transport is closed when a new one replaces it) and fans the current panel's playhead
 * out to the transcript-highlight listeners.
 *
 * <p>Owns the waveform loader: one background executor decodes peaks (with the persistent
 * {@code .peaks} cache beside the recording) for every meeting the user plays, shared across
 * panels. {@link #close()} shuts it down.
 */
public final class MeetingPlayerHost implements MeetingMediaHost {

	private final Path ffmpegPath;
	private final ExecutorService waveformExecutor = Threads.daemon("gravaai-meeting-waveforms");
	private final CachingWaveformProvider waveforms = new CachingWaveformProvider(
			MeetingPlayerHost::cachePath, AudioPeaks::loadPeaks, AudioPeaks.PEAKS_PER_SECOND,
			waveformExecutor);
	private final List<Consumer<Double>> timeListeners = new CopyOnWriteArrayList<>();
	private @Nullable MeetingPlayerPanel panel;
	private volatile boolean closed;

	public MeetingPlayerHost(Path ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
	}

	@Override
	public Node createPlayer(Path mediaPath, boolean hasVideo) {
		if (panel != null) {
			panel.close();
		}
		panel = new MeetingPlayerPanel(mediaPath, ffmpegPath, waveforms, this::publishTime);
		return panel.ui();
	}

	@Override
	public void seek(double seconds) {
		MeetingPlayerPanel current = panel;
		if (current != null) {
			current.seek(seconds);
		}
	}

	@Override
	public void pausePlayback() {
		MeetingPlayerPanel current = panel;
		if (current != null) {
			current.pausePlayback();
		}
	}

	@Override
	public boolean isPlaying() {
		MeetingPlayerPanel current = panel;
		return current != null && current.isPlaying();
	}

	@Override
	public AutoCloseable onTime(Consumer<Double> listener) {
		timeListeners.add(listener);
		return () -> timeListeners.remove(listener);
	}

	private void publishTime(double seconds) {
		for (Consumer<Double> listener : timeListeners) {
			listener.accept(seconds);
		}
	}

	/** The persistent peaks cache lives beside the recording, like the timeline's. */
	private static Path cachePath(String audioPath) {
		Path audio = Path.of(audioPath).toAbsolutePath().normalize();
		return audio.resolveSibling(audio.getFileName().toString() + ".peaks");
	}

	/** Stops the waveform loader; the host lives as long as the app shell. */
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		waveforms.close();
		waveformExecutor.shutdownNow();
	}
}
