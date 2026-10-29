package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Announcements feed model and selection (port of {@code src/lib/announcements.ts}, plan §5.12):
 * strict parse, version comparison and audience/date/version/impression filtering plus priority
 * ordering. The bundled feed for this release is empty apart from the 4:3 aspect setting.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class Announcements {

	public enum Audience {
		ALL("all"), EDITOR("editor");

		private final String wire;

		Audience(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	public enum Presentation {
		POPUP("popup"), NOTIFICATION("notification"), BANNER("banner"), EXPORT("export");

		private final String wire;

		Presentation(String wire) {
			this.wire = wire;
		}

		public @Nullable String wire() {
			return wire;
		}

		static @Nullable Presentation fromWire(String value) {
			for (Presentation presentation : values()) {
				if (presentation != POPUP && presentation.wire.equals(value)) {
					return presentation;
				}
			}
			return null;
		}
	}

	public enum MediaMode {
		BANNER("banner"), COVER("cover");

		private final String wire;

		MediaMode(String wire) {
			this.wire = wire;
		}

		static @Nullable MediaMode fromWire(String value) {
			for (MediaMode mode : values()) {
				if (mode.wire.equals(value)) {
					return mode;
				}
			}
			return null;
		}
	}

	public @Nullable record Media(@Nullable String type, String url, @Nullable String alt, @Nullable String posterUrl) {}

	public sealed interface Action permits UrlAction, SectionAction {}

	public @Nullable record UrlAction(String label, String url) implements Action {}

	public @Nullable record SectionAction(String label, @Nullable String section) implements Action {}

	public @Nullable record Controls(
			@Nullable Boolean close, @Nullable Boolean dismiss, @Nullable Boolean action, @Nullable Boolean navigation,
			@Nullable Boolean indicators) {}

	public @Nullable record Announcement(
			String id, String title, String body, @Nullable Presentation presentation, Audience audience,
			int priority, @Nullable MediaMode mediaMode, @Nullable Integer displayDurationSeconds,
			@Nullable Integer maxImpressions, @Nullable Controls controls, @Nullable Media media, @Nullable Action action, @Nullable String startsAt,
			@Nullable String endsAt, @Nullable String minVersion, @Nullable String maxVersion) {}

	public @Nullable record Feed(@Nullable String aspectRatio, List<Announcement> announcements) {

		public static final Feed EMPTY = new Feed(null, List.of());
	}

	private static final int MAX_ANNOUNCEMENTS = 50;
	private static final int MAX_ID_LENGTH = 100;
	private static final int MAX_TITLE_LENGTH = 160;
	private static final int MAX_BODY_LENGTH = 2_000;
	private static final int MAX_LABEL_LENGTH = 80;
	private static final int MAX_URL_LENGTH = 2_048;

	@Nullable private Announcements() {}

	/** {@code BUNDLED_ANNOUNCEMENT_FEED}. */
	public static @Nullable Feed bundledFeed() {
		return new Feed("4:3", List.of());
	}

	/** {@code parseAnnouncementFeed}. */
	public static @Nullable Feed parseFeed(JsonNode value) {
		if (value == null || !value.isObject() || !value.path("announcements").isArray()) {
			return Feed.EMPTY;
		}
		String aspectRatio = value.path("settings").isObject()
				? readAspectRatio(value.path("settings").get("aspectRatio")) : null;
		List<Announcement> items = new ArrayList<>();
		int count = 0;
		for (JsonNode item : value.get("announcements")) {
			if (count++ >= MAX_ANNOUNCEMENTS) {
				break;
			}
			Announcement announcement = parseAnnouncement(item);
			if (announcement != null) {
				items.add(announcement);
			}
		}
		return new Feed(aspectRatio, List.copyOf(items));
	}

	private static @Nullable Announcement parseAnnouncement(JsonNode value) {
		if (value == null || !value.isObject()) {
			return null;
		}
		String id = readTrimmedString(value.get("id"), MAX_ID_LENGTH);
		String title = readTrimmedString(value.get("title"), MAX_TITLE_LENGTH);
		String body = readTrimmedString(value.get("body"), MAX_BODY_LENGTH);
		if (id == null || title == null || body == null) {
			return null;
		}
		Presentation presentation = Presentation.fromWire(text(value.get("presentation")));
		boolean textOnly = presentation == Presentation.NOTIFICATION
				|| presentation == Presentation.BANNER || presentation == Presentation.EXPORT;
		int priority = value.has("priority") && value.get("priority").isNumber()
				? (int) Math.max(-100, Math.min(100, Math.round(value.get("priority").doubleValue())))
				: 0;
		Media media = textOnly ? null : parseMedia(value.get("media"));
		Action action = parseAction(value.get("action"));
		Controls controls = parseControls(value.get("controls"));
		MediaMode mediaMode = textOnly ? null : MediaMode.fromWire(text(value.get("mediaMode")));
		return new Announcement(id, title, body, presentation,
				"editor".equals(text(value.get("audience"))) ? Audience.EDITOR : Audience.ALL,
				priority, mediaMode,
				readBoundedInteger(value.get("displayDurationSeconds"), 3, 300),
				readBoundedInteger(value.get("maxImpressions"), 1, 100), controls, media, action,
				readDate(value.get("startsAt")), readDate(value.get("endsAt")),
				readTrimmedString(value.get("minVersion"), 64),
				readTrimmedString(value.get("maxVersion"), 64));
	}

	private static @Nullable Media parseMedia(JsonNode value) {
		if (value == null || !value.isObject()) {
			return null;
		}
		String type = text(value.get("type"));
		if (!"image".equals(type) && !"video".equals(type)) {
			return null;
		}
		String url = readSafeUrl(value.get("url"), true);
		if (url == null) {
			return null;
		}
		String posterUrl = "video".equals(type) ? readSafeUrl(value.get("posterUrl"), true) : null;
		return new Media(type, url, readTrimmedString(value.get("alt"), 300), posterUrl);
	}

	private static @Nullable Action parseAction(JsonNode value) {
		if (value == null || !value.isObject()) {
			return null;
		}
		String label = readTrimmedString(value.get("label"), MAX_LABEL_LENGTH);
		String url = readSafeUrl(value.get("url"), false);
		String section = isEditorSection(text(value.get("section")))
				? text(value.get("section")) : null;
		if (label == null || (url != null) == (section != null)) {
			return null;
		}
		return url != null ? new UrlAction(label, url) : new SectionAction(label, section);
	}

	private static @Nullable boolean isEditorSection(@Nullable String value) {
		return value != null && List.of("scene", "cursor", "webcam", "captions", "settings",
				"extensions").contains(value);
	}

	private static @Nullable Controls parseControls(JsonNode value) {
		if (value == null || !value.isObject()) {
			return null;
		}
		Controls controls = new Controls(booleanOrNull(value.get("close")),
				booleanOrNull(value.get("dismiss")), booleanOrNull(value.get("action")),
				booleanOrNull(value.get("navigation")), booleanOrNull(value.get("indicators")));
		return controls.close() == null && controls.dismiss() == null
				&& controls.action() == null && controls.navigation() == null
				&& controls.indicators() == null ? null : controls;
	}

	private static @Nullable Boolean booleanOrNull(JsonNode value) {
		return value != null && value.isBoolean() ? value.booleanValue() : null;
	}

	private static @Nullable String text(JsonNode value) {
		return value != null && value.isString() ? value.asString() : null;
	}

	private static @Nullable String readTrimmedString(JsonNode value, int maxLength) {
		if (value == null || !value.isString()) {
			return null;
		}
		String trimmed = value.asString().strip();
		return !trimmed.isEmpty() && trimmed.length() <= maxLength ? trimmed : null;
	}

	private static @Nullable String readDate(JsonNode value) {
		String date = readTrimmedString(value, 64);
		if (date == null) {
			return null;
		}
		try {
			Instant.parse(date);
			return date;
		} catch (RuntimeException error) {
			try {
				java.time.OffsetDateTime.parse(date);
				return date;
			} catch (RuntimeException ignored) {
				return null;
			}
		}
	}

	private static @Nullable String readAspectRatio(JsonNode value) {
		String ratio = readTrimmedString(value, 16);
		if (ratio == null || !ratio.matches("^(\\d{1,3}):(\\d{1,3})$")) {
			return null;
		}
		String[] parts = ratio.split(":");
		double width = Double.parseDouble(parts[0]);
		double height = Double.parseDouble(parts[1]);
		double numeric = width / height;
		return width > 0 && height > 0 && numeric >= 0.4 && numeric <= 3 ? ratio : null;
	}

	private static @Nullable Integer readBoundedInteger(JsonNode value, int minimum, int maximum) {
		if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
			return null;
		}
		long rounded = Math.round(value.doubleValue());
		return rounded >= minimum && rounded <= maximum ? (int) rounded : null;
	}

	private static @Nullable String readSafeUrl(JsonNode value, boolean allowRelative) {
		String url = readTrimmedString(value, MAX_URL_LENGTH);
		if (url == null) {
			return null;
		}
		if (allowRelative && url.startsWith("/") && !url.startsWith("//")) {
			return url;
		}
		try {
			java.net.URI parsed = java.net.URI.create(url);
			return "https".equalsIgnoreCase(parsed.getScheme()) && parsed.getUserInfo() == null
					? url : null;
		} catch (RuntimeException error) {
			return null;
		}
	}

	/** {@code compareVersions}. */
	public static int compareVersions(String left, String right) {
		List<Integer> leftParts = versionParts(left);
		List<Integer> rightParts = versionParts(right);
		int length = Math.max(leftParts.size(), rightParts.size());
		for (int index = 0; index < length; index++) {
			int difference = (index < leftParts.size() ? leftParts.get(index) : 0)
					- (index < rightParts.size() ? rightParts.get(index) : 0);
			if (difference != 0) {
				return difference > 0 ? 1 : -1;
			}
		}
		return 0;
	}

	private static List<Integer> versionParts(String version) {
		String core = version == null ? "" : version.strip().replaceFirst("(?i)^v", "");
		int dash = core.indexOf('-');
		if (dash >= 0) {
			core = core.substring(0, dash);
		}
		List<Integer> parts = new ArrayList<>();
		for (String part : core.split("\\.")) {
			try {
				parts.add(Integer.parseInt(part));
			} catch (NumberFormatException error) {
				parts.add(0);
			}
		}
		return parts;
	}

	/** {@code selectAnnouncements}. */
	public static List<Announcement> selectAnnouncements(
			List<Announcement> bundled, List<Announcement> remote, Set<String> dismissedIds,
			Map<String, Integer> impressionCounts, String appVersion, Audience audience,
			Instant now) {
		Map<String, Announcement> byId = new LinkedHashMap<>();
		for (Announcement announcement : bundled) {
			byId.put(announcement.id(), announcement);
		}
		for (Announcement announcement : remote) {
			byId.put(announcement.id(), announcement);
		}
		long nowMs = now.toEpochMilli();
		List<Announcement> selected = new ArrayList<>();
		for (Announcement announcement : byId.values()) {
			if (dismissedIds != null && dismissedIds.contains(announcement.id())) {
				continue;
			}
			Integer maxImpressions = announcement.maxImpressions();
			if (maxImpressions != null && impressionCounts != null
					&& impressionCounts.getOrDefault(announcement.id(), 0) >= maxImpressions) {
				continue;
			}
			if (announcement.audience() != Audience.ALL && announcement.audience() != audience) {
				continue;
			}
			if (announcement.startsAt() != null && parseInstant(announcement.startsAt()) > nowMs) {
				continue;
			}
			if (announcement.endsAt() != null && parseInstant(announcement.endsAt()) < nowMs) {
				continue;
			}
			if (announcement.minVersion() != null
					&& compareVersions(appVersion, announcement.minVersion()) < 0) {
				continue;
			}
			if (announcement.maxVersion() != null
					&& compareVersions(appVersion, announcement.maxVersion()) > 0) {
				continue;
			}
			selected.add(announcement);
		}
		selected.sort((left, right) -> Integer.compare(right.priority(), left.priority()));
		return selected;
	}

	private static long parseInstant(String value) {
		try {
			return Instant.parse(value).toEpochMilli();
		} catch (RuntimeException error) {
			return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli();
		}
	}

	/** Parses a feed from stored JSON ({@code null}/blank → empty feed). */
	public static @Nullable Feed parseFeedJson(String json) {
		return json == null || json.isBlank() ? Feed.EMPTY
				: parseFeed(Json.parseJsonWithByteOrderMark(json));
	}
}
