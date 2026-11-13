package dev.gravaai.services.recording.cosmic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.recording.MicGate;
import dev.gravaai.services.recording.PushToTalkKeys;
import dev.gravaai.services.recording.cosmic.CosmicRecording.AudioTrack;
import dev.gravaai.services.recording.cosmic.CosmicRecording.PauseState;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;
import dev.gravaai.services.shortcuts.GlobalShortcutBinding;

/**
 * The push-to-talk gate zeroes the mic's PCM chunks while muted, so the file keeps its length and
 * its offset↔clock alignment (the timeline never desyncs) and the track still counts as captured.
 */
class CosmicAudioTracksGateTest {

	/** One chunk's worth of audible signal (anything but zeros). */
	private static final byte[] PCM = new byte[8192];

	static {
		for (int i = 0; i < PCM.length; i++) {
			PCM[i] = (byte) (i % 251);
		}
	}

	@TempDir
	Path recordings;

	@Test
	@DisplayName("muted chunks are written as silence, live chunks untouched")
	void mutedChunksAreZeroed() throws Exception {
		byte[] muted = record(true);
		assertTrue(CosmicAudioTracks.hasAudioData(recordings.resolve(".recording-1.mic.pcm")),
				"the muted track still counts as captured");
		for (int i = 0; i < muted.length; i++) {
			assertEquals(0, muted[i], "muted audio is silence (byte " + i + ")");
		}

		byte[] live = record(false);
		for (int i = 0; i < live.length; i++) {
			assertEquals(PCM[i], live[i], "the live mic is written untouched (byte " + i + ")");
		}
	}

	/** Runs one mic-only capture and returns what the track file holds. */
	private byte[] record(boolean muted) throws Exception {
		RecordingProcesses processes = (command, options) -> new PipeProcess(new ByteArrayInputStream(PCM));
		RecordingEnvironment environment = new RecordingEnvironment() {
			@Override
			public Path captureHelper() {
				return Path.of("/native/gravaai-wayland-capture");
			}

			@Override
			public Path cursorHelper() {
				return Path.of("/native/gravaai-wayland-cursor");
			}

			@Override
			public Path ffmpeg() {
				return Path.of("/native/ffmpeg");
			}

			@Override
			public Path rnnoiseModel() {
				return null;
			}

			@Override
			public Path command(String name) throws IOException {
				return Path.of("/usr/bin/" + name);
			}
		};
		CosmicProcessIo processIo = new CosmicProcessIo(processes);
		CosmicAudioTracks tracks = new CosmicAudioTracks(
				processes, environment, new PulseAudioSources(environment, processIo), processIo);

		MicGate gate = new MicGate(() -> 0L);
		gate.configure(PushToTalkKeys.fromBinding(
				new GlobalShortcutBinding("v", true, false, true, false)).orElseThrow());
		assertTrue(gate.isMuted());
		if (!muted) {
			// Live the way the shortcut does: Super+Alt+V held down (no release yet).
			gate.onKeyLine("KEY:down:125");
			gate.onKeyLine("KEY:down:56");
			gate.onKeyLine("KEY:down:47");
		}
		if (muted) {
			assertTrue(gate.isMuted(), "the gate starts a push-to-talk recording muted");
		} else {
			assertFalse(gate.isMuted(), "holding the combo unmutes the mic");
		}

		long timestamp = 1;
		List<AudioTrack> audio = tracks.start(recordings, timestamp,
				new CosmicAudioTracks.Options(false, true, null), PauseState.running(),
				gate.isActive() ? gate : null);
		assertEquals(1, audio.size());
		Path file = recordings.resolve(".recording-" + timestamp + ".mic.pcm");
		// The drain thread finishes once the stream ends; poll for the full size.
		for (int waited = 0; waited < 200 && Files.size(file) < PCM.length; waited++) {
			Thread.sleep(5);
		}
		assertEquals(PCM.length, Files.size(file), "every chunk is written, muted or not");
		byte[] written = Files.readAllBytes(file);
		tracks.killAll(audio);
		return written;
	}

	/** A process whose stdout is the given stream and that is dead from the start. */
	private static final class PipeProcess implements SpawnedProcess {

		private final InputStream stdout;

		PipeProcess(InputStream stdout) {
			this.stdout = stdout;
		}

		@Override
		public @Nullable OutputStream stdin() {
			return null;
		}

		@Override
		public @Nullable InputStream stdout() {
			return stdout;
		}

		@Override
		public @Nullable InputStream stderr() {
			return null;
		}

		@Override
		public long pid() {
			return 1;
		}

		@Override
		public boolean isAlive() {
			return false;
		}

		@Override
		public int waitFor() {
			return 0;
		}

		@Override
		public void destroy() {
		}

		@Override
		public void destroyForcibly() {
		}
	}
}
