package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Render target sizing for preview vs export (plan §5.8, decision rule R2). */
class RenderTargetSizeTest {

	@Test
	@DisplayName("export uses the configured output size")
	void exportUsesOutputSize() {
		RenderTargetSize size = RenderTargetSize.export(1920, 1080);
		assertEquals(1920, size.width());
		assertEquals(1080, size.height());
		assertEquals(16.0 / 9.0, size.aspectRatio(), 1e-9);
	}

	@Test
	@DisplayName("preview renders at the panel size")
	void previewUsesPanelSize() {
		RenderTargetSize size = RenderTargetSize.preview(800, 600);
		assertEquals(800, size.width());
		assertEquals(600, size.height());
	}

	@Test
	@DisplayName("the R2 render scale rounds to whole pixels and never drops below 1x1")
	void renderScaleRounds() {
		RenderTargetSize size = RenderTargetSize.preview(800, 600).scaled(0.5);
		assertEquals(400, size.width());
		assertEquals(300, size.height());
		RenderTargetSize rounded = RenderTargetSize.preview(800, 600).scaled(0.333);
		assertEquals(266, rounded.width());
		assertEquals(200, rounded.height());
		assertEquals(1, RenderTargetSize.export(10, 10).scaled(0.01).width());
	}

	@Test
	@DisplayName("invalid sizes and scale factors are rejected")
	void invalidInputsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new RenderTargetSize(0, 10));
		assertThrows(IllegalArgumentException.class, () -> new RenderTargetSize(10, -1));
		assertThrows(IllegalArgumentException.class,
				() -> RenderTargetSize.preview(10, 10).scaled(0));
		assertThrows(IllegalArgumentException.class,
				() -> RenderTargetSize.preview(10, 10).scaled(Double.NaN));
	}
}
