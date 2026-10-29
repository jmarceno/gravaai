package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;

/**
 * Resolved MP4 export settings (port of {@code src/components/video-editor/mp4ExportSettings.ts},
 * plan §5.10). The smoke-export and dialog override shapes are nested here so the file name matches
 * the one public type.
 */
public record Mp4ExportSettings(ExportQuality quality, ExportEncodingMode encodingMode,
		int selectedMp4FrameRate) {

	/** Smoke-export overrides ({@code SmokeExportConfig} fields used by export settings). */
	public record SmokeExportConfig(boolean enabled, ExportQuality quality,
			ExportEncodingMode encodingMode, @Nullable Integer fps) {}

	/** Export-dialog overrides (the used {@code ExportSettings} fields). */
	public record ExportSettingsOverrides(@Nullable ExportQuality quality, @Nullable ExportEncodingMode encodingMode,
			@Nullable Integer mp4FrameRate) {}

	/** {@code resolveMp4ExportSettings}. */
	public static Mp4ExportSettings resolve(SmokeExportConfig smokeExportConfig,
			ExportSettingsOverrides settings, ExportQuality exportQuality,
			ExportEncodingMode exportEncodingMode, int mp4FrameRate) {
		ExportSettingsOverrides resolvedSettings = settings == null
				? new ExportSettingsOverrides(null, null, null)
				: settings;
		if (smokeExportConfig != null && smokeExportConfig.enabled()) {
			return new Mp4ExportSettings(
					smokeExportConfig.quality() != null
							? smokeExportConfig.quality()
							: resolvedSettings.quality() != null
									? resolvedSettings.quality()
									: exportQuality,
					smokeExportConfig.encodingMode() != null
							? smokeExportConfig.encodingMode()
							: resolvedSettings.encodingMode() != null
									? resolvedSettings.encodingMode()
									: exportEncodingMode,
					smokeExportConfig.fps() != null
							? smokeExportConfig.fps()
							: resolvedSettings.mp4FrameRate() != null
									? resolvedSettings.mp4FrameRate()
									: mp4FrameRate);
		}
		return new Mp4ExportSettings(
				resolvedSettings.quality() != null ? resolvedSettings.quality() : exportQuality,
				resolvedSettings.encodingMode() != null
						? resolvedSettings.encodingMode()
						: exportEncodingMode,
				resolvedSettings.mp4FrameRate() != null
						? resolvedSettings.mp4FrameRate()
						: mp4FrameRate);
	}
}
