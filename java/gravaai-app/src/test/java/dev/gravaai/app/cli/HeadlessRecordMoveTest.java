package dev.gravaai.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HeadlessRecord#moveRecordingBundle}: {@code --out} must move a take and everything that
 * belongs to it, renaming the sidecars consistently so the editor still finds them.
 *
 * <p>The recorder always writes {@code recording-<timestamp>.mp4}, so this move is how {@code --out}
 * is honoured. A sidecar left behind or renamed wrongly would silently lose the cursor telemetry
 * (no cursor in the export) or the audio captures (no untouched microphone).
 */
class HeadlessRecordMoveTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("moves the recording and its sidecars, renaming each to the destination stem")
	void movesWholeBundle() throws Exception {
		Path recordings = Files.createDirectory(tempDir.resolve("recordings"));
		Path saved = recordings.resolve("recording-1793004888668.mp4");
		Files.writeString(saved, "video");
		// The telemetry and session names use the file name; the audio captures and peaks use the
		// stem (RecordingAudioManifest.stemOf), exactly as the recorder writes them.
		for (String name : new String[] {
				"recording-1793004888668.mp4.cursor.json",
				"recording-1793004888668.mp4.keys.json",
				"recording-1793004888668.gravaai-session.json",
				"recording-1793004888668.audio-tracks.json",
				"recording-1793004888668.mic.wav",
				"recording-1793004888668.system.m4a"}) {
			Files.writeString(recordings.resolve(name), name);
		}
		// A neighbouring recording's sidecar must not move with this one.
		Path neighbour = recordings.resolve("recording-1793004888999.mp4.cursor.json");
		Files.writeString(neighbour, "other");

		Path destination = tempDir.resolve("out/demo.mp4");
		HeadlessRecord.moveRecordingBundle(saved, destination);

		assertTrue(Files.isRegularFile(destination));
		assertFalse(Files.exists(saved));
		for (String name : new String[] {
				"demo.mp4.cursor.json",
				"demo.mp4.keys.json",
				"demo.gravaai-session.json",
				"demo.audio-tracks.json",
				"demo.mic.wav",
				"demo.system.m4a"}) {
			assertTrue(Files.isRegularFile(destination.resolveSibling(name)),
					name + " should have moved");
		}
		assertTrue(Files.isRegularFile(neighbour), "a neighbouring recording's sidecar stays put");
	}

	@Test
	@DisplayName("a recording without sidecars moves alone")
	void movesBareRecording() throws Exception {
		Path saved = tempDir.resolve("recording-1.mp4");
		Files.writeString(saved, "video");
		Path destination = tempDir.resolve("bare.mp4");
		HeadlessRecord.moveRecordingBundle(saved, destination);
		assertTrue(Files.isRegularFile(destination));
		assertEquals("video", Files.readString(destination));
	}

	@Test
	@DisplayName("moving onto the recording's own path changes nothing")
	void samePathIsANoOp() throws Exception {
		Path saved = tempDir.resolve("recording-1.mp4");
		Files.writeString(saved, "video");
		HeadlessRecord.moveRecordingBundle(saved, saved);
		assertTrue(Files.isRegularFile(saved));
		assertEquals("video", Files.readString(saved));
	}

	@Test
	@DisplayName("a sidecar keeps the naming base it had")
	void renamesSidecars() {
		// Written next to the video's file name.
		assertEquals("demo.mp4.cursor.json", HeadlessRecord.rename(
				"recording-1.mp4.cursor.json", "recording-1.mp4", "demo.mp4"));
		assertEquals("demo.mp4.keys.json", HeadlessRecord.rename(
				"recording-1.mp4.keys.json", "recording-1.mp4", "demo.mp4"));
		// Written next to its stem (RecordingAudioManifest, the session manifest, the peaks cache).
		assertEquals("demo.mic.wav", HeadlessRecord.rename(
				"recording-1.mic.wav", "recording-1.mp4", "demo.mp4"));
		assertEquals("demo.system.m4a", HeadlessRecord.rename(
				"recording-1.system.m4a", "recording-1.mp4", "demo.mp4"));
		assertEquals("demo.audio-tracks.json", HeadlessRecord.rename(
				"recording-1.audio-tracks.json", "recording-1.mp4", "demo.mp4"));
		assertEquals("demo.gravaai-session.json", HeadlessRecord.rename(
				"recording-1.gravaai-session.json", "recording-1.mp4", "demo.mp4"));
		assertEquals("demo.peaks", HeadlessRecord.rename(
				"recording-1.peaks", "recording-1.mp4", "demo.mp4"));
		// A name that does not belong to the recording is never rewritten.
		assertEquals("other.mp4.cursor.json", HeadlessRecord.rename(
				"other.mp4.cursor.json", "recording-1.mp4", "demo.mp4"));
	}
}
