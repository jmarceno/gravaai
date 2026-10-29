package dev.gravaai.services.meeting;

import tools.jackson.databind.JsonNode;

/**
 * Speaker label on a transcript segment. {@code null} means the input had a single audio track and
 * no speaker labels (electron/ipc/meeting/transcript.ts).
 */
public enum Speaker {
	ME("Me"),
	REMOTE("Remote");

	private final String label;

	Speaker(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/**
	 * whisper.cpp writes the diarised speaker as the JSON string {@code "0"} or {@code "1"}; any
	 * other value means "unknown" (the TS code compares with {@code ===} against the strings).
	 */
	public static Speaker fromWhisperIndex(JsonNode node) {
		if (node == null || !node.isString()) {
			return null;
		}
		return switch (node.asString()) {
			case "0" -> ME;
			case "1" -> REMOTE;
			default -> null;
		};
	}
}
