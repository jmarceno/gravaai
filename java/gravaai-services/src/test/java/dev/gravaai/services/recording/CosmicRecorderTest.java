package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.CosmicRecorder.TrackRef;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioRole;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;

/** Ported from {@code electron/ipc/recording/cosmic.test.ts}; the recorder's pure pipeline helpers. */
class CosmicRecorderTest {

	private static final List<String> DENOISE =
			List.of("highpass=f=80", "afftdn=nr=10:nf=-50:tn=1");

	@Test
	@DisplayName("delays late tracks and trims early ones")
	void delaysLateTracksAndTrimsEarlyOnes() {
		assertEquals("adelay=12:all=1", CosmicRecorder.alignTrackFilter(1012, 1000));
		assertEquals("atrim=start=0.010,asetpts=PTS-STARTPTS", CosmicRecorder.alignTrackFilter(990, 1000));
		assertEquals("atrim=start=0.000,asetpts=PTS-STARTPTS", CosmicRecorder.alignTrackFilter(1000, 1000));
	}

	// Output from before the filter was extracted from stopCosmicRecording().
	@Test
	@DisplayName("matches the previous output with no mic filters")
	void matchesThePreviousOutputWithNoMicFilters() {
		assertEquals(
				"[1:a]adelay=12:all=1[a]",
				CosmicRecorder.buildCosmicAudioMixFilter(
						List.of(new TrackRef(CosmicAudioRole.MIC, 1012)), 1000, 1, List.of()));
		assertEquals(
				"[1:a]adelay=12:all=1[a0];[2:a]atrim=start=0.010,asetpts=PTS-STARTPTS[a1];[a0][a1]amix=inputs=2:duration=longest:normalize=0[a]",
				CosmicRecorder.buildCosmicAudioMixFilter(
						List.of(
								new TrackRef(CosmicAudioRole.MIC, 1012),
								new TrackRef(CosmicAudioRole.SYSTEM, 990)),
						1000,
						1,
						List.of()));
		assertEquals(
				"[0:a]atrim=start=0.000,asetpts=PTS-STARTPTS[a]",
				CosmicRecorder.buildCosmicAudioMixFilter(
						List.of(new TrackRef(CosmicAudioRole.SYSTEM, 1000)), 1000, 0, List.of()));
	}

	@Test
	@DisplayName("denoises only the mic track, after alignment")
	void denoisesOnlyTheMicTrackAfterAlignment() {
		assertEquals(
				"[0:a]atrim=start=0.010,asetpts=PTS-STARTPTS[a0];[1:a]adelay=12:all=1,highpass=f=80,afftdn=nr=10:nf=-50:tn=1[a1];[a0][a1]amix=inputs=2:duration=longest:normalize=0[a]",
				CosmicRecorder.buildCosmicAudioMixFilter(
						List.of(
								new TrackRef(CosmicAudioRole.SYSTEM, 990),
								new TrackRef(CosmicAudioRole.MIC, 1012)),
						1000,
						0,
						DENOISE));
		assertEquals(
				"[1:a]adelay=12:all=1,highpass=f=80,afftdn=nr=10:nf=-50:tn=1[a]",
				CosmicRecorder.buildCosmicAudioMixFilter(
						List.of(new TrackRef(CosmicAudioRole.MIC, 1012)), 1000, 1, DENOISE));
	}

	@Test
	@DisplayName("leaves the system speaker track unfiltered")
	void leavesTheSystemSpeakerTrackUnfiltered() {
		assertEquals(
				List.of("adelay=12:all=1"),
				CosmicRecorder.trackFilterChain(new TrackRef(CosmicAudioRole.SYSTEM, 1012), 1000, DENOISE));
		assertEquals(
				List.of("adelay=12:all=1", "highpass=f=80", "afftdn=nr=10:nf=-50:tn=1"),
				CosmicRecorder.trackFilterChain(new TrackRef(CosmicAudioRole.MIC, 1012), 1000, DENOISE));
	}

	@Test
	@DisplayName("keeps the speaker track next to the recording")
	void keepsTheSpeakerTrackNextToTheRecording() {
		assertEquals(
				"/tmp/Gravaai/recordings/recording-1720588800000.meeting-mic.opus",
				CosmicRecorder.speakerTrackPath(
						"/tmp/Gravaai/recordings/recording-1720588800000.mp4", CosmicAudioRole.MIC));
		assertEquals(
				"/tmp/Gravaai/recordings/recording-1720588800000.meeting-system.opus",
				CosmicRecorder.speakerTrackPath(
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
				CosmicRecorder.pickPulseMicrophone(sources, "USB Microphone Analog Stereo"));
		assertEquals(
				"alsa_input.usb-Mic-00.analog-stereo",
				CosmicRecorder.pickPulseMicrophone(sources, "USB Microphone"));
		assertNull(CosmicRecorder.pickPulseMicrophone(sources, "No such device"));
		assertNull(CosmicRecorder.pickPulseMicrophone(null, "USB Microphone"));
	}

	@Test
	@DisplayName("resolves the free space of the recordings folder")
	void resolvesFreeSpace(@TempDir Path dir) {
		Long free = CosmicRecorder.getFreeBytes(dir);
		org.junit.jupiter.api.Assertions.assertNotNull(free);
		org.junit.jupiter.api.Assertions.assertTrue(free > 0);
	}
}
