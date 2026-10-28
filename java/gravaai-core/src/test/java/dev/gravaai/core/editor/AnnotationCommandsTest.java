package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.core.project.AnnotationTextStyle;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.core.project.FigureData;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/** Behaviour tests for {@link AnnotationCommands} derived from {@code useAnnotationRegionCommands.ts}. */
class AnnotationCommandsTest {

	private static ProjectEditorState state(String editorJson) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(editorJson));
	}

	private static EditorStore storeWithAnnotation() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new AnnotationCommands.Add(0, 1_000, null));
		return store;
	}

	@Test
	@DisplayName("adds a text annotation with defaults, z-index and selection")
	void addsAnnotation() {
		EditorStore store = storeWithAnnotation();

		assertEquals(1, store.state().annotationRegions().size());
		AnnotationRegion region = store.state().annotationRegions().get(0);
		assertEquals("annotation-1", region.id());
		assertEquals(AnnotationType.TEXT, region.type());
		assertEquals("Enter text...", region.content());
		assertEquals(AnnotationPosition.DEFAULT, region.position());
		assertEquals(AnnotationSize.DEFAULT, region.size());
		assertEquals(1, region.zIndex());
		assertEquals(0, region.trackIndex());
		assertEquals(region.id(), store.selectedAnnotationId());
		assertNull(store.selectedZoomId());
	}

	@Test
	@DisplayName("changes span, track index, content, position and size")
	void changesAnnotationFields() {
		EditorStore store = storeWithAnnotation();
		String id = store.selectedAnnotationId();

		store.apply(new AnnotationCommands.ChangeSpan(id, 200, 900, 2));
		store.apply(new AnnotationCommands.ChangeContent(id, "Hello"));
		store.apply(new AnnotationCommands.ChangePosition(id, new AnnotationPosition(10, 20)));
		store.apply(new AnnotationCommands.ChangeSize(id, new AnnotationSize(40, 30)));

		AnnotationRegion region = store.state().annotationRegions().get(0);
		assertEquals(200, region.startMs());
		assertEquals(900, region.endMs());
		assertEquals(2, region.trackIndex());
		assertEquals("Hello", region.content());
		assertEquals("Hello", region.textContent());
		assertEquals(new AnnotationPosition(10, 20), region.position());
		assertEquals(new AnnotationSize(40, 30), region.size());
	}

	@Test
	@DisplayName("switches annotation types, keeping text/image payloads")
	void switchesAnnotationType() {
		EditorStore store = storeWithAnnotation();
		String id = store.selectedAnnotationId();
		store.apply(new AnnotationCommands.ChangeContent(id, "stored text"));

		store.apply(new AnnotationCommands.ChangeType(id, AnnotationType.IMAGE));
		AnnotationRegion image = store.state().annotationRegions().get(0);
		assertEquals(AnnotationType.IMAGE, image.type());
		assertEquals("", image.content());
		// imageContent stays unset until the user picks an image, exactly like the hook.
		assertNull(image.imageContent());

		store.apply(new AnnotationCommands.ChangeType(id, AnnotationType.TEXT));
		assertEquals("stored text", store.state().annotationRegions().get(0).content());

		store.apply(new AnnotationCommands.ChangeType(id, AnnotationType.FIGURE));
		AnnotationRegion figure = store.state().annotationRegions().get(0);
		assertEquals("", figure.content());
		assertEquals(FigureData.DEFAULT, figure.figureData());

		store.apply(new AnnotationCommands.ChangeType(id, AnnotationType.BLUR));
		assertEquals(20, store.state().annotationRegions().get(0).blurIntensity());
	}

	@Test
	@DisplayName("merges partial style updates")
	void mergesStylePatch() {
		EditorStore store = storeWithAnnotation();
		String id = store.selectedAnnotationId();

		store.apply(new AnnotationCommands.ChangeStyle(id, new AnnotationCommands.StylePatch(
				"#ff0000", null, 44.0, null, AnnotationTextStyle.FontWeight.NORMAL,
				null, null, AnnotationTextStyle.TextAlign.LEFT, null)));

		AnnotationTextStyle style = store.state().annotationRegions().get(0).style();
		assertEquals("#ff0000", style.color());
		assertEquals(44, style.fontSize());
		assertEquals(AnnotationTextStyle.FontWeight.NORMAL, style.fontWeight());
		assertEquals(AnnotationTextStyle.TextAlign.LEFT, style.textAlign());
		assertEquals(AnnotationTextStyle.FontStyle.NORMAL, style.fontStyle());
	}

	@Test
	@DisplayName("updates figure data, blur intensity/colour and deletes the region")
	void updatesFigureAndBlurThenDeletes() {
		EditorStore store = storeWithAnnotation();
		String id = store.selectedAnnotationId();

		store.apply(new AnnotationCommands.SetFigureData(id, FigureData.DEFAULT));
		store.apply(new AnnotationCommands.SetBlurIntensity(id, 40));
		store.apply(new AnnotationCommands.SetBlurColor(id, "#112233"));
		store.apply(new AnnotationCommands.Select(id));
		store.apply(new AnnotationCommands.Delete(id));

		assertTrue(store.state().annotationRegions().isEmpty());
		assertNull(store.selectedAnnotationId());
	}
}
