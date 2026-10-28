package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from {@code electron/ipc/recording/audioFilters.test.ts}. */
class AudioFiltersTest {

	@Test
	@DisplayName("keeps repair filters without automatic gain or loudness normalization")
	void keepsRepairFiltersWithoutAutomaticGain() {
		assertTrue(AudioFilters.WINDOWS_NATIVE_MIC_PRE_FILTERS.contains("adeclip=threshold=1"));
		assertFalse(AudioFilters.WINDOWS_NATIVE_MIC_PRE_FILTERS.stream()
				.anyMatch(filter -> filter.matches("(?i).*(^|,)(loudnorm|dynaudnorm|volume)=.*")));
	}

	@Test
	@DisplayName("keeps native audio sidecars only when explicitly requested")
	void keepsNativeAudioSidecarsOnlyWhenRequested() {
		assertFalse(AudioFilters.shouldKeepRecordingAudioSidecars(Map.of()));
		assertTrue(AudioFilters.shouldKeepRecordingAudioSidecars(
				Map.of(AudioFilters.RECORDING_AUDIO_SIDECAR_DEBUG_ENV, "1")));
		assertTrue(AudioFilters.shouldKeepRecordingAudioSidecars(
				Map.of(AudioFilters.RECORDING_AUDIO_SIDECAR_DEBUG_ENV, "true")));
		assertFalse(AudioFilters.shouldKeepRecordingAudioSidecars(
				Map.of(AudioFilters.RECORDING_AUDIO_SIDECAR_DEBUG_ENV, "off")));
	}

	@Test
	@DisplayName("keeps the browser fallback chain conservative and speech-oriented")
	void keepsTheBrowserFallbackChainConservative() {
		assertEquals(
				List.of(
						"adeclip=threshold=1",
						"adeclick=w=40:o=75:t=3:b=2",
						"highpass=f=85",
						"lowpass=f=9500",
						"afftdn=nr=10:nf=-45:tn=1",
						"alimiter=limit=0.92:level=0"),
				AudioFilters.BROWSER_MIC_SIDECAR_FILTERS);
		assertFalse(AudioFilters.BROWSER_MIC_SIDECAR_FILTERS.stream()
				.anyMatch(filter -> filter.matches("(?i).*(^|,)(loudnorm|dynaudnorm|volume)=.*")));
	}

	@Test
	@DisplayName("adds bounded speech gain only for the no-AGC browser mic profile")
	void addsBoundedSpeechGainOnlyForNoAgcProfile() {
		assertEquals(
				AudioFilters.BROWSER_MIC_SIDECAR_FILTERS,
				AudioFilters.getBrowserMicSidecarFilters("processed"));
		assertEquals(
				List.of(
						"adeclip=threshold=1",
						"adeclick=w=40:o=75:t=3:b=2",
						"highpass=f=85",
						"lowpass=f=9500",
						"afftdn=nr=10:nf=-45:tn=1",
						"speechnorm=p=0.92:e=12:c=2:r=0.0005:f=0.001",
						"alimiter=limit=0.92:level=0"),
				AudioFilters.getBrowserMicSidecarFilters("no-agc"));
		assertTrue(AudioFilters.BROWSER_MIC_SIDECAR_NO_AGC_GAIN_FILTERS.contains(
				"speechnorm=p=0.92:e=12:c=2:r=0.0005:f=0.001"));
		assertTrue(AudioFilters.BROWSER_MIC_SIDECAR_NO_AGC_GAIN_FILTERS.contains(
				"alimiter=limit=0.92:level=0"));
	}
}
