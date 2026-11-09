package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.playback.MeetingPlayback;
import dev.gravaai.media.playback.PlaybackState;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.services.meeting.MeetingAudioInputs;
import dev.gravaai.services.meeting.Transcript;
import dev.gravaai.ui.UiThreads;
import dev.gravaai.ui.editor.EditorPlaybackPort;
import dev.gravaai.ui.editor.PlaybackControls;
import dev.gravaai.ui.editor.timeline.CachingWaveformProvider;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Kit;
import dev.gravaai.ui.kit.Popover;
import dev.gravaai.ui.kit.Slider;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * The meeting player panel (port of the {@code <audio>/<video>} controls in {@code MeetingView.tsx}
 * through the {@link MeetingMediaHost} seam): play/pause, a seek bar with the recording's waveform
 * strip, playback speed chips and the timecode. Audio-only for both meeting kinds — the diarized
 * mic/system sidecars are mixed into one stream when they exist, otherwise the recording's own
 * embedded mix plays.
 *
 * <p>The transport ({@link MeetingPlayback}) is created in the background together with the media
 * probe; until it lands the controls stay disabled. Time and status callbacks arrive on the FX
 * thread; {@code timeSink} receives each playhead position (also on the FX thread) so the notes
 * pane can highlight the playing transcript line.
 */
public final class MeetingPlayerPanel extends VBox {

	private static final double WAVEFORM_HEIGHT = 44;
	private static final double WAVEFORM_BAR_PITCH_PX = 3;
	private static final long SLIDER_DRAG_GRACE_MS = 300;

	private final Path mediaPath;
	private final Path ffmpegPath;
	private final CachingWaveformProvider waveforms;
	private final Consumer<Double> timeSink;
	private final Canvas waveform = new Canvas();
	private final Button playButton = new Button("", Button.Variant.GHOST, Button.Size.ICON);
	private final Label currentClock = new Label("00:00:00");
	private final Label durationClock = new Label("00:00:00");
	private final Slider seekSlider = new Slider();
	private final Button rateButton = new Button("1×", Button.Variant.GHOST, Button.Size.SM);
	private final HBox controls = new HBox(8);
	private final Label unavailable = new Label("Playback is unavailable for this recording.");
	private final List<Button> rateChips = new ArrayList<>();
	private @Nullable Popover ratePopover;
	private @Nullable MeetingPlayback playback;
	private volatile boolean closed;
	private boolean ready;
	private boolean sliderSyncing;
	private long lastSliderTouchAt;
	private double durationSeconds;
	private double playheadSeconds;

