package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationType;
import javafx.application.Platform;

/**
 * Add Layer → Text/Blur/Image end to end (user reports 2026-10-28): every added overlay must show up
 * on the timeline as its own block, only be active inside its time range, and the preview overlay
 * must follow edits made while paused.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EditorAnnotationFlowTest {

	private final AtomicReference<EditorWindow> holder = new AtomicReference<>();

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			Platform.runLater(() -> { });
		}
		dev.gravaai.ui.AppFonts.load();
	}

	@AfterEach
	void tearDown() throws Exception {
		EditorWindow window = holder.get();
		if (window != null) {
			runOnFx(window::dispose);
		}
	}

	@Test
	@DisplayName("added text, blur and image overlays are timeline blocks bound to their time range")
	void addedOverlaysAreTimedTimelineBlocks() throws Exception {
		open();
		await(() -> holder.get().session().playbackPort().state().durationSeconds() > 0.5, 15_000);
		runOnFx(() -> holder.get().session().playbackPort().seek(0.2));
		Thread.sleep(300);

		runOnFx(() -> holder.get().shell().actions().addTextAnnotation());
		runOnFx(() -> holder.get().shell().actions().addBlurAnnotation());
		runOnFx(() -> holder.get().shell().actions().addImageAnnotation());

		List<AnnotationRegion> regions = onFx(
				() -> holder.get().session().store().state().annotationRegions());
		assertEquals(3, regions.size(), "three overlays were added: " + regions);
		assertEquals(List.of(AnnotationType.TEXT, AnnotationType.BLUR, AnnotationType.IMAGE),
				regions.stream().map(AnnotationRegion::type).toList());
		Thread.sleep(300);
		for (AnnotationRegion region : regions) {
			assertTrue(region.endMs() > region.startMs(), "timed: " + region);
			assertTrue(onFx(() -> holder.get().timeline().blockBounds(region.id()).isPresent()),
					region.type() + " overlay has a timeline block");
		}
		long rows = onFx(() -> holder.get().timeline().rowIds().stream()
				.filter(dev.gravaai.ui.editor.timeline.core.TimelineRows::isAnnotationTrackRowId).count());
		assertEquals(3, rows, "each added overlay gets its own lane (React nextTrack)");

		// Inside the range the overlay is active; past its end it is gone.
		await(() -> holder.get().shell().preview().annotationOverlay().visibleAnnotations().size() == 3,
				5_000);
		double after = regions.get(0).endMs() / 1000.0 + 0.1;
		runOnFx(() -> holder.get().session().playbackPort().seek(after));
		await(() -> holder.get().shell().preview().annotationOverlay().visibleAnnotations().isEmpty(),
				5_000);
	}

	@Test
	@DisplayName("editing a paused text overlay updates the preview overlay right away")
	void pausedEditUpdatesOverlay() throws Exception {
		open();
		await(() -> holder.get().session().playbackPort().state().durationSeconds() > 0.5, 15_000);
		runOnFx(() -> holder.get().session().playbackPort().seek(0.2));
		Thread.sleep(300);
		runOnFx(() -> holder.get().shell().actions().addTextAnnotation());
		String id = onFx(() -> holder.get().session().store().selectedAnnotationId());
		assertNotNull(id);
		Thread.sleep(400);
		long before = onFx(this::previewChecksum);
		runOnFx(() -> holder.get().session().store().apply(
				new dev.gravaai.core.editor.AnnotationCommands.ChangeContent(id,
						"Hello overlay, a much longer line of text")));
		await(() -> previewChecksum() != before, 5_000);
	}

	@Test
	@DisplayName("dragging an overlay moves the composited annotation live, before release")
	void dragMovesRenderedAnnotationLive() throws Exception {
		overlayBoxMatchesRenderedAnnotation();
		double[] start = onFx(this::redBounds);
		javafx.scene.layout.Region box = onFx(() -> (javafx.scene.layout.Region) holder.get()
				.shell().preview().annotationOverlay().layer().getChildrenUnmodifiable().get(0));
		javafx.geometry.Point2D centre = onFx(() -> box.localToScene(box.getWidth() / 2,
				box.getHeight() / 2));
		runOnFx(() -> box.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, centre, 0)));
		runOnFx(() -> box.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, centre, -40)));
		runOnFx(() -> box.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, centre, -120)));
		try {
			await(() -> redBounds()[0] < start[0] - 60, 5_000);
		} catch (AssertionError error) {
			throw new AssertionError("start " + java.util.Arrays.toString(start) + " now "
					+ java.util.Arrays.toString(onFx(this::redBounds)) + " box x " + onFx(
							box::getLayoutX) + " centre " + centre, error);
		}
		runOnFx(() -> box.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_RELEASED, centre, -120)));
		Thread.sleep(400);
		assertTrue(onFx(this::redBounds)[0] < start[0] - 60, "the committed position stays");
	}

	private static javafx.scene.input.MouseEvent mouse(
			javafx.event.EventType<javafx.scene.input.MouseEvent> type,
			javafx.geometry.Point2D scene, double dx) {
		return new javafx.scene.input.MouseEvent(type, scene.getX() + dx, scene.getY(),
				scene.getX() + dx, scene.getY(),
				javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false,
				false, false, false, false, null);
	}

	private javafx.scene.image.Image previewImage() {
		for (javafx.scene.Node child : holder.get().shell().preview().previewView()
				.getChildrenUnmodifiable()) {
			if (child instanceof javafx.scene.image.ImageView view) {
				return view.getImage();
			}
		}
		throw new IllegalStateException("no preview image");
	}

	private long previewChecksum() {
		javafx.scene.image.Image image = previewImage();
		var reader = image.getPixelReader();
		long sum = 0;
		for (int y = 0; y < (int) image.getHeight(); y += 2) {
			for (int x = 0; x < (int) image.getWidth(); x += 2) {
				sum = sum * 31 + reader.getArgb(x, y);
			}
		}
		return sum;
	}

	/** minX, minY, maxX, maxY of the red test image in preview pixels. */
	private double[] redBounds() {
		javafx.scene.image.Image image = previewImage();
		var reader = image.getPixelReader();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < (int) image.getHeight(); y++) {
			for (int x = 0; x < (int) image.getWidth(); x++) {
				int argb = reader.getArgb(x, y);
				if ((argb >> 16 & 0xff) > 200 && (argb >> 8 & 0xff) < 70 && (argb & 0xff) < 70) {
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return new double[] {minX, minY, maxX, maxY};
	}

	@Test
	@DisplayName("the overlay box sits exactly where the compositor draws the annotation")
	void overlayBoxMatchesRenderedAnnotation() throws Exception {
		open();
		await(() -> holder.get().shell().preview().isPreviewReady(), 15_000);
		runOnFx(() -> holder.get().session().playbackPort().seek(0.2));
		Thread.sleep(300);
		runOnFx(() -> holder.get().shell().actions().addImageAnnotation());
		String id = onFx(() -> holder.get().session().store().selectedAnnotationId());
		java.awt.image.BufferedImage red = new java.awt.image.BufferedImage(64, 64,
				java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D graphics = red.createGraphics();
		graphics.setColor(java.awt.Color.RED);
		graphics.fillRect(0, 0, 64, 64);
		graphics.dispose();
		java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(red, "png", png);
		String dataUri = "data:image/png;base64,"
				+ java.util.Base64.getEncoder().encodeToString(png.toByteArray());
		runOnFx(() -> {
			var store = holder.get().session().store();
			store.apply(new dev.gravaai.core.editor.AnnotationCommands.ChangeContent(id, dataUri));
			store.apply(new dev.gravaai.core.editor.AnnotationCommands.ChangePosition(id,
					new dev.gravaai.core.project.AnnotationPosition(55, 20)));
			store.apply(new dev.gravaai.core.editor.AnnotationCommands.ChangeSize(id,
					new dev.gravaai.core.project.AnnotationSize(20, 30)));
		});
		Thread.sleep(800);
		double[] measured = onFx(() -> {
			var preview = holder.get().shell().preview();
			javafx.scene.image.ImageView view = null;
			for (javafx.scene.Node child : preview.previewView().getChildrenUnmodifiable()) {
				if (child instanceof javafx.scene.image.ImageView imageView) {
					view = imageView;
				}
			}
			javafx.scene.image.Image image = view.getImage();
			var reader = image.getPixelReader();
			int width = (int) image.getWidth();
			int height = (int) image.getHeight();
			int minX = width;
			int minY = height;
			int maxX = -1;
			int maxY = -1;
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int argb = reader.getArgb(x, y);
					int r = argb >> 16 & 0xff;
					int g = argb >> 8 & 0xff;
					int b = argb & 0xff;
					if (r > 200 && g < 70 && b < 70) {
						minX = Math.min(minX, x);
						maxX = Math.max(maxX, x);
						minY = Math.min(minY, y);
						maxY = Math.max(maxY, y);
					}
				}
			}
			javafx.scene.Node box = preview.annotationOverlay().layer().getChildrenUnmodifiable()
					.get(0);
			double sx = width / preview.previewView().getWidth();
			double sy = height / preview.previewView().getHeight();
			javafx.scene.layout.Region region = (javafx.scene.layout.Region) box;
			double boxX = region.getLayoutX();
			double boxY = region.getLayoutY();
			return new double[] {(minX + maxX) / 2.0, (minY + maxY) / 2.0,
					(boxX + region.getWidth() / 2) * sx, (boxY + region.getHeight() / 2) * sy,
					maxX - minX, maxY - minY, region.getWidth() * sx, region.getHeight() * sy};
		});
		String detail = java.util.Arrays.toString(measured);
		assertTrue(measured[4] > 10, "the red image is rendered: " + detail);
		assertEquals(measured[2], measured[0], 3, "box centre x matches the render: " + detail);
		assertEquals(measured[3], measured[1], 3, "box centre y matches the render: " + detail);
		// The square image is contain-fitted: its side equals the box's shorter side.
		assertEquals(Math.min(measured[6], measured[7]), Math.max(measured[4], measured[5]), 4,
				"box size matches the render: " + detail);
	}

	private void open() throws Exception {
		Path project = Files.createTempDirectory("gravaai-annotations").resolve("Overlays.gravaai");
		Files.writeString(project, """
				{"version":2,"projectId":"11111111-2222-3333-4444-999999999999",
				 "videoPath": "%s","createdAt":1700000000000,"updatedAt":1700000000000,
				 "projectName":"Overlays","editor":{"aspectRatio":"16:9",
				 "clipRegions":[{"id":"clip-1","startMs":0,"endMs":2000,"speed":1}]}}
				""".formatted(fixture()));
		runOnFx(() -> {
			try {
				EditorWindow window = EditorWindow.create(project, EditorWindow.Services.bare());
				holder.set(window);
				window.show();
			} catch (Exception error) {
				throw new IllegalStateException(error);
			}
		});
	}

	private static void runOnFx(Runnable work) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				work.run();
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(30, TimeUnit.SECONDS), "FX work timed out");
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}

	private static <T> T onFx(Supplier<T> work) throws Exception {
		AtomicReference<T> value = new AtomicReference<>();
		runOnFx(() -> value.set(work.get()));
		return value.get();
	}

	private static void await(Supplier<Boolean> condition, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (Boolean.TRUE.equals(onFx(condition))) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("condition not met within " + timeoutMs + " ms");
	}

	private static Path fixture() {
		for (Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath(); start != null;
				start = start.getParent()) {
			for (String relative : List.of("parity/fixtures/media/h264-1080p60.mp4",
					"java/parity/fixtures/media/h264-1080p60.mp4")) {
				Path candidate = start.resolve(relative).normalize();
				if (Files.isRegularFile(candidate)) {
					return candidate;
				}
			}
		}
		throw new IllegalStateException("h264 fixture not found");
	}
}
