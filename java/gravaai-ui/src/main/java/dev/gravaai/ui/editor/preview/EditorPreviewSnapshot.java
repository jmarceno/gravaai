package dev.gravaai.ui.editor.preview;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.CursorSample;
import java.nio.ByteBuffer;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.playback.DecoderFrameSourceProvider;
import dev.gravaai.media.playback.FrameSource;
import dev.gravaai.media.playback.FrameSourceProvider;
import dev.gravaai.media.playback.PlaybackEngine;
import dev.gravaai.media.playback.PlaybackMedia;
import dev.gravaai.media.playback.PlaybackMediaLoader;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.media.playback.WebcamSync;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.RecordingPaths;
import dev.gravaai.ui.AppFonts;
import dev.gravaai.ui.editor.EditorPreviewPanel;
import dev.gravaai.ui.editor.EditorShellView;
import dev.gravaai.ui.editor.EditorViewActions;
import dev.gravaai.ui.kit.KitRoot;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Development harness for the P2.5 preview (plan §6.3): loads a real parity project fixture, builds
 * the playback media through {@link PlaybackMediaLoader}, runs the real {@link PlaybackEngine} with
 * the FFmpeg decoder, renders the editor shell with the compositor preview and writes
 * {@code parity/ui-java/editor-preview.png}.
 *
 * <p>Usage (from {@code java/}):
 * {@code DISPLAY=:0 java -cp "<classes>:<deps>" dev.gravaai.ui.editor.preview.EditorPreviewSnapshot
 * [outDir] [fixtureName]}
 *
 * <p>The fixture's {@code videoPath} is absolute (parity fixtures point into
 * {@code java/parity/fixtures/media}); the recording's cursor sidecar is loaded through
 * {@link RecordingPaths} and fed to the compositor as a {@link PreviewFrameMapper.CursorTrack}.
 */
public final class EditorPreviewSnapshot {

	private static final String DEFAULT_FIXTURE = "03-zoom-motion-blur.gravaai";

	private EditorPreviewSnapshot() {
	}

	public static void main(String[] args) throws Exception {
		AppFonts.load();
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Path output = Path.of(args.length > 0 ? args[0] : "parity/ui-java");
		String fixtureName = args.length > 1 ? args[1] : DEFAULT_FIXTURE;
		Path fixture = fixture(fixtureName);
		System.out.println("fixture=" + fixture.toAbsolutePath());
		Platform.startup(() -> {
			try {
				render(fixture, output, done);
			} catch (Throwable error) {
				failure.set(error);
				done.countDown();
			}
		});
		if (!done.await(90, TimeUnit.SECONDS)) {
			throw new IllegalStateException("editor preview snapshot timed out");
		}
		Platform.exit();
		if (failure.get() != null) {
			throw new IllegalStateException("editor preview snapshot failed", failure.get());
		}
	}

	private static void render(Path fixture, Path output, CountDownLatch done) throws IOException {
		ProjectDocument document = ProjectDocument.load(fixture);
		ProjectEditorState state = document.editor();
		EditorStore store = new EditorStore(state);
		PlaybackMedia media = mediaFor(document);
		boolean fakeFrames = Boolean.getBoolean("gravaai.preview.fakeFrames");
		FrameSourceProvider frameSources = fakeFrames
				? new FakeFrameSources(media.sourceDurationMs() / 1000.0)
				: DecoderFrameSourceProvider.ffmpegBacked();
		System.out.println("frameSource=" + (fakeFrames ? "synthetic" : "ffmpeg-decoder")
				+ " (set -Dgravaai.preview.fakeFrames=true to bypass the decoder)");
		PlaybackEngine engine = PlaybackEngine.createDefault(frameSources);
		PlaybackControllerPort port = new PlaybackControllerPort(engine);
		EditorShellView view = new EditorShellView(store, new EditorViewActions() { }, port);
		view.header().setProjectName(fixture.getFileName().toString()
				.replace(".gravaai", ""));
		view.setPreviewPlayback(engine);
		view.preview().setCursorTrack(cursorTrackFor(document));

		Scene scene = new Scene(new KitRoot(view), 1440, 1000);
		EditorShellView.installStyles(scene);
		Stage stage = new Stage(StageStyle.UNDECORATED);
		stage.setScene(scene);
		stage.show();
		view.requestFocus();

		// Media load and transport: setMedia synchronously delivers the first frame (recording's
		// handshake), then seek into the fixture's zoom region and play so the spring/camera settle.
		PauseTransition load = new PauseTransition(Duration.millis(700));
		load.setOnFinished(event -> {
			try {
				engine.setMedia(media);
				engine.seek(0.7);
				engine.play();
			} catch (RuntimeException error) {
				error.printStackTrace();
			}
		});
		load.play();

		// Play into the fixture's zoom, then pause exactly at 1.15 s (inside zoom region 2) and grab
		// the frame the seek delivers so the snapshot shows the zoom transform and the cursor overlay.
		new javafx.animation.AnimationTimer() {
			private long startedAt = -1;
			private int phase;
			private long framesAtPause;

			@Override
			public void handle(long now) {
				if (startedAt < 0) {
					startedAt = now;
				}
				EditorPreviewView surface = view.preview().previewView();
				PlaybackStatus status = engine.status();
				boolean timedOut = now - startedAt > 8_000_000_000L;
				if (phase == 0) {
					if (status.state() == dev.gravaai.media.playback.PlaybackState.PLAYING
							&& status.timelineSeconds() >= 0.85) {
						engine.pause();
						engine.seek(1.15);
						framesAtPause = surface.framesReceived();
						phase = 1;
					}
					if (!timedOut) {
						return;
					}
				}
				if (phase == 1 && !timedOut && surface.framesReceived() <= framesAtPause) {
					return;
				}
				stop();
				// Let the queued PixelBuffer presents and control refreshes run before capturing.
				Platform.runLater(() -> {
					try {
						view.playbackControls().refresh();
						System.out.println("status=" + engine.status() + " surface="
								+ surface.getWidth() + "x" + surface.getHeight() + " renderer="
								+ surface.rendererWidth() + "x" + surface.rendererHeight()
								+ " framesSubmitted=" + surface.framesSubmitted() + " framesDrawn="
								+ surface.framesDrawn() + " framesReceived="
								+ surface.framesReceived() + " framesDropped="
								+ surface.framesDropped() + " ready=" + view.preview().isPreviewReady()
								+ " failure=" + surface.rendererFailure());
						write(scene, output.resolve("editor-preview.png"));
						System.out.println(
								"wrote " + output.toAbsolutePath().resolve("editor-preview.png"));
					} catch (IOException error) {
						error.printStackTrace();
					} finally {
						engine.close();
						view.dispose();
						done.countDown();
					}
				});
			}
		}.start();
	}

