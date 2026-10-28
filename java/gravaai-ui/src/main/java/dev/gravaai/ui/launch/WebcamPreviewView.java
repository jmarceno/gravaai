package dev.gravaai.ui.launch;

import dev.gravaai.media.capture.WebcamCapture;
import dev.gravaai.media.capture.WebcamFormat;
import java.nio.ByteBuffer;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;

/**
 * Live webcam preview (plan §5.6.6 / §7.1 P1.7), ported from the {@code <video>} elements of
 * {@code LaunchWindow.tsx} and the HUD webcam menu.
 *
 * <p>Frames come from {@link WebcamCapture} (libavdevice v4l2) as packed RGBA top row first. The
 * capture is opened AND read on one platform thread (the media layer's single-thread contract:
 * FFM memory is confined to its owning thread); only the {@link WritableImage} upload hops to the
 * FX thread. The image is mirrored horizontally like the React {@code scaleX(-1)}.
 */
public final class WebcamPreviewView extends StackPane {

	private static final System.Logger LOG = System.getLogger("gravaai.hud.webcam");
	/** Requested capture size; readFrame's destination contract uses these target dimensions. */
	private static final int PREVIEW_WIDTH = 640;
	private static final int PREVIEW_HEIGHT = 480;

	private final ImageView view = new ImageView();

	private volatile boolean running;
	private Thread reader;

	public WebcamPreviewView() {
		view.setPreserveRatio(false);
		view.setSmooth(true);
		view.setScaleX(-1);
		getChildren().add(view);
		setMinSize(0, 0);
	}

	/** Starts (or restarts) the preview for {@code devicePath}; no-op for a blank path. */
	public void start(String devicePath) {
		stop();
		if (devicePath == null || devicePath.isBlank()) {
			return;
		}
		running = true;
		reader = Thread.ofPlatform().daemon(true).name("hud-webcam-preview").unstarted(
				() -> readFrames(devicePath));
		reader.start();
	}

	private void readFrames(String devicePath) {
		try (WebcamCapture capture = WebcamCapture.open(devicePath, PREVIEW_WIDTH, PREVIEW_HEIGHT, 30)) {
			WebcamFormat format = capture.format();
			LOG.log(System.Logger.Level.DEBUG, "webcam preview format " + format);
			int width = PREVIEW_WIDTH;
			int height = PREVIEW_HEIGHT;
			WritableImage image = new WritableImage(width, height);
			Platform.runLater(() -> view.setImage(image));
			byte[] rgba = new byte[width * height * 4];
			byte[] rgb = new byte[width * height * 3];
			ByteBuffer direct = ByteBuffer.allocateDirect(width * height * 4);
			while (running) {
				direct.clear();
				if (!capture.readFrame(direct)) {
					break;
				}
				// readFrame copies with an absolute put (buffer position stays 0), so reset the
				// view explicitly instead of relying on flip().
				direct.clear();
				direct.limit(width * height * 4);
				direct.get(rgba);
				for (int source = 0, target = 0; source < rgba.length; source += 4, target += 3) {
					rgb[target] = rgba[source];
					rgb[target + 1] = rgba[source + 1];
					rgb[target + 2] = rgba[source + 2];
				}
				byte[] published = rgb.clone();
				Platform.runLater(() -> image.getPixelWriter().setPixels(0, 0, width, height,
						PixelFormat.getByteRgbInstance(), published, 0, width * 3));
			}
		} catch (RuntimeException error) {
			if (running) {
				LOG.log(System.Logger.Level.INFO, "webcam preview stopped: " + error);
			}
		} finally {
			running = false;
		}
	}

	/** Stops the reader; the capture is closed by the reader thread when the loop exits. */
	public void stop() {
		running = false;
		Thread current = reader;
		reader = null;
		if (current != null) {
			current.interrupt();
		}
		view.setImage(null);
	}

	/** True while a camera is being read. */
	public boolean isRunning() {
		return running;
	}
}
