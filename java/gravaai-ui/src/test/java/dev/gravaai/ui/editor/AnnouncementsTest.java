package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/** Port of {@code src/lib/announcements.ts} behaviours (plan §5.12, P2.6). */
class AnnouncementsTest {

	private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");

	private static Announcements.Feed parse(String json) {
		return Announcements.parseFeed(Json.parseJsonWithByteOrderMark(json));
	}

	@Test
	@DisplayName("treats a missing or malformed feed as empty")
	void treatsMalformedFeedAsEmpty() {
		assertEquals(Announcements.Feed.EMPTY, Announcements.parseFeed(null));
		assertEquals(Announcements.Feed.EMPTY, parse("[]"));
		assertEquals(Announcements.Feed.EMPTY, parse("{\"settings\":{}}"));
		assertEquals(Announcements.Feed.EMPTY, Announcements.parseFeedJson(""));
	}

	@Test
	@DisplayName("reads the aspect ratio setting and validates its bounds")
	void readsAspectRatioSetting() {
		assertEquals("4:3", parse("{\"settings\":{\"aspectRatio\":\"4:3\"},\"announcements\":[]}")
				.aspectRatio());
		assertNull(parse("{\"settings\":{\"aspectRatio\":\"10:1\"},\"announcements\":[]}")
				.aspectRatio());
		assertNull(parse("{\"settings\":{\"aspectRatio\":\"wide\"},\"announcements\":[]}")
				.aspectRatio());
	}

	@Test
	@DisplayName("parses a valid announcement and drops invalid ones")
	void parsesAnnouncements() {
		Announcements.Feed feed = parse("""
				{"announcements":[
				{"id":"a","title":"Hello","body":"World","presentation":"banner",
				 "audience":"editor","priority":5.4,"displayDurationSeconds":10,
				 "maxImpressions":2,"startsAt":"2026-01-01T00:00:00Z",
				 "controls":{"close":false,"action":true}},
				{"id":"","title":"No id","body":"x"},
				{"id":"b","title":"No body"},
				{"id":"c","title":"  ","body":"x"},
				{"id":"d","title":"Kept","body":"Body","priority":500}
				]}
				""");
		assertEquals(2, feed.announcements().size());
		Announcements.Announcement first = feed.announcements().get(0);
		assertEquals("a", first.id());
		assertEquals(Announcements.Presentation.BANNER, first.presentation());
		assertEquals(Announcements.Audience.EDITOR, first.audience());
		assertEquals(5, first.priority());
		assertEquals(10, first.displayDurationSeconds());
		assertEquals(2, first.maxImpressions());
		assertFalse(first.controls().close());
		assertTrue(first.controls().action());
		assertEquals(100, feed.announcements().get(1).priority());
	}

	@Test
	@DisplayName("strips media from text-only presentations")
	void stripsMediaFromTextOnlyPresentations() {
		Announcements.Announcement banner = parse("""
				{"announcements":[{"id":"a","title":"T","body":"B","presentation":"notification",
				"media":{"type":"image","url":"/media/x.png"},"mediaMode":"cover"}]}
				""").announcements().get(0);
		assertNull(banner.media());
		assertNull(banner.mediaMode());

		Announcements.Announcement popup = parse("""
				{"announcements":[{"id":"b","title":"T","body":"B",
				"media":{"type":"image","url":"https://example.com/x.png","alt":"Pic"},
				"mediaMode":"cover"}]}
				""").announcements().get(0);
		assertEquals("https://example.com/x.png", popup.media().url());
		assertEquals("Pic", popup.media().alt());
		assertEquals(Announcements.MediaMode.COVER, popup.mediaMode());
	}

	@Test
	@DisplayName("validates announcement actions and media URLs")
	void validatesActionsAndUrls() {
		Announcements.Announcement sectionAction = parse("""
				{"announcements":[{"id":"a","title":"T","body":"B",
				"action":{"label":"Open","section":"settings"}}]}
				""").announcements().get(0);
		assertInstanceOf(Announcements.SectionAction.class, sectionAction.action());

		Announcements.Announcement urlAction = parse("""
				{"announcements":[{"id":"a","title":"T","body":"B",
				"action":{"label":"Open","url":"https://example.com/x"}}]}
				""").announcements().get(0);
		assertInstanceOf(Announcements.UrlAction.class, urlAction.action());

		// An invalid URL is dropped; a valid section then wins (same as Boolean(url)===Boolean(section)).
		Announcements.Announcement fallbackSection = parse("""
				{"announcements":[{"id":"a","title":"T","body":"B",
				"action":{"label":"Bad","url":"http://example.com/x","section":"settings"}}]}
				""").announcements().get(0);
		assertEquals("settings",
				((Announcements.SectionAction) fallbackSection.action()).section());

		Announcements.Announcement noValidAction = parse("""
				{"announcements":[{"id":"a","title":"T","body":"B",
				"action":{"label":"Bad","url":"http://example.com/x"}}]}
				""").announcements().get(0);
		assertNull(noValidAction.action());
	}

