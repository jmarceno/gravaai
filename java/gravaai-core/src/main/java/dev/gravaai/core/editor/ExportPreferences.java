package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Export preferences shared by the editor settings and the export dialog (port of
 * {@code src/components/video-editor/exportPreferences.ts}, plan §5.11).
 */
public record ExportPreferences(
		ExportEncodingMode exportEncodingMode,
		ExportBackendPreference exportBackendPreference,
		ExportPipelineModel exportPipelineModel,
		ExportQuality exportQuality,
		int mp4FrameRate,
		ExportFormat exportFormat,
		int gifFrameRate,
		boolean gifLoop,
		GifSizePreset gifSizePreset,
		PublishDestination publishDestination,
		boolean includeCaptionSidecar) {

	public enum ExportEncodingMode {
		FAST("fast"), BALANCED("balanced"), QUALITY("quality");

		private final String wire;

		ExportEncodingMode(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		/** Wire parser; {@code null} (field absent) selects the default. */
		@JsonCreator
		public static ExportEncodingMode fromWire(@Nullable String value) {
			for (ExportEncodingMode mode : values()) {
				if (mode.wire.equals(value)) {
					return mode;
				}
			}
			return BALANCED;
		}
	}

	public enum ExportBackendPreference {
		AUTO("auto"), WEBCODECS("webcodecs"), BREEZE("breeze");

		private final String wire;

		ExportBackendPreference(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		/** Wire parser; {@code null} (field absent) selects the default. */
		@JsonCreator
		public static ExportBackendPreference fromWire(@Nullable String value) {
			for (ExportBackendPreference preference : values()) {
				if (preference.wire.equals(value)) {
					return preference;
				}
			}
			return AUTO;
		}
	}

	public enum ExportPipelineModel {
		MODERN("modern"), LEGACY("legacy");

		private final String wire;

		ExportPipelineModel(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		@JsonCreator
		public static ExportPipelineModel fromWire(@Nullable String value) {
			return MODERN;
		}
	}

	public enum ExportQuality {
		MEDIUM("medium"), GOOD("good"), HIGH("high"), SOURCE("source");

		private final String wire;

		ExportQuality(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		/** Wire parser; {@code null} (field absent) selects the default. */
		@JsonCreator
		public static ExportQuality fromWire(@Nullable String value) {
			for (ExportQuality quality : values()) {
				if (quality.wire.equals(value)) {
					return quality;
				}
			}
			return SOURCE;
		}
	}

	public enum ExportFormat {
		MP4("mp4"), GIF("gif");

		private final String wire;

		ExportFormat(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		/** Wire parser; {@code null} (field absent) selects MP4. */
		@JsonCreator
		public static ExportFormat fromWire(@Nullable String value) {
			return "gif".equals(value) ? GIF : MP4;
		}
	}

	public enum GifSizePreset {
		MEDIUM("medium"), LARGE("large"), ORIGINAL("original");

		private final String wire;

		GifSizePreset(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		/** Wire parser; {@code null} (field absent) selects the default. */
		@JsonCreator
		public static GifSizePreset fromWire(@Nullable String value) {
			for (GifSizePreset preset : values()) {
				if (preset.wire.equals(value)) {
					return preset;
				}
			}
			return MEDIUM;
		}
	}

	public enum PublishDestination {
		LINK("link"), LOCAL("local");

		private final String wire;

		PublishDestination(String wire) {
			this.wire = wire;
		}

		@JsonValue
		public String wire() {
			return wire;
		}

		/** Wire parser; {@code null} (field absent) selects LINK. */
		@JsonCreator
		public static PublishDestination fromWire(@Nullable String value) {
			return "local".equals(value) ? LOCAL : LINK;
		}
	}

	/** {@code MP4_FRAME_RATES}. */
	public static final int[] MP4_FRAME_RATES = {24, 30, 60};
	/** {@code GIF_FRAME_RATES}. */
	public static final int[] GIF_FRAME_RATES = {15, 20, 25, 30};

	/** {@code normalizeExportEncodingMode}; {@code null} when the stored field is missing. */
	public static ExportEncodingMode normalizeExportEncodingMode(@Nullable JsonNode value) {
		return ExportEncodingMode.fromWire(value != null && value.isString() ? value.asString() : null);
	}

	/** {@code normalizeExportBackendPreference}; {@code null} when the stored field is missing. */
	public static ExportBackendPreference normalizeExportBackendPreference(@Nullable JsonNode value) {
		return ExportBackendPreference.fromWire(
				value != null && value.isString() ? value.asString() : null);
	}

	/**
	 * {@code normalizeExportPipelineModel}: persisted selections migrate to the modern pipeline;
	 * {@code value} is ignored (and may be null when the field is absent).
	 */
	public static ExportPipelineModel normalizeExportPipelineModel(@Nullable JsonNode value) {
		return ExportPipelineModel.MODERN;
	}

	/** {@code normalizeExportMp4FrameRate}; {@code null} when the stored field is missing. */
	public static int normalizeExportMp4FrameRate(@Nullable JsonNode value) {
		if (value == null || !value.isNumber()) {
			return 30;
		}
		int rate = value.intValue();
		for (int allowed : MP4_FRAME_RATES) {
			if (rate == allowed) {
				return rate;
			}
		}
		return 30;
	}

	/** GIF frame-rate normaliser; {@code null} when the stored field is missing. */
	private static int normalizeGifFrameRate(@Nullable JsonNode value) {
		if (value == null || !value.isNumber()) {
			return 15;
		}
		int rate = value.intValue();
		for (int allowed : GIF_FRAME_RATES) {
			if (rate == allowed) {
				return rate;
			}
		}
		return 15;
	}

	/** {@code normalizeExportPreferences}; {@code null} means "no stored preferences object". */
	public static ExportPreferences normalizeExportPreferences(@Nullable JsonNode raw) {
		JsonNode source = raw != null && raw.isObject() ? raw : Json.nodeFactory().objectNode();
		JsonNode quality = source.get("exportQuality");
		String qualityText = quality != null && quality.isString() ? quality.asString() : null;
		return new ExportPreferences(
				normalizeExportEncodingMode(source.get("exportEncodingMode")),
				normalizeExportBackendPreference(source.get("exportBackendPreference")),
				normalizeExportPipelineModel(source.get("exportPipelineModel")),
				ExportQuality.fromWire(qualityText),
				normalizeExportMp4FrameRate(source.get("mp4FrameRate")),
				ExportFormat.fromWire(source.has("exportFormat") && source.get("exportFormat").isString()
						? source.get("exportFormat").asString() : null),
				normalizeGifFrameRate(source.get("gifFrameRate")),
				booleanOr(source.get("gifLoop"), true),
				GifSizePreset.fromWire(source.has("gifSizePreset") && source.get("gifSizePreset").isString()
						? source.get("gifSizePreset").asString() : null),
				PublishDestination.fromWire(source.has("publishDestination")
						&& source.get("publishDestination").isString()
								? source.get("publishDestination").asString() : null),
				booleanOr(source.get("includeCaptionSidecar"), false));
	}

	private static boolean booleanOr(JsonNode value, boolean fallback) {
		return value != null && value.isBoolean() ? value.booleanValue() : fallback;
	}

	/** The TS defaults of {@code normalizeExportPreferences({})}. */
	public static ExportPreferences defaults() {
		return normalizeExportPreferences(Json.nodeFactory().objectNode());
	}

	/** Writes the preferences with the same JSON names as the TS object. */
	public ObjectNode toJson() {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("exportEncodingMode", exportEncodingMode.wire());
		node.put("exportBackendPreference", exportBackendPreference.wire());
		node.put("exportPipelineModel", exportPipelineModel.wire());
		node.put("exportQuality", exportQuality.wire());
		node.put("mp4FrameRate", mp4FrameRate);
		node.put("exportFormat", exportFormat.wire());
		node.put("gifFrameRate", gifFrameRate);
		node.put("gifLoop", gifLoop);
		node.put("gifSizePreset", gifSizePreset.wire());
		node.put("publishDestination", publishDestination.wire());
		node.put("includeCaptionSidecar", includeCaptionSidecar);
		return node;
	}
}
