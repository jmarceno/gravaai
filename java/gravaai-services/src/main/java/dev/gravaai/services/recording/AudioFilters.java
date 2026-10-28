package dev.gravaai.services.recording;

import java.util.List;
import java.util.Map;

/**
 * Recording audio-filter policy, ported from {@code electron/ipc/recording/audioFilters.ts}.
 */
public final class AudioFilters {

	// Keep native mic capture dry by default. Automatic loudness normalization
	// amplified wireless-headset noise and WASAPI discontinuities during beta tests.
	public static final List<String> WINDOWS_NATIVE_MIC_PRE_FILTERS = List.of("adeclip=threshold=1");

	// Browser mic fallback uses Chromium/WebRTC voice processing, but beta tests
	// showed its realtime AGC can introduce short crackle bursts on some Realtek
	// and headset paths. Default to no AGC, then restore usable level offline with
	// bounded speech expansion and a limiter.
	public static final List<String> BROWSER_MIC_SIDECAR_BASE_FILTERS = List.of(
			"adeclip=threshold=1",
			"adeclick=w=40:o=75:t=3:b=2",
			"highpass=f=85",
			"lowpass=f=9500",
			"afftdn=nr=10:nf=-45:tn=1");

	public static final List<String> BROWSER_MIC_SIDECAR_NO_AGC_GAIN_FILTERS = List.of(
			"speechnorm=p=0.92:e=12:c=2:r=0.0005:f=0.001",
			"alimiter=limit=0.92:level=0");

	public static final List<String> BROWSER_MIC_SIDECAR_FILTERS = List.of(
			"adeclip=threshold=1",
			"adeclick=w=40:o=75:t=3:b=2",
			"highpass=f=85",
			"lowpass=f=9500",
			"afftdn=nr=10:nf=-45:tn=1",
			"alimiter=limit=0.92:level=0");

	public static final String RECORDING_AUDIO_SIDECAR_DEBUG_ENV = "GRAVAAI_KEEP_RECORDING_AUDIO_SIDECARS";

	private AudioFilters() {
	}

	public static List<String> getBrowserMicSidecarFilters(String profile) {
		if ("no-agc".equals(profile)) {
			return java.util.stream.Stream
					.concat(BROWSER_MIC_SIDECAR_BASE_FILTERS.stream(),
							BROWSER_MIC_SIDECAR_NO_AGC_GAIN_FILTERS.stream())
					.toList();
		}
		return BROWSER_MIC_SIDECAR_FILTERS;
	}

	public static boolean shouldKeepRecordingAudioSidecars(Map<String, String> env) {
		String value = env.get(RECORDING_AUDIO_SIDECAR_DEBUG_ENV);
		value = value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
		return "1".equals(value) || "true".equals(value) || "yes".equals(value) || "on".equals(value);
	}
}