	@Test
	@DisplayName("compares versions numerically")
	void comparesVersions() {
		assertEquals(1, Announcements.compareVersions("1.2.10", "1.2.9"));
		assertEquals(-1, Announcements.compareVersions("1.2.9", "1.2.10"));
		assertEquals(0, Announcements.compareVersions("v2.0.0", "2.0"));
		assertEquals(0, Announcements.compareVersions("1.2", "1.2.0"));
		assertEquals(0, Announcements.compareVersions("1.2.0-beta", "1.2.0"));
	}

	private static Announcements.Announcement ann(String id, int priority) {
		return new Announcements.Announcement(id, id, "body", Announcements.Presentation.BANNER,
				Announcements.Audience.ALL, priority, null, null, null, null, null, null, null, null,
				null, null);
	}

	@Test
	@DisplayName("selects per audience, dismissals, impressions, dates and versions")
	void selectsAnnouncements() {
		Announcements.Announcement all = ann("all", 1);
		Announcements.Announcement editor = new Announcements.Announcement("editor", "e", "b",
				Announcements.Presentation.BANNER, Announcements.Audience.EDITOR, 3, null, null,
				null, null, null, null, null, null, null, null);
		Announcements.Announcement otherAudience = new Announcements.Announcement("other", "o", "b",
				Announcements.Presentation.BANNER, Announcements.Audience.ALL, 5, null, null, null,
				null, null, null, null, null, null, null);
		Announcements.Announcement future = new Announcements.Announcement("future", "f", "b",
				Announcements.Presentation.BANNER, Announcements.Audience.ALL, 9, null, null, null,
				null, null, null, "2027-01-01T00:00:00Z", null, null, null);
		Announcements.Announcement tooNew = new Announcements.Announcement("new", "n", "b",
				Announcements.Presentation.BANNER, Announcements.Audience.ALL, 9, null, null, null,
				null, null, null, null, null, "2.0.0", null);

		List<Announcements.Announcement> selected = Announcements.selectAnnouncements(
				List.of(all, editor, future, tooNew), List.of(otherAudience),
				Set.of("future"), Map.of(), "1.0.0", Announcements.Audience.EDITOR, NOW);

		assertEquals(List.of(editor, all),
				selected.stream().filter(announcement -> !announcement.id().equals("other"))
						.filter(announcement -> !announcement.id().equals("future"))
						.toList());
		assertTrue(selected.stream().allMatch(announcement -> !announcement.id().equals("future")));
	}

	@Test
	@DisplayName("lets remote announcements replace bundled ones with the same id")
	void remoteOverridesBundled() {
		Announcements.Announcement bundled = ann("same", 1);
		Announcements.Announcement remote = new Announcements.Announcement("same", "remote", "b",
				Announcements.Presentation.BANNER, Announcements.Audience.ALL, 2, null, null, null,
				null, null, null, null, null, null, null);

		List<Announcements.Announcement> selected = Announcements.selectAnnouncements(
				List.of(bundled), List.of(remote), Set.of(), Map.of(), "1.0.0",
				Announcements.Audience.EDITOR, NOW);

		assertEquals(1, selected.size());
		assertEquals("remote", selected.get(0).title());
	}

	@Test
	@DisplayName("filters announcements past their impression limit")
	void filtersByImpressions() {
		Announcements.Announcement limited = new Announcements.Announcement("limited", "l", "b",
				Announcements.Presentation.BANNER, Announcements.Audience.ALL, 1, null, null, 2,
				null, null, null, null, null, null, null);

		assertEquals(1, Announcements.selectAnnouncements(List.of(limited), List.of(), Set.of(),
				Map.of(), "1.0.0", Announcements.Audience.EDITOR, NOW).size());
		assertEquals(1, Announcements.selectAnnouncements(List.of(limited), List.of(), Set.of(),
				Map.of("limited", 1), "1.0.0", Announcements.Audience.EDITOR, NOW).size());
		assertEquals(0, Announcements.selectAnnouncements(List.of(limited), List.of(), Set.of(),
				Map.of("limited", 2), "1.0.0", Announcements.Audience.EDITOR, NOW).size());
	}

	@Test
	@DisplayName("sorts announcements by priority")
	void sortsByPriority() {
		List<Announcements.Announcement> selected = Announcements.selectAnnouncements(
				List.of(ann("low", 0), ann("high", 10), ann("mid", 5)), List.of(), Set.of(),
				Map.of(), "1.0.0", Announcements.Audience.EDITOR, NOW);
		assertEquals(List.of("high", "mid", "low"),
				selected.stream().map(Announcements.Announcement::id).toList());
	}
}
