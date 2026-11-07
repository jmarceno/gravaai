package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The timeline strip is 23% of the editor until the user drags its top-edge handle; the dragged
 * height then wins, but never below {@link EditorTimelinePanel#MIN_HEIGHT}, so the toolbar, the
 * ruler and the first lane stay reachable (Instrument mock).
 */
class EditorTimelinePanelResizeTest {

	@DisplayName("the default height follows the editor and stops at the raised 400 px cap")
	@Test
	void defaultHeightFollowsTheEditor() {
		EditorTimelinePanel panel = new EditorTimelinePanel();
		DoubleProperty editorHeight = new SimpleDoubleProperty(1000);
		panel.bindDefaultHeight(editorHeight);

		assertEquals(230, panel.prefHeight(-1), 0.5);
		editorHeight.set(2000);
		assertEquals(400, panel.getMaxHeight(), 0.5);
		assertEquals(400, Math.min(panel.prefHeight(-1), panel.getMaxHeight()), 0.5);
	}

	@DisplayName("a dragged height is kept and never dips below the minimum")
	@Test
	void draggedHeightIsClamped() {
		EditorTimelinePanel panel = new EditorTimelinePanel();
		panel.bindDefaultHeight(new SimpleDoubleProperty(1000));

		panel.setTimelineHeight(420);
		assertEquals(420, panel.prefHeight(-1), 0.5);
		assertTrue(panel.getMaxHeight() > 420, "a dragged height lifts the 400 px default cap");

		panel.setTimelineHeight(10);
		assertEquals(EditorTimelinePanel.MIN_HEIGHT, panel.prefHeight(-1), 0.5);
	}

	@DisplayName("resetting returns the strip to the editor-relative default")
	@Test
	void resetReturnsToDefault() {
		EditorTimelinePanel panel = new EditorTimelinePanel();
		DoubleProperty editorHeight = new SimpleDoubleProperty(1000);
		panel.bindDefaultHeight(editorHeight);
		panel.setTimelineHeight(420);

		panel.resetTimelineHeight();
		assertEquals(230, panel.prefHeight(-1), 0.5);
		editorHeight.set(1100);
		assertEquals(253, panel.prefHeight(-1), 0.5);
	}

	@DisplayName("the resize handle spans the top edge of the strip")
	@Test
	void handleIsPartOfThePanel() {
		EditorTimelinePanel panel = new EditorTimelinePanel();
		Region handle = panel.resizeHandle();
		assertTrue(panel.getChildren().contains(handle));
		assertEquals("Resize the timeline", handle.getAccessibleText());
	}
}
