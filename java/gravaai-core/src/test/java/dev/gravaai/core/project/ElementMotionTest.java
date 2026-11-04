package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.node.ObjectNode;

class ElementMotionTest {

	@Test
	void everyPresetExpandsToKeyframes() {
		for (String preset : ElementMotion.PRESETS) {
			List<ElementMotion.Track> tracks = ElementMotion.preset(preset, true, true, 400);
			assertTrue(!tracks.isEmpty(), preset);
			for (ElementMotion.Track track : tracks) {
				assertTrue(track.keyframes().size() >= 2, preset + " " + track.property());
			}
		}
		assertThrows(IllegalArgumentException.class, () -> ElementMotion.preset("wobble", true, true, 400));
	}

	@Test
	void exitKeysFollowTheElementEnd() {
		ElementMotion.Keyframe exit = new ElementMotion.Keyframe(400, true, 0, "linear");
		assertEquals(2600, exit.resolve(3000));
		assertEquals(9600, exit.resolve(10000));
	}

	@Test
	void combineMergesInAndOutOfOneProperty() {
		List<ElementMotion.Track> combined = ElementMotion.combine(List.of(
				ElementMotion.preset("fade", true, false, 300).getFirst(),
				ElementMotion.preset("fade", false, true, 300).getFirst()));
		assertEquals(1, combined.size());
		assertEquals(4, combined.getFirst().keyframes().size());
	}

	@Test
	void elementsAndAssetsRoundTripThroughTheProjectJson() {
		SceneElement element = new SceneElement("element-1", "gravaai/title-card", 500, 3500,
				new SceneElement.Box(10, 20, 30, 40), SceneElement.noProps(), 2,
				SceneElement.Space.SCENE, ElementMotion.preset("rise", true, true, 400),
				new ElementMotion.Blur(5, 1.5));
		ElementAsset asset = ElementAsset.of("logo", ElementAsset.Kind.IMAGE, new byte[] {1, 2, 3, 4});
		ProjectEditorState state = ProjectPersistence.normalizeProjectEditor(Json.nodeFactory().objectNode())
				.toBuilder().elements(List.of(element)).elementAssets(List.of(asset)).build();
		ObjectNode json = ProjectDocument.writeEditor(state, Json.nodeFactory().objectNode());
		ProjectEditorState read = ProjectPersistence.normalizeProjectEditor(json);
		assertEquals(List.of(element), read.elements());
		assertEquals(List.of(asset), read.elementAssets());
		assertEquals(4, read.elementAssets().getFirst().bytes().length);
	}
}