	public MeetingPlayerPanel(Path mediaPath, Path ffmpegPath, CachingWaveformProvider waveforms,
			Consumer<Double> timeSink) {
		this.mediaPath = mediaPath;
		this.ffmpegPath = ffmpegPath;
		this.waveforms = waveforms;
		this.timeSink = timeSink;
		getStyleClass().add("meeting-player");
		setSpacing(8);
		setStyle("-fx-background-color: rgba(35,40,51,0.5); -fx-background-radius: 6px;"
				+ " -fx-border-color: " + css(Theme.SEPARATOR) + "; -fx-border-radius: 6px;"
				+ " -fx-padding: 10px;");
		setMinHeight(WAVEFORM_HEIGHT + 46);

		waveform.getStyleClass().add("meeting-player__waveform");
		waveform.setOnMouseClicked(this::onWaveformClicked);
		waveforms.setOnPeaksReady(this::renderWaveform);

		playButton.setGraphic(Icon.of(Icons.Play, 16, "fill"));
		playButton.setAccessibleText("Play");
		playButton.setDisable(true);
		playButton.setOnAction(event -> {
			if (playback != null && ready) {
				playback.togglePlayPause();
			}
		});

		currentClock.getStyleClass().add("meeting-player__clock");
		currentClock.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12px; -fx-text-fill: "
				+ css(Theme.FG) + "; -fx-min-width: 62px;");
		durationClock.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12px; -fx-text-fill: "
				+ css(Theme.MUTED) + "; -fx-min-width: 62px;");
		durationClock.setAlignment(Pos.CENTER_RIGHT);

		seekSlider.range(0, 1).step(0.1).hideOutput();
		seekSlider.getStyleClass().add("meeting-player__seek");
		seekSlider.setDisable(true);
		seekSlider.onValueChange(value -> {
			if (sliderSyncing) {
				// The playhead's own syncs are not user touches (they must not arm the grace).
				return;
			}
			lastSliderTouchAt = System.currentTimeMillis();
			// Scrub preview: follow the pointer on the clock, seek on release.
			currentClock.setText(PlaybackControls.formatClock(value));
			playheadSeconds = value;
			renderWaveform();
		});
		seekSlider.onValueCommit(value -> seek(value));

		rateButton.getStyleClass().add("meeting-player__rate");
		dev.gravaai.ui.kit.Tooltip.install(rateButton, "Playback speed");
		rateButton.setOnAction(event -> {
			if (ratePopover == null) {
				ratePopover = new Popover(rateButton);
				ratePopover.content(rateContent());
				ratePopover.side(dev.gravaai.ui.kit.Anchors.Side.TOP);
				ratePopover.align(dev.gravaai.ui.kit.Anchors.Align.END);
			}
			ratePopover.toggle();
		});

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox.setHgrow(seekSlider, Priority.ALWAYS);
		controls.setAlignment(Pos.CENTER_LEFT);
		controls.getChildren().addAll(playButton, currentClock, seekSlider, durationClock,
				rateButton);
		unavailable.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		unavailable.setVisible(false);
		unavailable.setManaged(false);
		unavailable.setWrapText(true);

		getChildren().addAll(waveform, controls, unavailable);
		loadMedia();
	}

	/** The panel root; host it inside the player area of the view. */
	public javafx.scene.Node ui() {
		return this;
	}

	@Override
	protected void layoutChildren() {
		super.layoutChildren();
		// The canvas does not resize itself: keep it as wide as the padded content and repaint.
		javafx.geometry.Insets insets = getInsets();
		double width = Math.max(0, getWidth() - insets.getLeft() - insets.getRight());
		if (waveform.getWidth() != width) {
			waveform.setWidth(width);
		}
		if (waveform.getHeight() != WAVEFORM_HEIGHT) {
			waveform.setHeight(WAVEFORM_HEIGHT);
		}
		renderWaveform();
	}

	/** Plays the recording from the given position (transcript timestamp clicks land here). */
	public void seek(double seconds) {
		MeetingPlayback current = playback;
		if (!ready || current == null) {
			return;
		}
		current.seek(seconds);
		onPlaybackTime(seconds);
	}

	/** Pauses when playing (close-to-tray rule); the position is kept. */
	public void pausePlayback() {
		MeetingPlayback current = playback;
		if (ready && current != null && current.isPlaying()) {
			current.pause();
		}
	}

	public boolean isPlaying() {
		MeetingPlayback current = playback;
		return ready && current != null && current.isPlaying();
	}

	/** Releases the transport and stops listening; idempotent. */
	public void close() {
		closed = true;
		waveforms.setOnPeaksReady(null);
		MeetingPlayback current = playback;
		playback = null;
		ready = false;
		if (current != null) {
			current.close();
		}
	}

	// ── media loading ──────────────────────────────────────────────────────

	/** Resolves the sources, probes the duration and opens the transport off the FX thread. */
	private void loadMedia() {
		CompletableFuture.supplyAsync(this::openPlayback, UiThreads.background())
				.thenAcceptAsync(loaded -> {
					// The panel can close while the probe/transport setup is in flight; a late
					// transport must not leak its PulseAudio stream.
					if (closed) {
						if (loaded != null) {
							loaded.close();
						}
						return;
					}
					if (loaded == null) {
						showUnavailable();
						return;
					}
					playback = loaded;
					ready = true;
					durationClock.setText(PlaybackControls.formatClock(durationSeconds));
					seekSlider.range(0, durationSeconds).step(0.1);
					seekSlider.setDisable(false);
					playButton.setDisable(false);
					PlaybackStatus status = loaded.status();
					loaded.onStatus(next -> Platform.runLater(() -> refreshTransport(next)));
					loaded.onTime(time -> Platform.runLater(() ->
							onPlaybackTime(time.timelineSeconds())));
					refreshTransport(status);
					renderWaveform();
				}, Platform::runLater)
				.exceptionally(error -> {
					if (!closed) {
						showUnavailable();
					}
					return null;
				});
	}

	/** Runs on a background thread: probe + transport setup; null when playback cannot start. */
	private @Nullable MeetingPlayback openPlayback() {
		try {
			MeetingAudioInputs inputs = Transcript.resolveMeetingAudioInputs(mediaPath);
			// Both diarized sidecars mix into one stream; otherwise the embedded mix plays.
			List<String> sources = inputs.mic() != null && inputs.system() != null
					? List.of(inputs.mic().toString(), inputs.system().toString())
					: List.of(mediaPath.toString());
			durationSeconds = Math.max(0, MediaProbe.probe(mediaPath).durationSeconds());
			if (durationSeconds <= 0) {
				return null;
			}
			MeetingPlayback loaded = MeetingPlayback.openWithPulse(ffmpegPath);
			loaded.setMedia(new MeetingPlayback.Media(sources, durationSeconds));
			return loaded;
		} catch (RuntimeException error) {
			return null;
		}
	}

	private void showUnavailable() {
		Platform.runLater(() -> {
			controls.setVisible(false);
			controls.setManaged(false);
			unavailable.setVisible(true);
			unavailable.setManaged(true);
		});
	}

	// ── transport ──────────────────────────────────────────────────────────

	private void refreshTransport(PlaybackStatus status) {
		boolean playing = status.state() == PlaybackState.PLAYING;
		playButton.setGraphic(Icon.of(playing ? Icons.Pause : Icons.Play, 16, "fill"));
		playButton.setAccessibleText(playing ? "Pause" : "Play");
		if (status.state() == PlaybackState.ENDED) {
			playheadSeconds = status.timelineSeconds();
			currentClock.setText(PlaybackControls.formatClock(status.timelineSeconds()));
			syncSlider(status.timelineSeconds());
			renderWaveform();
		}
	}

	private void onPlaybackTime(double seconds) {
		playheadSeconds = seconds;
		currentClock.setText(PlaybackControls.formatClock(seconds));
		syncSlider(seconds);
		renderWaveform();
		timeSink.accept(seconds);
	}

	private void syncSlider(double seconds) {
		// A drag owns the thumb for a moment; the playhead must not fight it.
		if (System.currentTimeMillis() - lastSliderTouchAt < SLIDER_DRAG_GRACE_MS) {
			return;
		}
		sliderSyncing = true;
		try {
			seekSlider.setValue(seconds);
		} finally {
			sliderSyncing = false;
		}
	}

	private void onWaveformClicked(MouseEvent event) {
		if (!ready || durationSeconds <= 0 || waveform.getWidth() <= 1) {
			return;
		}
		double fraction = event.getX() / waveform.getWidth();
		seek(Math.max(0, Math.min(1, fraction)) * durationSeconds);
	}

	// ── waveform strip ─────────────────────────────────────────────────────

	/** One bar per ~3px, drawn like {@code TimelineView.drawWaveform}; the played part is accented. */
	private void renderWaveform() {
		double width = waveform.getWidth();
		double height = waveform.getHeight();
		if (width < 2 || height < 2) {
			return;
		}
		GraphicsContext graphics = waveform.getGraphicsContext2D();
		graphics.clearRect(0, 0, width, height);
		if (durationSeconds <= 0) {
			return;
		}
		int bars = Math.max(1, (int) Math.ceil(width / WAVEFORM_BAR_PITCH_PX));
		float @Nullable [] peaks = waveforms.peaks(mediaPath.toString(), 0,
				durationSeconds * 1000, bars);
		if (peaks == null || peaks.length == 0) {
			return;
		}
		double playedFraction = Math.max(0, Math.min(1, playheadSeconds / durationSeconds));
		double centerY = height / 2;
		double maxAmplitude = height * 0.35;
		double barWidth = Math.max(1, width / bars - 1);
		Color played = Theme.ACCENT;
		Color unplayed = Color.web("#8fa3b0", 0.55);
		for (int bar = 0; bar < bars && bar < peaks.length; bar++) {
			double amplitude = Math.max(0, Math.min(1, peaks[bar])) * maxAmplitude;
			if (amplitude <= 0) {
				continue;
			}
			double barX = width * bar / bars;
			double barCenter = (barX + barWidth / 2) / width;
			graphics.setFill(barCenter <= playedFraction ? played : unplayed);
			graphics.fillRect(barX, centerY - amplitude, barWidth, amplitude * 2);
		}
		graphics.setFill(Theme.ACCENT);
		graphics.fillRect(playedFraction * width, 2, 1.5, height - 4);
	}

	// ── speed chips ────────────────────────────────────────────────────────

	/** The speed popover: the editor's preset grid with the active rate highlighted. */
	private VBox rateContent() {
		Label title = new Label("Playback speed");
		title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: "
				+ css(Theme.FG) + ";");
		javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
		grid.setHgap(6);
		grid.setVgap(6);
		for (int index = 0; index < EditorPlaybackPort.RATE_PRESETS.size(); index++) {
			double preset = EditorPlaybackPort.RATE_PRESETS.get(index);
			Button chip = new Button(EditorPlaybackPort.formatRate(preset),
					Button.Variant.GHOST, Button.Size.SM);
			chip.getStyleClass().add("meeting-player__rate-chip");
			chip.setOnAction(event -> {
				MeetingPlayback current = playback;
				if (current != null) {
					current.setRate(preset);
				}
				rateButton.setText(EditorPlaybackPort.formatRate(preset));
				Kit.setClass(rateButton, "meeting-player__rate--accented",
						Math.abs(preset - 1) > 1e-9);
				if (ratePopover != null) {
					ratePopover.hide();
				}
				refreshRateChips();
			});
			rateChips.add(chip);
			grid.add(chip, index % EditorPlaybackPort.RATE_PRESET_COLUMNS,
					index / EditorPlaybackPort.RATE_PRESET_COLUMNS);
		}
		VBox content = new VBox(8, title, grid);
		content.setPadding(new Insets(10));
		refreshRateChips();
		return content;
	}

	private void refreshRateChips() {
		double rate = playback == null ? 1 : playback.rate();
		for (int index = 0; index < rateChips.size(); index++) {
			double preset = EditorPlaybackPort.RATE_PRESETS.get(index);
			Kit.setClass(rateChips.get(index), "meeting-player__rate-chip--active",
					Math.abs(rate - preset) < 1e-9);
		}
	}

	private static String css(Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
