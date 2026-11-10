package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.humbleui.types.Rect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/**
 * The smart-framing dodge schedule: the camera never sits over the cursor (or the zoom focus) for
 * longer than the grace period, moves glide, and Camera layout segments stand it down.
 */
class SmartFramingTest {

	private static final int STAGE_WIDTH = 1920;
	private static final int STAGE_HEIGHT = 1080;

	/** A project with the default bottom-right bubble and smart framing on. */
	private static final ProjectEditorState EDITOR = ProjectPersistence.normalizeProjectEditor(
			Json.parseJsonWithByteOrderMark("""
					{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
					 "webcam":{"enabled":true,"positionPreset":"bottom-right","corner":"bottom-right",
					           "smartFraming":true}}
					"""));

	/** One cursor sample at a source position, at source time {@code seconds}. */
	private static CursorSample at(double seconds, double x, double y) {
		return new CursorSample(seconds, x, y);
	}

	private static SmartFraming.Plan plan(ProjectEditorState editor, List<CursorSample> samples) {
		SmartFraming.Plan plan = SmartFraming.plan(editor, samples, 30, 1920, 1080, 0, 0,
				STAGE_WIDTH, STAGE_HEIGHT);
		return plan;
	}

	@Test
	@DisplayName("dodges to the nearest clearing corner after the grace period, gliding there")
	void dodgesAfterGrace() {
		// Parked under the bottom-right camera from the start.
		List<CursorSample> samples = List.of(
				at(0, 0.9, 0.9), at(1, 0.9, 0.9), at(5, 0.9, 0.9), at(10, 0.9, 0.9));
		SmartFraming.Plan plan = plan(EDITOR, samples);
		assertNotNull(plan);
		assertFalse(plan.isEmpty());
		assertEquals(1, plan.moves().size());
		assertFalse(plan.moves().get(0).returning());

		// Nothing before the grace expires; the glide is in full swing shortly after it.
		assertFalse(plan.at(500).active());
		assertTrue(plan.at(800).active());
		assertTrue(plan.at(800).positionY() < 1);
		// Landed at a clearing corner: the top-right, nearest one away from the cursor.
		SmartFraming.Override settled = plan.at(2000);
		assertTrue(settled.active());
		assertEquals(1, settled.positionX(), 1e-6);
		assertEquals(0, settled.positionY(), 1e-6);
		assertEquals(1, settled.sizeFactor(), 1e-6);
	}

	@Test
	@DisplayName("the settled card no longer covers the cursor")
	void settledCardClearsCursor() {
		List<CursorSample> samples = List.of(at(0, 0.9, 0.9), at(10, 0.9, 0.9));
		SmartFraming.Plan plan = plan(EDITOR, samples);
		WebcamSpec spec = SmartFraming.apply(ProjectEditorInputs.webcamSpec(EDITOR),
				plan.at(2000));
		assertEquals("custom", spec.positionPreset());
		// The same box math the compositor draws with, at the settled override.
		Rect box = boxOf(spec, plan.at(2000).sizeFactor(), 1);
		assertFalse(SmartFramingTest.inside(box, 0.9 * STAGE_WIDTH, 0.9 * STAGE_HEIGHT, 16));
	}

	@Test
	@DisplayName("returns to the layout once the way has been clear for the hysteresis window")
	void returnsAfterHysteresis() {
		List<CursorSample> samples = List.of(
				at(0, 0.9, 0.9), at(0.9, 0.9, 0.9),
				at(1.0, 0.05, 0.05), at(5, 0.05, 0.05));
		SmartFraming.Plan plan = plan(EDITOR, samples);
		// Dodged, and still dodged while the cursor wanders top-left.
		assertTrue(plan.at(1500).active());
		// Then the return glide, completing MOVE_MS later.
		SmartFraming.Move last = plan.moves().get(plan.moves().size() - 1);
		assertTrue(last.returning());
		assertTrue(plan.at(last.startMs() + SmartFraming.MOVE_MS / 2).active());
		assertFalse(plan.at(last.startMs() + SmartFraming.MOVE_MS + 1).active());
	}

	@Test
	@DisplayName("stays evaded while the cursor parks where the layout would put the camera")
	void staysEvadedOverBaseSpot() {
		List<CursorSample> samples = List.of(
				at(0, 0.9, 0.9), at(1, 0.9, 0.9), at(10, 0.9, 0.9));
		SmartFraming.Plan plan = plan(EDITOR, samples);
		// The cursor never leaves the base corner: the camera never comes back onto it.
		assertEquals(1, plan.moves().size());
		assertTrue(plan.at(10_000).active());
	}

