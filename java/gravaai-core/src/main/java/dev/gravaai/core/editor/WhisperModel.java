package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Whisper model ids the editor can select (port of {@code WHISPER_MODELS} in editorPreferences.ts). */
public enum WhisperModel {
	SMALL("small", "Whisper small", 487_601_967L),
	LARGE_V3_TURBO_Q5_0("large-v3-turbo-q5_0", "Whisper large-v3-turbo", 574_041_195L);

	private final String wire;
	private final String label;
	private final long sizeBytes;

	WhisperModel(String wire, String label, long sizeBytes) {
		this.wire = wire;
		this.label = label;
		this.sizeBytes = sizeBytes;
	}

	@JsonValue
	public String wire() {
		return wire;
	}

	public String label() {
		return label;
	}

	public long sizeBytes() {
		return sizeBytes;
	}

	/** Null for unknown or missing ids, like the TS {@code in WHISPER_MODELS} check. */
	@JsonCreator
	public static @Nullable WhisperModel fromWire(String value) {
		for (WhisperModel model : values()) {
			if (model.wire.equals(value)) {
				return model;
			}
		}
		return null;
	}
}
