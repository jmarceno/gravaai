package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.CropRegion;

class CropEditorDialogTest {

	private static final double EPS = 1e-9;

	@Test
	@DisplayName("edges drag independently and keep 10% of the frame (CropControl)")
	void edgeDragClamps() {
		CropRegion full = CropRegion.DEFAULT;
		CropRegion top = CropEditorDialog.drag(full, "top", 0, 0.25);
		assertEquals(0.25, top.y(), EPS);
		assertEquals(0.75, top.height(), EPS);

		CropRegion pastBottom = CropEditorDialog.drag(full, "top", 0, 2);
		assertEquals(0.9, pastBottom.y(), EPS);
		assertEquals(0.1, pastBottom.height(), EPS);

		CropRegion right = CropEditorDialog.drag(new CropRegion(0.2, 0, 0.5, 1), "right", 0.6, 0);
		assertEquals(0.8, right.width(), EPS);

		CropRegion left = CropEditorDialog.drag(new CropRegion(0.2, 0, 0.5, 1), "left", -0.5, 0);
		assertEquals(0, left.x(), EPS);
		assertEquals(0.7, left.width(), EPS);

		CropRegion bottom = CropEditorDialog.drag(full, "bottom", 0, -0.95);
		assertEquals(0.1, bottom.height(), EPS);
	}
}
