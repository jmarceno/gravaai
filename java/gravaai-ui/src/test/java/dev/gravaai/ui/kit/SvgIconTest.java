package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The runtime icon loader must parse exactly what {@code java/tools/extract-icons.mjs} wrote. */
class SvgIconTest {

	@DisplayName("parses paths and circles, inheriting stroke-width from the root var() fallback")
	@Test
	void parsesShapesAndInheritedPaint() throws IOException {
		String svg = """
				<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"
				     stroke-width="var(--solar-stroke-width, 1.5)">
				  <circle cx="12" cy="12" r="2" stroke="currentColor" stroke-linecap="round"/>
				  <path d="M4 4L20 20" stroke="currentColor" stroke-linecap="round"/>
				</svg>
				""";
		SvgIcon.Parsed parsed = SvgIcon.parse(svg);
		assertEquals(24, parsed.width());
		assertEquals(2, parsed.shapes().size());
		SvgIcon.SvgShape circle = parsed.shapes().get(0);
		assertEquals("circle", circle.element());
		assertEquals("M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z", circle.pathData());
		assertEquals(SvgIcon.PaintKind.NONE, circle.paint().fillKind());
		assertEquals(SvgIcon.PaintKind.CURRENT_COLOR, circle.paint().strokeKind());
		assertEquals(1.5, circle.paint().strokeWidth());
		assertEquals(javafx.scene.shape.StrokeLineCap.ROUND, circle.paint().lineCap());
	}

	@DisplayName("converts ellipse, rect (rounded), line and polygon to path data")
	@Test
	void convertsShapes() throws IOException {
		SvgIcon.Parsed parsed = SvgIcon.parse("""
				<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
				  <ellipse cx="12" cy="12" rx="4" ry="2"/>
				  <rect x="1" y="2" width="10" height="6" rx="2" ry="2"/>
				  <line x1="0" y1="0" x2="4" y2="4"/>
				  <polygon points="0,0 4,0 2,4"/>
				</svg>
				""");
		assertEquals(4, parsed.shapes().size());
		assertEquals("M8 12a4 2 0 1 0 8 0a4 2 0 1 0 -8 0Z", parsed.shapes().get(0).pathData());
		assertEquals("M3 2h6a2 2 0 0 1 2 2v2a2 2 0 0 1 -2 2h-6a2 2 0 0 1 -2 -2v-2a2 2 0 0 1 2 -2Z",
				parsed.shapes().get(1).pathData());
		assertEquals("M0 0L4 4", parsed.shapes().get(2).pathData());
		assertEquals("M0 0L4 0L2 4Z", parsed.shapes().get(3).pathData());
		assertEquals(SvgIcon.PaintKind.CURRENT_COLOR, parsed.shapes().get(0).paint().fillKind());
	}

	@DisplayName("keeps path and circle transforms in document order")
	@Test
	void parsesTransforms() throws IOException {
		SvgIcon.Parsed parsed = SvgIcon.parse("""
				<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none">
				  <circle cx="12" cy="12" r="2" transform="rotate(90 12 5)" stroke="currentColor"/>
				</svg>
				""");
		List<SvgIcon.SvgTransform> transforms = parsed.shapes().get(0).transforms();
		assertEquals(1, transforms.size());
		assertEquals("rotate", transforms.get(0).function());
		assertEquals(90, transforms.get(0).parameters()[0]);
		assertEquals(12, transforms.get(0).parameters()[1]);
		assertEquals(5, transforms.get(0).parameters()[2]);
	}

	@DisplayName("paint values: currentColor, none, hex, var() fallback")
	@Test
	void parsesPaintValues() {
		assertEquals(SvgIcon.PaintKind.CURRENT_COLOR, SvgIcon.paintValue("currentColor").kind());
		assertEquals(SvgIcon.PaintKind.NONE, SvgIcon.paintValue("none").kind());
		assertEquals(SvgIcon.PaintKind.COLOR, SvgIcon.paintValue("#2fb3a3").kind());
		assertEquals(javafx.scene.paint.Color.web("#2fb3a3"), SvgIcon.paintValue("#2fb3a3").color());
		assertEquals(SvgIcon.PaintKind.CURRENT_COLOR,
				SvgIcon.paintValue("var(--solar-color, currentColor)").kind());
		assertEquals(1.5, SvgIcon.length("var(--solar-stroke-width, 1.5)"));
	}

	@DisplayName("throws on a non-SVG document")
	@Test
	void rejectsNonSvg() {
		assertThrows(IllegalArgumentException.class,
				() -> SvgIcon.parse("<html><body/></html>"));
	}

	@DisplayName("parses the extracted solar linear/bold and phosphor icon resources")
	@Test
	void parsesExtractedResources() throws IOException {
		SvgIcon.Parsed add = load("/kit/icons/solar/linear/add.svg");
		assertEquals(24, add.width());
		assertEquals(2, add.shapes().size());
		SvgIcon.Parsed boldAdd = load("/kit/icons/solar/bold/add.svg");
		assertEquals(1, boldAdd.shapes().size());
		assertEquals(SvgIcon.PaintKind.CURRENT_COLOR, boldAdd.shapes().get(0).paint().fillKind());
		SvgIcon.Parsed export = load("/kit/icons/phosphor/regular/Export.svg");
		assertEquals(256, export.width());
		assertEquals(1, export.shapes().size());
		SvgIcon.Parsed duotone = load("/kit/icons/phosphor/duotone/Export.svg");
		assertEquals(2, duotone.shapes().size());
		assertEquals(0.2, duotone.shapes().get(0).paint().opacity());
		SvgIcon.Parsed slash = load("/kit/icons/solar/linear/MicrophoneSlash.svg");
		assertEquals(6, slash.shapes().size());
		assertEquals(SvgIcon.PaintKind.CURRENT_COLOR, slash.shapes().get(5).paint().strokeKind());
	}

	@DisplayName("the micro-dots icon keeps its per-circle rotate transforms")
	@Test
	void parsesMenuDotsVertical() throws IOException {
		SvgIcon.Parsed dots = load("/kit/icons/solar/linear/menu-dots-vertical.svg");
		assertEquals(3, dots.shapes().size());
		assertFalse(dots.shapes().get(0).transforms().isEmpty());
		assertEquals("rotate", dots.shapes().get(0).transforms().get(0).function());
	}

	@DisplayName("a missing path value yields no shape instead of throwing")
	@Test
	void skipsEmptyPath() throws IOException {
		SvgIcon.Parsed parsed = SvgIcon.parse("""
				<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path/></svg>
				""");
		assertTrue(parsed.shapes().isEmpty());
	}

	private static SvgIcon.Parsed load(String resource) throws IOException {
		try (InputStream in = SvgIconTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, "missing icon resource " + resource);
			return SvgIcon.parse(in);
		}
	}
}
