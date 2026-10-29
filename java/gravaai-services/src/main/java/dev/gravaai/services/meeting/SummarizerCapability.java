package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** A modality an OpenAI-compatible chat model accepts (MeetingSettings.apiChatCapabilities). */
public enum SummarizerCapability {
	TEXT("text"),
	IMAGES("images"),
	VIDEO("video");

	private final String wire;

	SummarizerCapability(String wire) {
		this.wire = wire;
	}

	public @Nullable String wire() {
		return wire;
	}

	public static @Nullable SummarizerCapability fromWire(Object value) {
		if (!(value instanceof String text)) {
			return null;
		}
		for (SummarizerCapability capability : values()) {
			if (capability.wire.equals(text)) {
				return capability;
			}
		}
		return null;
	}

	public static final List<SummarizerCapability> ALL = List.of(TEXT, IMAGES, VIDEO);
}
