package dev.gravaai.services.recording.cosmic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioRole;
import dev.gravaai.services.recording.cosmic.CosmicMuxer.TrackRef;
import dev.gravaai.services.recording.cosmic.CosmicRecording.CaptureFormat;

/** Ported from {@code electron/ipc/recording/cosmic.test.ts}; the recorder's pure pipeline helpers. */
class CosmicRecorderTest {

	private static final List<String> DENOISE =
			List.of("highpass=f=80", "afftdn=nr=10:nf=-50:tn=1");

	@Test
	@DisplayName("delays late tracks and trims early ones")
	void delaysLateTracksAndTrimsEarlyOnes() {
		assertEquals("adelay=12:all=1", CosmicMuxer.alignTrackFilter(1012, 1000));
		assertEquals("atrim=start=0.010,asetpts=PTS-STARTPTS", CosmicMuxer.alignTrackFilter(990, 1000));
		assertEquals("atrim=start=0.000,asetpts=PTS-STARTPTS", CosmicMuxer.alignTrackFilter(1000, 1000));
	}

	// Output from before the filter was extracted from stopCosmicRecording().
	@Test
	@DisplayName("matches the previous output with no mic filters")
	void matchesThePreviousOutputWithNoMicFilters() {
		assertEquals(
				"[1:a]adelay=12:all=1[a]",
				CosmicMuxer.buildMixFilter(
						List.of(new TrackRef(CosmicAudioRole.MIC, 1012)), 1000, 1, List.of()));
		assertEquals(
				"[1:a]adelay=12:all=1[a0];[2:a]atrim=start=0.010,asetpts=PTS-STARTPTS[a1];[a0][a1]amix=inputs=2:duration=longest:normalize=0[a]",
				CosmicMuxer.buildMixFilter(
						List.of(
								new TrackRef(CosmicAudioRole.MIC, 1012),
								new TrackRef(CosmicAudioRole.SYSTEM, 990)),
						1000,
						1,
						List.of()));
		assertEquals(
				"[0:a]atrim=start=0.000,asetpts=PTS-STARTPTS[a]",
				CosmicMuxer.buildMixFilter(
						List.of(new TrackRef(CosmicAudioRole.SYSTEM, 1000)), 1000, 0, List.of()));
	}

	@Test
	@DisplayName("denoises only the mic track, after alignment")
	void denoisesOnlyTheMicTrackAfterAlignment() {
		assertEquals(
				"[0:a]atrim=start=0.010,asetpts=PTS-STARTPTS[a0];[1:a]adelay=12:all=1,highpass=f=80,afftdn=nr=10:nf=-50:tn=1[a1];[a0][a1]amix=inputs=2:duration=longest:normalize=0[a]",
				CosmicMuxer.buildMixFilter(
						List.of(
								new TrackRef(CosmicAudioRole.SYSTEM, 990),
								new TrackRef(CosmicAudioRole.MIC, 1012)),
						1000,
						0,
						DENOISE));
		assertEquals(
				"[1:a]adelay=12:all=1,highpass=f=80,afftdn=nr=10:nf=-50:tn=1[a]",
				CosmicMuxer.buildMixFilter(
						List.of(new TrackRef(CosmicAudioRole.MIC, 1012)), 1000, 1, DENOISE));
	}

	@Test
	@DisplayName("leaves the system speaker track unfiltered")
	void leavesTheSystemSpeakerTrackUnfiltered() {
		assertEquals(
				List.of("adelay=12:all=1"),
				CosmicMuxer.trackFilterChain(new TrackRef(CosmicAudioRole.SYSTEM, 1012), 1000, DENOISE));
		assertEquals(
				List.of("adelay=12:all=1", "highpass=f=80", "afftdn=nr=10:nf=-50:tn=1"),
				CosmicMuxer.trackFilterChain(new TrackRef(CosmicAudioRole.MIC, 1012), 1000, DENOISE));
	}

	@Test
	@DisplayName("keeps the speaker track next to the recording")
	void keepsTheSpeakerTrackNextToTheRecording() {
		assertEquals(
				"/tmp/Gravaai/recordings/recording-1720588800000.meeting-mic.opus",
				CosmicMuxer.speakerTrackPath(
						"/tmp/Gravaai/recordings/recording-1720588800000.mp4", CosmicAudioRole.MIC));
		assertEquals(
				"/tmp/Gravaai/recordings/recording-1720588800000.meeting-system.opus",
				CosmicMuxer.speakerTrackPath(
						"/tmp/Gravaai/recordings/recording-1720588800000.m4a", CosmicAudioRole.SYSTEM));
	}

	@Test
	@DisplayName("matches a PulseAudio source description to its device name")
	void matchesAPulseAudioSourceDescription() {
		var sources = Json.parseJsonWithByteOrderMark("""
				[
				  {"name": "alsa_input.usb-Mic-00.analog-stereo", "description": "USB Microphone Analog Stereo"},
				  {"name": "alsa_input.pci-0000_00_1f.3.analog-stereo", "description": "Built-in Audio Analog Stereo"}
				]
				""");
		assertEquals(
				"alsa_input.usb-Mic-00.analog-stereo",
				PulseAudioSources.pickMicrophone(sources, "USB Microphone Analog Stereo"));
		assertEquals(
				"alsa_input.usb-Mic-00.analog-stereo",
				PulseAudioSources.pickMicrophone(sources, "USB Microphone"));
		assertNull(PulseAudioSources.pickMicrophone(sources, "No such device"));
		assertNull(PulseAudioSources.pickMicrophone(null, "USB Microphone"));
	}

	@Test
	@DisplayName("resolves the free space of the recordings folder")
	void resolvesFreeSpace(@TempDir Path dir) {
		Long free = CosmicDiskGuard.freeBytes(dir);
		assertNotNull(free);
		assertTrue(free > 0);
	}

	@Test
	@DisplayName("maps the helper's transform code to a transpose filter")
	void mapsTransformToTransposeFilter() {
		assertNull(CosmicVideoPipeline.transposeFilter(0));
		assertEquals("transpose=1", CosmicVideoPipeline.transposeFilter(1));
		assertEquals("hflip,vflip", CosmicVideoPipeline.transposeFilter(2));
		assertEquals("transpose=2", CosmicVideoPipeline.transposeFilter(3));
		assertEquals("hflip", CosmicVideoPipeline.transposeFilter(4));
		assertEquals("hflip,transpose=1", CosmicVideoPipeline.transposeFilter(5));
	}

	@Test
	@DisplayName("accepts a frame size the capture helper reported")
	void parsesACaptureFormat() {
		CaptureFormat format = new CaptureFormat(1920, 1080, "bgra", 0);
		assertEquals(1920, format.width());
		assertEquals(1080, format.height());
	}
}
