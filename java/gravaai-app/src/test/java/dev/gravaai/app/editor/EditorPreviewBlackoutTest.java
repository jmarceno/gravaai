package dev.gravaai.app.editor;

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

import dev.gravaai.ui.editor.EditorMutations;
import dev.gravaai.ui.editor.EditorSidebarRail;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;

/**
 * Regression for the paused-preview blackouts (user reports 2026-10-28): switching inspector tabs,
 * resizing the window (double-click on the title bar maximises) and changing the aspect ratio all
 * recreate or repaint the compositor surface while paused, and the preview went black until the
 * user pressed play.
 *
 * <p>Runs the real editor window, playback engine and GL renderer against the h264 fixture and
 * reads the pixels the preview {@link ImageView} is showing.
 */
@Timeout(180)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EditorPreviewBlackoutTest {

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
	@DisplayName("the paused preview stays visible across tab switches, resizes and aspect changes")
	void pausedPreviewNeverGoesBlack() throws Exception {
		openFixtureProject();
		await(() -> holder.get().shell().preview().isPreviewReady(), 20_000);
		runOnFx(() -> holder.get().session().playbackPort().seek(1.0));
		awaitVisible("after the first seek");

		for (EditorSidebarRail.Section section : List.of(EditorSidebarRail.Section.CURSOR,
				EditorSidebarRail.Section.WEBCAM, EditorSidebarRail.Section.SETTINGS,
				EditorSidebarRail.Section.SCENE)) {
			runOnFx(() -> holder.get().shell().rail().select(section));
			Thread.sleep(600);
			awaitVisible("after selecting " + section);
		}

		runOnFx(() -> {
			var stage = holder.get().stage();
			stage.setWidth(stage.getWidth() + 260);
			stage.setHeight(stage.getHeight() + 120);
		});
		Thread.sleep(600);
		awaitVisible("after a window resize");

		for (String ratio : List.of("9:16", "1:1", "16:9")) {
			runOnFx(() -> {
				EditorMutations.update(holder.get().shell().store(),
						json -> json.put("aspectRatio", ratio));
				holder.get().shell().preview().refresh();
			});
			Thread.sleep(600);
			awaitVisible("after aspect ratio " + ratio);
		}
	}

	@Test
	@DisplayName("applying generated captions keeps the video in the paused preview")
	void captionsKeepTheVideo() throws Exception {
		openFixtureProject();
		await(() -> holder.get().shell().preview().isPreviewReady(), 20_000);
		runOnFx(() -> holder.get().session().playbackPort().seek(1.0));
		awaitVisible("before captions");
		Thread.sleep(400);
		int[] before = onFx(this::centreSample);
		runOnFx(() -> {
			var cues = List.of(new dev.gravaai.core.project.CaptionCue("cue-1", 0, 4000,
					"Hello captions", List.of()));
			EditorMutations.update(holder.get().shell().store(), json -> {
				json.set("autoCaptions",
						dev.gravaai.core.project.ProjectDocument.writeAutoCaptions(cues));
				((tools.jackson.databind.node.ObjectNode) json.get("autoCaptionSettings"))
						.put("enabled", true);
			});
		});
		Thread.sleep(800);
		int[] after = onFx(this::centreSample);
		String detail = java.util.Arrays.toString(before) + " -> " + java.util.Arrays.toString(after);
		assertTrue(Math.abs(before[0] - after[0]) + Math.abs(before[1] - after[1])
				+ Math.abs(before[2] - after[2]) < 40, "the video centre is unchanged: " + detail);
		assertTrue(onFx(() -> holder.get().shell().preview().previewView().rendererFailure()) == null,
				"the compositor did not fail");
	}

	/** Mean RGB of the central 10% of the preview (inside the video, away from captions). */
	private int[] centreSample() {
		ImageView view = findImageView(holder.get().shell().preview().previewView());
		Image image = view.getImage();
		PixelReader reader = image.getPixelReader();
		int width = (int) image.getWidth();
		int height = (int) image.getHeight();
		long r = 0;
		long g = 0;
		long b = 0;
		int count = 0;
		for (int y = (int) (height * 0.45); y < height * 0.55; y++) {
			for (int x = (int) (width * 0.45); x < width * 0.55; x++) {
				int argb = reader.getArgb(x, y);
				r += argb >> 16 & 0xff;
				g += argb >> 8 & 0xff;
				b += argb & 0xff;
				count++;
			}
		}
		return new int[] {(int) (r / count), (int) (g / count), (int) (b / count)};
	}

	private void openFixtureProject() throws Exception {
		Path project = Files.createTempDirectory("gravaai-blackout")
				.resolve("Blackout project.gravaai");
		Files.writeString(project, """
				{"version":2,"projectId":"11111111-2222-3333-4444-666666666666",
				 "videoPath": "%s","createdAt":1700000000000,"updatedAt":1700000000000,
				 "projectName":"Blackout project",
				 "editor":{"wallpaper":"/wallpapers/tahoe-light.jpg","shadowIntensity":0.67,
				 "backgroundBlur":0,"aspectRatio":"16:9","borderRadius":0,
				 "padding":{"top":20,"bottom":20,"left":20,"right":20,"linked":true},
				 "clipRegions":[{"id":"clip-1","startMs":0,"endMs":4000,"speed":1}]}}
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

	/** Waits until the preview image shows real content (not an empty, all-zero buffer). */
	private void awaitVisible(String when) throws Exception {
		long deadline = System.currentTimeMillis() + 5_000;
		double lit = 0;
		while (System.currentTimeMillis() < deadline) {
			lit = onFx(this::litFraction);
			if (lit > 0.5) {
				return;
			}
			Thread.sleep(50);
		}
		String diagnostics = onFx(() -> {
			var view = holder.get().shell().preview().previewView();
			return "renderer " + view.rendererWidth() + "x" + view.rendererHeight()
					+ " view " + view.getWidth() + "x" + view.getHeight()
					+ " submitted " + view.framesSubmitted() + " drawn " + view.framesDrawn()
					+ " received " + view.framesReceived() + " dropped " + view.framesDropped()
					+ " failure " + view.rendererFailure();
		});
		throw new AssertionError("preview is black " + when + " (lit fraction " + lit + ") "
				+ diagnostics);
	}

	/** Fraction of sampled preview pixels that are opaque and not black. */
	private double litFraction() {
		ImageView view = findImageView(holder.get().shell().preview().previewView());
		Image image = view == null ? null : view.getImage();
		if (image == null || !view.isVisible()) {
			return 0;
		}
		PixelReader reader = image.getPixelReader();
		int width = (int) image.getWidth();
		int height = (int) image.getHeight();
		int lit = 0;
		int total = 0;
		for (int y = height / 8; y < height; y += Math.max(1, height / 8)) {
			for (int x = width / 8; x < width; x += Math.max(1, width / 8)) {
				int argb = reader.getArgb(x, y);
				int alpha = argb >>> 24;
				int rgb = (argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff);
				total++;
				if (alpha > 200 && rgb > 30) {
					lit++;
				}
			}
		}
		return total == 0 ? 0 : lit / (double) total;
	}

	private static ImageView findImageView(javafx.scene.Parent parent) {
		for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
			if (child instanceof ImageView view) {
				return view;
			}
		}
		return null;
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
