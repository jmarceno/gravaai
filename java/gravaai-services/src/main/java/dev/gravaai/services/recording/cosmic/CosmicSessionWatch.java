package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.services.recording.CosmicSession;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioTrack;
import dev.gravaai.services.recording.CosmicSession.CosmicSessionManifest;
import dev.gravaai.services.recording.RecordingEvents;
import dev.gravaai.services.recording.cosmic.CosmicRecording.AudioTrack;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * The session manifest kept next to a recording's temp files, so a crash leaves enough metadata
 * behind for recovery, plus the two watchdogs that can end a recording from the outside.
 *
 * <p>Both watchdogs ask for a <em>normal</em> stop through {@link RecordingEvents}: the file is
 * saved instead of being left half-written in the temp files.
 */
final class CosmicSessionWatch implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** Answers whether a recording is still the active one. */
	interface ActiveCheck {
		boolean isActive(CosmicRecording recording);
	}

	private final RecordingEvents events;
	private final CosmicDiskGuard diskGuard;

	private @Nullable ScheduledFuture<?> diskCheck;

	CosmicSessionWatch(RecordingEvents events, CosmicDiskGuard diskGuard) {
		this.events = events;
		this.diskGuard = diskGuard;
	}

	/**
	 * Writes the manifest and starts the disk watchdog.
	 *
	 * @param recording the recording to publish
	 * @param recordingsDir the folder the temp files live in
	 * @param isActive tells the watchdog whether this recording is still the active one
	 */
	void begin(CosmicRecording recording, Path recordingsDir, ActiveCheck isActive) {
		// A track that had no sound yet updates the manifest on its first chunk.
		for (AudioTrack track : recording.audio()) {
			if (!track.awaitingFirstData()) {
				continue;
			}
			track.firstData().thenRun(() -> {
				if (isActive.isActive(recording) && !track.awaitingFirstData()) {
					save(recording);
				}
			});
		}
		save(recording);
		diskCheck = diskGuard.watch(recordingsDir, free -> {
			if (!isActive.isActive(recording)) {
				return;
			}
			stopDiskCheck();
			LOG.log(System.Logger.Level.WARNING,
					"Only " + free + " bytes free in " + recordingsDir + "; stopping");
			events.emitRecordingStopRequested(
					"low-disk", "Disk almost full — the recording was stopped and saved.");
		});
	}

	/**
	 * Asks for a normal stop when {@code capture} exits while the recording is still active. That
	 * happens when the recording is ended outside the app: the desktop's own screen-sharing
	 * indicator (KDE's "End", the GNOME top-bar button) or the captured monitor going away.
	 * Without this the app kept "recording" a pipe that no longer delivered frames.
	 */
	void watchCaptureEnd(CosmicRecording recording, SpawnedProcess capture, ActiveCheck isActive) {
		Threads.startVirtual("gravaai-capture-watch", () -> {
			try {
				capture.waitFor();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
			// The app stopped or discarded the recording itself.
			if (!isActive.isActive(recording)) {
				return;
			}
			LOG.log(System.Logger.Level.WARNING, "The screen capture ended outside the app; stopping");
			events.emitRecordingStopRequested(
					"capture-ended", "The screen share was ended — the recording was stopped and saved.");
		});
	}

	/** Stops the disk watchdog for this recording. */
	void stopDiskCheck() {
		if (diskCheck != null) {
			diskCheck.cancel(false);
			diskCheck = null;
		}
	}

	/** Writes the manifest; writes are serialized so two never share the temp file. */
	synchronized void save(CosmicRecording recording) {
		try {
			CosmicSession.writeSessionManifest(recording.manifestPath(), buildManifest(recording));
		} catch (IOException error) {
			// A recording without a manifest still works, it just cannot be recovered.
			LOG.log(System.Logger.Level.WARNING, "Could not write the recording manifest: " + error);
		}
	}

	private static CosmicSessionManifest buildManifest(CosmicRecording recording) {
		List<CosmicAudioTrack> audio = new ArrayList<>();
		for (AudioTrack track : recording.audio()) {
			audio.add(new CosmicAudioTrack(
					track.role(),
					track.path().toString(),
					track.awaitingFirstData() ? null : trackedStart(track)));
		}
		CosmicRecording.VideoCapture video = recording.video();
		return new CosmicSessionManifest(
				1,
				recording.timestamp(),
				ProcessHandle.current().pid(),
				Instant.ofEpochMilli(recording.timestamp()).toString(),
				recording.outputPath().toString(),
				video == null ? null : video.videoPath().toString(),
				recording.startedAtMs(),
				recording.keepSpeakerTracks(),
				recording.micNoiseReduction(),
				List.copyOf(audio));
	}

	/** The track's start as a nullable manifest value. */
	private static @Nullable Long trackedStart(AudioTrack track) {
		return track.awaitingFirstData() ? null : Long.valueOf(track.startedAtMs());
	}

	@Override
	public void close() {
		stopDiskCheck();
	}
}
