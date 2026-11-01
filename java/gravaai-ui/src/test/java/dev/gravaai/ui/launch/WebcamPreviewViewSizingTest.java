package dev.gravaai.ui.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.geometry.Pos;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The preview must never demand more space than its container gives it. An {@link ImageView} is
 * not resizable and reports the capture's natural size, so an unclamped preview forced the webcam
 * menu frame past its own max size and painted over the rows beneath it — "Turn Off Webcam" ended
 * up unreachable.
 */
class WebcamPreviewViewSizingTest {

	@DisplayName("the pane has no intrinsic size, so a 420x315 menu frame is never inflated")
	@Test
	void hasNoIntrinsicSize() {
		WebcamPreviewView preview = new WebcamPreviewView();
		assertEquals(0, preview.minWidth(-1));
		assertEquals(0, preview.minHeight(-1));
		assertEquals(0, preview.prefWidth(-1));
		assertEquals(0, preview.prefHeight(-1));
	}

	@DisplayName("the frame is fitted to the pane and clipped, instead of overflowing it")
	@Test
	void imageFitsAndClipsToPaint() {
		WebcamPreviewView preview = new WebcamPreviewView();
		ImageView view = preview.imageView();
		assertNotNull(view);
		assertTrue(view.fitWidthProperty().isBound(), "fit width must follow the pane width");
		assertTrue(view.fitHeightProperty().isBound(), "fit height must follow the pane height");
		assertNotNull(preview.getClip(), "the pane must clip the frame it paints");
	}

	@DisplayName("the frame keeps its aspect ratio and is centred in the pane")
	@Test
	void imageIsCentredWithoutStretching() {
		WebcamPreviewView preview = new WebcamPreviewView();
		assertTrue(preview.imageView().isPreserveRatio(),
				"a frame whose aspect differs from the box must letterbox, not stretch");
		assertEquals(Pos.CENTER, preview.getAlignment(),
				"the letterbox must be even on both sides");
	}

	@DisplayName("a stacked image keeps the menu item above and below it clickable")
	@Test
	void imageContributesNoLayoutBounds() {
		WebcamPreviewView preview = new WebcamPreviewView();
		preview.imageView().setImage(new javafx.scene.image.WritableImage(640, 480));
		// With the image attached the pane still reports no preferred size of its own; the layout
		// bounds stay empty, so no sibling can be pushed or covered.
		assertEquals(0, preview.prefWidth(-1));
		assertEquals(0, preview.prefHeight(-1));
		assertEquals(0, preview.getLayoutBounds().getWidth());
		assertEquals(0, preview.getLayoutBounds().getHeight());
	}
}