	@Test
	@DisplayName("dodges again when the cursor follows the camera to its corner")
	void dodgesAgainWhenFollowed() {
		// Under the camera, then under the top-right corner it dodged to.
		List<CursorSample> samples = List.of(
				at(0, 0.9, 0.9), at(0.9, 0.9, 0.9),
				at(1.0, 0.9, 0.1), at(10, 0.9, 0.1));
		SmartFraming.Plan plan = plan(EDITOR, samples);
		assertEquals(2, plan.moves().size());
		assertFalse(plan.moves().get(0).returning());
		assertFalse(plan.moves().get(1).returning());
		// The second dodge steps back toward the bottom (the nearest clearing corner), which is
		// numerically the layout spot: the camera stepped aside and back.
		SmartFraming.Override settled = plan.at(10_000);
		assertFalse(settled.active());
	}

	@Test
	@DisplayName("a Camera layout segment pins the camera; smart framing re-engages after it")
	void segmentsStandDown() {
		ProjectEditorState editor = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"bottom-right","corner":"bottom-right",
						           "smartFraming":true},
						 "cameraLayoutSegments":[
							{"id":"camera-1","startMs":3000,"endMs":9000,"layout":"top-left"}]}
						"""));
		List<CursorSample> samples = List.of(
				at(0, 0.9, 0.9), at(1, 0.9, 0.9), at(5, 0.9, 0.9), at(10, 0.9, 0.9),
				at(20, 0.9, 0.9));
		SmartFraming.Plan plan = plan(editor, samples);
		// Dodged in the project stretch, back at the layout before the pinned block starts,
		// inert inside it, and dodging again once the block ends.
		assertTrue(plan.at(2000).active());
		assertFalse(plan.at(4000).active());
		assertFalse(plan.at(8000).active());
		assertEquals(3, plan.moves().size());
		// The return lands right when the block takes over.
		SmartFraming.Move back = plan.moves().get(1);
		assertTrue(back.returning());
		assertEquals(3000, back.startMs() + SmartFraming.MOVE_MS, 1e-6);
		assertTrue(plan.at(20_000).active());
	}

	@Test
	@DisplayName("the zoom focus is protected too, even without cursor telemetry")
	void zoomFocusIsProtected() {
		ProjectEditorState editor = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"center","smartFraming":true},
						 "zoomRegions":[{"id":"z1","startMs":0,"endMs":20000,"depth":3,
						                 "focus":{"cx":0.5,"cy":0.5},"mode":"manual"}]}
						"""));
		SmartFraming.Plan plan = plan(editor, List.of());
		assertNotNull(plan);
		assertFalse(plan.isEmpty());
		// Zoomed at the centre, the focus sits where the centred camera was: the card moved away
		// to a corner (the four are equidistant from the centre; the first clearing one wins).
		assertTrue(plan.at(3000).active());
		double y = plan.at(3000).positionY();
		assertTrue(y <= 0.01 || y >= 0.99, "expected a corner, got " + y);
	}

	@Test
	@DisplayName("off, disabled webcam, or a split preset never plans")
	void nothingToPlan() {
		List<CursorSample> samples = List.of(at(0, 0.9, 0.9), at(10, 0.9, 0.9));
		ProjectEditorState off = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"bottom-right","corner":"bottom-right"}}
						"""));
		assertNull(plan(off, samples));
		ProjectEditorState split = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"split-right","smartFraming":true}}
						"""));
		assertNull(plan(split, samples));
	}

	@Test
	@DisplayName("apply substitutes the custom fraction pair and the shrunk size; NONE is identity")
	void applySubstitutes() {
		WebcamSpec spec = ProjectEditorInputs.webcamSpec(EDITOR);
		assertEquals(spec, SmartFraming.apply(spec, SmartFraming.Override.NONE));
		WebcamSpec moved = SmartFraming.apply(spec,
				new SmartFraming.Override(0, 0, 0.5, true));
		assertEquals("custom", moved.positionPreset());
		assertEquals(0, moved.positionX(), 1e-9);
		assertEquals(0, moved.positionY(), 1e-9);
		assertEquals(spec.size() * 0.5, moved.size(), 1e-9);
		assertEquals(spec.width() * 0.5, moved.width(), 1e-9);
		assertEquals(spec.height() * 0.5, moved.height(), 1e-9);
		assertEquals(spec.mirror(), moved.mirror());
		assertEquals(spec.backgroundEffect(), moved.backgroundEffect());
	}

	@Test
	@DisplayName("webcamSpecAt applies the plan on top of the camera-layout resolution")
	void webcamSpecAtAppliesPlan() {
		WebcamSpec base = ProjectEditorInputs.webcamSpec(EDITOR);
		SmartFraming.Plan plan = plan(EDITOR, List.of(at(0, 0.9, 0.9), at(10, 0.9, 0.9)));
		// The shared resolver both the preview and the export call: dodged mid-timeline.
		WebcamSpec dodged = ProjectEditorInputs.webcamSpecAt(base, EDITOR, 2000, plan);
		assertEquals("custom", dodged.positionPreset());
		assertNotEquals(base.positionY(), dodged.positionY(), 1e-6);
		// Before the grace expires the spec is the project layout, plan or no plan.
		assertEquals(base, ProjectEditorInputs.webcamSpecAt(base, EDITOR, 100, plan));
		// A null plan (smart framing off elsewhere) changes nothing.
		assertEquals(base, ProjectEditorInputs.webcamSpecAt(base, EDITOR, 2000, null));
	}

	@Test
	@DisplayName("the plan is deterministic and independent of sample order")
	void deterministic() {
		List<CursorSample> samples = List.of(
				at(2, 0.9, 0.9), at(0, 0.9, 0.9), at(4, 0.9, 0.9), at(6, 0.05, 0.05),
				at(8, 0.05, 0.05));
		SmartFraming.Plan first = plan(EDITOR, samples);
		SmartFraming.Plan second = plan(EDITOR, samples);
		assertEquals(first.moves(), second.moves());
		List<CursorSample> shuffled = List.of(
				at(6, 0.05, 0.05), at(0, 0.9, 0.9), at(8, 0.05, 0.05), at(2, 0.9, 0.9),
				at(4, 0.9, 0.9));
		assertEquals(first.moves(), plan(EDITOR, shuffled).moves());
	}

	@Test
	@DisplayName("a sparse timeline grid keeps the zoom focus protected without cursor samples")
	void gridCoversTelemetryFreeTimelines() {
		// Telemetry stops after a second: the zoom focus alone keeps the schedule alive.
		ProjectEditorState editor = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"center","smartFraming":true},
						 "zoomRegions":[{"id":"z1","startMs":4000,"endMs":12000,"depth":3,
						                 "focus":{"cx":0.5,"cy":0.5},"mode":"manual"}]}
						"""));
		SmartFraming.Plan plan = plan(editor, List.of(at(0, 0.1, 0.1)));
		assertNotNull(plan);
		// The dodge starts inside the zoom window (the focus arrives with the zoom, grace applies),
		// and once the zoom ends the camera glides back home on its own.
		assertEquals(2, plan.moves().size());
		assertTrue(plan.moves().get(0).startMs() >= 4000);
		assertTrue(plan.moves().get(0).startMs() <= 4000 + SmartFraming.GRACE_MS + 200);
		assertTrue(plan.moves().get(1).returning());
		assertTrue(plan.moves().get(1).startMs() >= 12_000);
	}

	private static boolean inside(Rect box, double x, double y, double pad) {
		return x >= box._left - pad && x <= box.getRight() + pad
				&& y >= box._top - pad && y <= box.getBottom() + pad;
	}

	/** The compositor's box for a spec at zoom 1 (the walk and render agree at rest). */
	private static Rect boxOf(WebcamSpec spec, double sizeFactor, double zoomScale) {
		double margin = WebcamOverlay.scalePixels(spec.margin(), STAGE_WIDTH);
		double widthPercent = (spec.width() > 0 ? spec.width() : spec.size()) * sizeFactor;
		double heightPercent = (spec.height() > 0 ? spec.height() : spec.size()) * sizeFactor;
		WebcamOverlay.Size size = WebcamOverlay.dimensionsPx(STAGE_WIDTH, STAGE_HEIGHT,
				widthPercent, heightPercent, margin, zoomScale, spec.reactToZoom());
		WebcamOverlay.Position position = WebcamOverlay.position(STAGE_WIDTH, STAGE_HEIGHT,
				size.width(), size.height(), margin, spec.positionPreset(), spec.positionX(),
				spec.positionY(), spec.corner());
		return Rect.makeXYWH((float) position.x(), (float) position.y(), (float) size.width(),
				(float) size.height());
	}
}
