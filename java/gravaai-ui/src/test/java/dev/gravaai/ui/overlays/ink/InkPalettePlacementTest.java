package dev.gravaai.ui.overlays.ink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javafx.geometry.Rectangle2D;

import dev.gravaai.ui.overlays.ink.InkPalettePlacement.Position;

/**
 * The palette lives inside what is recorded: top-centre of the area on an area recording, and
 * dragged positions never leave it.
 */
class InkPalettePlacementTest {

	/** A 1920x1080 monitor the window manager left where it was asked. */
	private static final Rectangle2D STAGE = new Rectangle2D(0, 635, 1920, 1080);
	private static final double W = 400;
	private static final double H = 36;

	@Test
	@DisplayName("without an area the palette hangs top-centre of the stage")
	void fullMonitor() {
		assertEquals(new Position((1920 - W) / 2, InkPalettePlacement.TOP_MARGIN),
				InkPalettePlacement.initial(STAGE, Optional.empty(), W, H));
	}

	@Test
	@DisplayName("with an area the palette hangs top-centre of the area, mapped into the stage")
	void insideArea() {
		// The recorded area sits 200 px right and 100 px down on the monitor the stage covers.
		Rectangle2D area = new Rectangle2D(200, 735, 800, 600);
		assertEquals(new Position(200 + (800 - W) / 2, 100 + InkPalettePlacement.TOP_MARGIN),
				InkPalettePlacement.initial(STAGE, Optional.of(area), W, H));
	}

	@Test
	@DisplayName("a drag can move the palette around the area but never out of it")
	void draggedStaysInside() {
		Rectangle2D area = new Rectangle2D(0, 635, 800, 400);
		// Free movement inside.
		assertEquals(new Position(300, 250),
				InkPalettePlacement.dragged(STAGE, Optional.of(area), W, H, 300, 250));
		// Past the right and bottom edges of the area.
		assertEquals(new Position(800 - W, 400 - H),
				InkPalettePlacement.dragged(STAGE, Optional.of(area), W, H, 1900, 1200));
		// Above the top edge.
		assertEquals(new Position(100, 0),
				InkPalettePlacement.dragged(STAGE, Optional.of(area), W, H, 100, -50));
	}

	@Test
	@DisplayName("without an area a drag keeps the palette on the stage")
	void draggedStaysOnStage() {
		assertEquals(new Position(0, 0),
				InkPalettePlacement.dragged(STAGE, Optional.empty(), W, H, -80, -30));
		assertEquals(new Position(1920 - W, 1080 - H),
				InkPalettePlacement.dragged(STAGE, Optional.empty(), W, H, 2500, 1500));
	}

	@Test
	@DisplayName("an area slimmer than the palette gets it centred, not pinned to an edge")
	void paletteWiderThanArea() {
		Rectangle2D area = new Rectangle2D(500, 635, 300, 300);
		Position position = InkPalettePlacement.initial(STAGE, Optional.of(area), W, H);
		assertEquals(500 + (300 - W) / 2, position.x(), 1e-9);
		assertEquals(InkPalettePlacement.TOP_MARGIN, position.y(), 1e-9);
		// The same drag still stays centred instead of being clamped to a nonsense edge.
		Position dragged = InkPalettePlacement.dragged(STAGE, Optional.of(area), W, H, 0, 0);
		assertEquals(500 + (300 - W) / 2, dragged.x(), 1e-9);
		assertEquals(0, dragged.y(), 1e-9);
	}

	@Test
	@DisplayName("a stage the window manager moved keeps the palette over the area")
	void clampedStage() {
		// COSMIC asked for (0,635) 1920x1080 and got (0,667) 1920x1000; the area is fixed on screen.
		Rectangle2D stage = new Rectangle2D(0, 667, 1920, 1000);
		Rectangle2D area = new Rectangle2D(0, 700, 1920, 900);
		assertEquals(new Position((1920 - W) / 2, 33 + InkPalettePlacement.TOP_MARGIN),
				InkPalettePlacement.initial(stage, Optional.of(area), W, H));
	}
}
