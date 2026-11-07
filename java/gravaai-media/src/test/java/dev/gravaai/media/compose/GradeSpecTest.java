package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.GradeTarget;

/** Layer resolution of the grade windows (stage 5.5): per picture, latest start winning. */
class GradeSpecTest {

	private static GradeSpec.Window window(double startMs, double endMs, double brightness,
			GradeTarget targets) {
		return new GradeSpec.Window(startMs, endMs, brightness, 0, 0, 0, 1, targets);
	}

	@Test
	@DisplayName("a screen-only grade shades the screen but not the camera")
	void screenOnlyGrade() {
		GradeSpec spec = new GradeSpec(List.of(window(0, 1000, 20, GradeTarget.MAIN)));

		assertEquals(20, spec.at(500, GradeTarget.MAIN).orElseThrow().brightness());
		assertTrue(spec.at(500, GradeTarget.CAMERA).isEmpty());
	}

	@Test
	@DisplayName("a camera-only grade shades the camera but not the screen")
	void cameraOnlyGrade() {
		GradeSpec spec = new GradeSpec(List.of(window(0, 1000, -30, GradeTarget.CAMERA)));

		assertTrue(spec.at(500, GradeTarget.MAIN).isEmpty());
		assertEquals(-30, spec.at(500, GradeTarget.CAMERA).orElseThrow().brightness());
	}

	@Test
	@DisplayName("a both grade shades both pictures with the same sliders")
	void bothGrade() {
		GradeSpec spec = new GradeSpec(List.of(window(0, 1000, 10, GradeTarget.BOTH)));

		assertEquals(10, spec.at(500, GradeTarget.MAIN).orElseThrow().brightness());
		assertEquals(10, spec.at(500, GradeTarget.CAMERA).orElseThrow().brightness());
	}

	@Test
	@DisplayName("a screen and a camera grade share a span; each picture gets its own")
	void oneForEach() {
		GradeSpec spec = new GradeSpec(List.of(
				window(0, 1000, 40, GradeTarget.MAIN),
				window(0, 1000, -40, GradeTarget.CAMERA)));

		assertEquals(40, spec.at(500, GradeTarget.MAIN).orElseThrow().brightness());
		assertEquals(-40, spec.at(500, GradeTarget.CAMERA).orElseThrow().brightness());
	}

	@Test
	@DisplayName("within one layer the later-starting window wins; other layers are untouched")
	void latestStartWinsPerLayer() {
		GradeSpec spec = new GradeSpec(List.of(
				window(0, 2000, 10, GradeTarget.MAIN),
				window(1000, 2000, 50, GradeTarget.CAMERA),
				window(1000, 2000, 20, GradeTarget.MAIN)));

		assertEquals(20, spec.at(1500, GradeTarget.MAIN).orElseThrow().brightness());
		assertEquals(50, spec.at(1500, GradeTarget.CAMERA).orElseThrow().brightness());
		// Before the second window starts, the screen still shows the first grade.
		assertEquals(10, spec.at(500, GradeTarget.MAIN).orElseThrow().brightness());
		assertTrue(spec.at(500, GradeTarget.CAMERA).isEmpty());
	}

	@Test
	@DisplayName("the winner is skipped when neutral, falling through to no grade")
	void neutralWinnerIsSkipped() {
		GradeSpec spec = new GradeSpec(List.of(
				window(0, 1000, 20, GradeTarget.MAIN),
				window(0, 1000, 0, GradeTarget.MAIN)));

		assertTrue(spec.at(500, GradeTarget.MAIN).isEmpty());
	}

	@Test
	@DisplayName("windows outside the span and the empty spec resolve to nothing")
	void outsideAndNone() {
		GradeSpec spec = new GradeSpec(List.of(window(1000, 2000, 20, GradeTarget.BOTH)));

		assertTrue(spec.at(500, GradeTarget.MAIN).isEmpty());
		assertTrue(spec.at(2500, GradeTarget.CAMERA).isEmpty());
		assertTrue(GradeSpec.NONE.at(500, GradeTarget.MAIN).equals(Optional.empty()));
	}
}