	/** Builds the playback media from the project (clips, webcam paths/ranges, undilated duration). */
	static PlaybackMedia mediaFor(ProjectDocument document) {
		ProjectEditorState state = document.editor();
		var webcam = state.webcam();
		Path webcamPath = webcam != null && webcam.sourcePath() != null
				? Path.of(webcam.sourcePath())
				: null;
		List<WebcamSync.WebcamRange> ranges = new ArrayList<>();
		if (webcam != null && webcam.visibleRanges() != null) {
			for (var range : webcam.visibleRanges()) {
				ranges.add(new WebcamSync.WebcamRange(range.startMs(), range.endMs()));
			}
		}
		return PlaybackMediaLoader.load(Path.of(document.videoPath()), webcamPath,
				webcam == null ? 0 : webcam.timeOffsetMs(), state.clipRegions(),
				ranges.isEmpty() ? null : ranges);
	}

	/** Loads the cursor sidecar next to the source video (if any) into compositor samples. */
	static PreviewFrameMapper.CursorTrack cursorTrackFor(ProjectDocument document) {
		Path telemetry = Path.of(RecordingPaths.getTelemetryPathForVideo(document.videoPath()));
		if (!Files.isRegularFile(telemetry)) {
			return PreviewFrameMapper.CursorTrack.NONE;
		}
		try {
			var parsed = Json.parseJsonWithByteOrderMark(
					Files.readString(telemetry, StandardCharsets.UTF_8));
			var samplesNode = parsed.isArray() ? parsed : parsed.get("samples");
			List<CursorTelemetryPoint> points = CursorTelemetry
					.normalizeCursorTelemetrySamples(samplesNode);
			List<CursorSample> samples = new ArrayList<>(points.size());
			for (CursorTelemetryPoint point : points) {
				samples.add(new CursorSample(point.timeMs() / 1000.0, point.cx(), point.cy()));
			}
			return new PreviewFrameMapper.CursorTrack(samples, document.editor().showCursor());
		} catch (IOException | RuntimeException error) {
			System.err.println("cursor telemetry ignored: " + error);
			return PreviewFrameMapper.CursorTrack.NONE;
		}
	}

	/** Snapshots the scene into a PNG. */
	static void write(Scene scene, Path target) throws IOException {
		WritableImage image = scene.snapshot(null);
		BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < buffered.getHeight(); y++) {
			for (int x = 0; x < buffered.getWidth(); x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		Files.createDirectories(target.toAbsolutePath().getParent());
		ImageIO.write(buffered, "png", target.toFile());
	}

	/**
	 * Dev fallback used while the decoder's thread confinement bug is open (media-native/recording):
	 * the real engine + real project run, but frames are generated instead of decoded.
	 */
	static final class FakeFrameSources implements FrameSourceProvider {
		private final double durationSeconds;

		FakeFrameSources(double durationSeconds) {
			this.durationSeconds = durationSeconds;
		}

		@Override
		public FrameSource open(String mediaPath) {
			return new FakeFrameSource(durationSeconds);
		}
	}

	static final class FakeFrameSource implements FrameSource {
		private final int width = 640;
		private final int height = 360;
		private final double durationSeconds;
		private final ByteBuffer pixels = ByteBuffer.allocateDirect(640 * 360 * 4);
		private final RgbaFrame frame = new RgbaFrame(width, height, pixels);

		FakeFrameSource(double durationSeconds) {
			this.durationSeconds = durationSeconds;
		}

		@Override
		public RgbaFrame frameAt(double sourceSeconds) {
			pixels.clear();
			double phase = sourceSeconds * 0.9;
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int band = (x * 8) / width;
					int r = (int) (128 + 127 * Math.sin(phase + band * 0.8));
					int g = (int) (128 + 127 * Math.sin(phase + band * 1.7 + 2));
					int b = (int) (128 + 127 * Math.sin(phase + band * 2.6 + 4));
					int shade = 90 + (y * 110) / height;
					pixels.put((byte) Math.min(255, r * shade / 180));
					pixels.put((byte) Math.min(255, g * shade / 180));
					pixels.put((byte) Math.min(255, b * shade / 180));
					pixels.put((byte) 255);
				}
			}
			pixels.flip();
			return frame;
		}

		@Override
		public double durationSeconds() {
			return durationSeconds;
		}

		@Override
		public void close() {
			// nothing to release
		}
	}

	/** Locates a parity fixture from the working directory or any parent. */
	static Path fixture(String name) {
		Path start = Path.of(System.getProperty("user.dir"));
		for (Path path = start; path != null; path = path.getParent()) {
			Path candidate = path.resolve("parity/fixtures/projects").resolve(name);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("parity fixture not found: " + name + " from " + start);
	}
}