package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.CosmicOutput;

/**
 * Tests for the geometry matching the source list uses on COSMIC (plan §5.5). Expected values come
 * from {@code sources.ts} (ordering and naming), {@code sources.ts} (display-id-or-index matching)
 * and {@code createCosmicScreenSource} in {@code captureSelection.ts}.
 */
class DisplayOutputResolverTest {

	private static final List<DisplayOutputResolver.DisplayInfo> DISPLAYS = List.of(
			new DisplayOutputResolver.DisplayInfo(7, new CaptureArea(1728, -200, 2560, 1440)),
			new DisplayOutputResolver.DisplayInfo(3, new CaptureArea(0, 0, 1728, 1117)),
			new DisplayOutputResolver.DisplayInfo(5, new CaptureArea(0, 0, 1280, 720)));

	@Test
	@DisplayName("numbers screens left to right, then top to bottom, then by display id")
	void sortsDisplaysLikeTheSourceList() {
		List<DisplayOutputResolver.DisplayInfo> sorted = DisplayOutputResolver.sortDisplays(DISPLAYS);
		assertEquals(List.of(3, 5, 7), sorted.stream().map(DisplayOutputResolver.DisplayInfo::id).toList());
	}

	@Test
	@DisplayName("marks the primary display in the screen name")
	void namesScreens() {
		assertEquals("Screen 1 (Primary)", DisplayOutputResolver.screenSourceName(0, true));
		assertEquals("Screen 2", DisplayOutputResolver.screenSourceName(1, false));
	}

	@Test
	@DisplayName("ties a cosmic output to the display at the same spot")
	void findsTheDisplayForAnOutput() {
		List<DisplayOutputResolver.DisplayInfo> sorted = DisplayOutputResolver.sortDisplays(DISPLAYS);
		CosmicOutput rotated = new CosmicOutput("DP-2", 1728, -200, 2560, 1440);
		assertEquals(7, DisplayOutputResolver.findDisplayForOutput(rotated, sorted).id());
	}

	@Test
	@DisplayName("leaves an output without a display unpaired")
	void leavesAnUnknownOutputUnpaired() {
		CosmicOutput output = new CosmicOutput("HDMI-A-1", 4000, 0, 1920, 1080);
		assertNull(DisplayOutputResolver.findDisplayForOutput(output, DISPLAYS));
		assertNull(CaptureSelection.createCosmicScreenSource(output, DISPLAYS).displayId());
		assertEquals(
				"Screen HDMI-A-1 (1920×1080)",
				CaptureSelection.createCosmicScreenSource(output, DISPLAYS).name());
	}

	@Test
	@DisplayName("matches live screen sources by display id first")
	void matchesByDisplayIdFirst() {
		List<DisplayOutputResolver.ScreenSourceRef> sources = List.of(
				new DisplayOutputResolver.ScreenSourceRef("screen:0:0", "7", "Screen 7"),
				new DisplayOutputResolver.ScreenSourceRef("screen:1:0", "3", "Screen 3"));
		List<DisplayOutputResolver.DisplayInfo> displays = DisplayOutputResolver.sortDisplays(DISPLAYS);
		List<DisplayOutputResolver.ScreenMatch> matches =
				DisplayOutputResolver.matchScreenSources(displays, sources);
		assertEquals("screen:1:0", matches.get(0).source().id());
		assertNull(matches.get(1).source());
		assertEquals("screen:0:0", matches.get(2).source().id());
	}

	@Test
	@DisplayName("falls back to position when every display has a live source")
	void matchesByPositionWhenTheCountsMatch() {
		List<DisplayOutputResolver.ScreenSourceRef> sources = List.of(
				new DisplayOutputResolver.ScreenSourceRef("screen:0:0", "99", "Left"),
				new DisplayOutputResolver.ScreenSourceRef("screen:1:0", "98", "Middle"),
				new DisplayOutputResolver.ScreenSourceRef("screen:2:0", "97", "Right"));
		List<DisplayOutputResolver.DisplayInfo> displays = DisplayOutputResolver.sortDisplays(DISPLAYS);
		List<DisplayOutputResolver.ScreenMatch> matches =
				DisplayOutputResolver.matchScreenSources(displays, sources);
		assertEquals("screen:0:0", matches.get(0).source().id());
		assertEquals("screen:1:0", matches.get(1).source().id());
		assertEquals("screen:2:0", matches.get(2).source().id());
	}

	@Test
	@DisplayName("does not borrow a source when the counts differ")
	void doesNotBorrowASourceWhenCountsDiffer() {
		List<DisplayOutputResolver.ScreenSourceRef> sources =
				List.of(new DisplayOutputResolver.ScreenSourceRef("screen:0:0", "7", "Screen 7"));
		List<DisplayOutputResolver.DisplayInfo> displays = DisplayOutputResolver.sortDisplays(DISPLAYS);
		List<DisplayOutputResolver.ScreenMatch> matches =
				DisplayOutputResolver.matchScreenSources(displays, sources);
		assertNull(matches.get(0).source());
		assertNull(matches.get(1).source());
		assertEquals("screen:0:0", matches.get(2).source().id());
	}

	@Test
	@DisplayName("uses the cosmic source id and display id in the selected source")
	void buildsTheSelectedCosmicSource() {
		CosmicOutput output = new CosmicOutput("DP-1", 0, 0, 1728, 1117);
		SelectedSource source = CaptureSelection.createCosmicScreenSource(output, DISPLAYS);
		assertEquals("screen:cosmic:DP-1", source.id());
		assertEquals("Screen DP-1 (1728×1117)", source.name());
		assertEquals("3", source.displayId());
		assertEquals(SourceType.SCREEN, source.sourceType());
	}
}
