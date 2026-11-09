package dev.gravaai.services.recording.cosmic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.cosmic.CosmicRecording.CaptureFormat;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;

/**
 * Guards the recording-length contract.
 *
 * <p>The encoder used to be told {@code -r 60}, so every frame was stamped as if it had arrived on
 * time. When encoding could not keep up (1080p x264 on a busy machine reaches well under 60 fps),
 * the stream fell behind wall clock but the timestamps did not: a 15 s recording came out as a
 * ~2.5 s clip that played several times too fast. Frames must carry their arrival time instead.
 */
class RecordingTimestampContractTest {

	private static CosmicVideoPipeline pipeline() {
		return new CosmicVideoPipeline(
				(command, options) -> {
					throw new UnsupportedOperationException("no processes in this test");
				},
				new PlatformRecordingEnvironment(),
				new CosmicProcessIo((command, options) -> {
					throw new UnsupportedOperationException("no processes in this test");
				}));
	}

	private static List<String> encoderArguments() {
		return pipeline().encoderCommand(
				new CaptureFormat(1920, 1080, "bgra", 0), "format=yuv420p",
				Path.of("/tmp/out.mkv"));
	}

	@Test
	@DisplayName("frames are timestamped by arrival, not by a fixed assumed rate")
	void usesWallclockTimestamps() {
		List<String> arguments = encoderArguments();
		int index = arguments.indexOf("-use_wallclock_as_timestamps");
		assertTrue(index >= 0, "the encoder must timestamp frames by arrival: " + arguments);
		assertEquals("1", arguments.get(index + 1), arguments.toString());
	}

	@Test
	@DisplayName("the input has no fixed rate, which is what shortened recordings")
	void hasNoFixedInputRate() {
		List<String> arguments = encoderArguments();
		int input = arguments.indexOf("-i");
		assertTrue(input > 0, arguments.toString());
		// Any "-r" before the input stamps frames at a fixed rate again and re-introduces the bug.
		assertFalse(arguments.subList(0, input).contains("-r"),
				"a fixed -r before the input shortens recordings: " + arguments);
	}

	@Test
	@DisplayName("output is variable frame rate so slow stretches keep their real duration")
	void outputsVariableFrameRate() {
		List<String> arguments = encoderArguments();
		int index = arguments.indexOf("-fps_mode");
		assertTrue(index >= 0, "want -fps_mode vfr: " + arguments);
		assertEquals("vfr", arguments.get(index + 1), arguments.toString());
	}
}
