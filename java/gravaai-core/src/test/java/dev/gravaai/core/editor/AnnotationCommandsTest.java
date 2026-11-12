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
import dev.gravaai.core.project.SpotlightData;

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
	@DisplayName("seeds the spotlight payload when an annotation becomes a spotlight")
	void seedsSpotlightPayloadOnChangeType() {
		EditorStore store = storeWithAnnotation();
		String id = store.selectedAnnotationId();

		store.apply(new AnnotationCommands.ChangeType(id, AnnotationType.SPOTLIGHT));
		AnnotationRegion region = store.state().annotationRegions().get(0);
		assertEquals(AnnotationType.SPOTLIGHT, region.type());
		assertEquals("", region.content());
		assertEquals(SpotlightData.DEFAULT, region.spotlightData());
	}

	@Test
	@DisplayName("updates the spotlight dim and feather independently")
	void updatesSpotlightDimAndFeather() {
		EditorStore store = storeWithAnnotation();
		String id = store.selectedAnnotationId();
		store.apply(new AnnotationCommands.ChangeType(id, AnnotationType.SPOTLIGHT));

		store.apply(new AnnotationCommands.SetSpotlightDim(id, 35));
		assertEquals(new SpotlightData(35, SpotlightData.DEFAULT.featherPercent()),
				store.state().annotationRegions().get(0).spotlightData());

		store.apply(new AnnotationCommands.SetSpotlightFeather(id, 60));
		assertEquals(new SpotlightData(35, 60),
				store.state().annotationRegions().get(0).spotlightData());

		// The payload clamps to its 0–100 range instead of producing an invisible/over-bright dim.
		store.apply(new AnnotationCommands.SetSpotlightDim(id, 150));
		store.apply(new AnnotationCommands.SetSpotlightFeather(id, -5));
		assertEquals(new SpotlightData(100, 0),
				store.state().annotationRegions().get(0).spotlightData());
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

	// ---- loupe (the "Magnifying Glass" card) ---------------------------------------

	@Test
	@DisplayName("adds a loupe with the lens defaults on the first free lane and selects it")
	void addsLoupeWithLensDefaults() {
		EditorStore store = storeWithAnnotation();

		store.apply(new AnnotationCommands.AddLoupe(2_000, 2_600));

		assertEquals(2, store.state().annotationRegions().size());
		AnnotationRegion loupe = store.state().annotationRegions().get(1);
		assertEquals(AnnotationType.LOUPE, loupe.type());
		assertEquals("", loupe.content());
		assertEquals(new AnnotationSize(AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT,
				AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT), loupe.size());
		assertEquals(AnnotationRegion.LOUPE_DEFAULT_MAGNIFICATION,
				loupe.loupeMagnificationOrDefault());
		assertEquals(0, loupe.trackIndex(), "the text annotation ends before the loupe starts");
		assertEquals(loupe.id(), store.selectedAnnotationId());
	}

	@Test
	@DisplayName("loupes that do not overlap share one lane instead of opening a lane each")
	void loupesShareAFreeLane() {
		EditorStore store = new EditorStore(state("{}"));

		store.apply(new AnnotationCommands.AddLoupe(1_000, 2_000, true));
		store.apply(new AnnotationCommands.AddLoupe(5_000, 6_000, true));
		store.apply(new AnnotationCommands.AddLoupe(5_500, 7_000));

		java.util.List<AnnotationRegion> regions = store.state().annotationRegions();
		assertEquals(3, regions.size());
		assertEquals(0, regions.get(0).trackIndex());
		assertEquals(0, regions.get(1).trackIndex());
		assertEquals(1, regions.get(2).trackIndex(), "an overlapping loupe takes the next lane");
	}

	@Test
	@DisplayName("a suggested loupe over an existing loupe is skipped (re-running suggestions)")
	void suggestedLoupeSkipsExistingLoupe() {
		EditorStore store = new EditorStore(state("{}"));
		store.apply(new AnnotationCommands.AddLoupe(1_000, 2_000, true));

		store.apply(new AnnotationCommands.AddLoupe(1_000, 2_000, true));
		store.apply(new AnnotationCommands.AddLoupe(1_500, 2_500, true));

		assertEquals(1, store.state().annotationRegions().size());
	}

	@Test
	@DisplayName("switching an annotation to a loupe applies the lens defaults")
	void switchesToLoupeAppliesLensDefaults() {
		EditorStore store = storeWithAnnotation();
		String id = store.selectedAnnotationId();
		store.apply(new AnnotationCommands.ChangeSize(id, new AnnotationSize(40, 30)));

		store.apply(new AnnotationCommands.ChangeType(id, AnnotationType.LOUPE));

		AnnotationRegion loupe = store.state().annotationRegions().get(0);
		assertEquals(AnnotationType.LOUPE, loupe.type());
		assertEquals("", loupe.content());
		assertEquals(AnnotationCommands.loupeSize(AnnotationRegion.LOUPE_DEFAULT_DIAMETER_PERCENT),
				loupe.size());
		assertEquals(AnnotationRegion.LOUPE_DEFAULT_MAGNIFICATION,
				loupe.loupeMagnificationOrDefault());
	}

	@Test
	@DisplayName("clamps the loupe magnification into the 1.2–4 renderer range")
	void clampsLoupeMagnification() {
		EditorStore store = storeWithAnnotation();
		String id = store.selectedAnnotationId();
		store.apply(new AnnotationCommands.ChangeType(id, AnnotationType.LOUPE));

		store.apply(new AnnotationCommands.SetLoupeMagnification(id, 9));
		assertEquals(4, store.state().annotationRegions().get(0).loupeMagnificationOrDefault());

		store.apply(new AnnotationCommands.SetLoupeMagnification(id, 0.5));
		assertEquals(1.2, store.state().annotationRegions().get(0).loupeMagnificationOrDefault());
	}
}
