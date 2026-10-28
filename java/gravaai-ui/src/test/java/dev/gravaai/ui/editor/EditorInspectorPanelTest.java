package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Inspector header toggle visibility follows the sections with advanced-only controls. */
class EditorInspectorPanelTest {

@DisplayName("only Scene, Webcam and Captions expose an Advanced toggle")
@Test
void hasAdvancedMatchesSectionsWithAdvancedRows() {
assertTrue(EditorInspectorPanel.hasAdvanced(EditorSidebarRail.Section.SCENE));
assertTrue(EditorInspectorPanel.hasAdvanced(EditorSidebarRail.Section.WEBCAM));
assertTrue(EditorInspectorPanel.hasAdvanced(EditorSidebarRail.Section.CAPTIONS));

assertFalse(EditorInspectorPanel.hasAdvanced(EditorSidebarRail.Section.CURSOR));
assertFalse(EditorInspectorPanel.hasAdvanced(EditorSidebarRail.Section.SETTINGS));
assertFalse(EditorInspectorPanel.hasAdvanced(EditorSidebarRail.Section.TRANSCRIPT));
assertFalse(EditorInspectorPanel.hasAdvanced(EditorSidebarRail.Section.MEETING));
}
}
