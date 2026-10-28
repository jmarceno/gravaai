package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioInput;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioRole;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioTrack;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxInput;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxResult;
import dev.gravaai.services.recording.CosmicSession.CosmicSessionManifest;
import dev.gravaai.services.recording.CosmicSession.MeetingInfo;
import dev.gravaai.services.recording.CosmicSession.RecoverResult;
import dev.gravaai.services.recording.CosmicSession.RecoverableRecording;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Ported from {@code electron/ipc/recording/cosmicSession.test.ts}. */
class CosmicSessionTest {

	// Far above any real pid, so ProcessHandle.of reports it as dead.
	private static final long DEAD_PID = (1L << 22) + 12345;
	private static final long TS = 1_761_300_000_000L;
	private static final DateTimeFormatter ISO =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	private static final String VIDEO_NAME = ".recording-" + TS + ".video.mkv";
	private static final String MIC_NAME = ".recording-" + TS + ".mic.pcm";
	private static final String SYSTEM_NAME = ".recording-" + TS + ".system.pcm";

	@TempDir
	Path dir;

	private Path manifestPath;

	@BeforeEach
	void setUp() {
		manifestPath = CosmicSession.sessionManifestPath(dir, TS);
	}

	private String outputPath() {
		return dir.resolve("recording-" + TS + ".mp4").toString();
	}

	private String videoPath() {
		return dir.resolve(VIDEO_NAME).toString();
	}

	private String micPath() {
		return dir.resolve(MIC_NAME).toString();
	}

	private String systemPath() {
		return dir.resolve(SYSTEM_NAME).toString();
	}

	private CosmicSessionManifest manifest(long pid) {
		return new CosmicSessionManifest(
				1,
				TS,
				pid,
				ISO.format(Instant.ofEpochMilli(TS)),
				outputPath(),
				videoPath(),
				TS + 500,
				false,
				MicNoiseReduction.OFF,
				List.of(
						new CosmicAudioTrack(CosmicAudioRole.MIC, micPath(), TS + 480),
						new CosmicAudioTrack(CosmicAudioRole.SYSTEM, systemPath(), null)));
	}

	private CosmicSessionManifest manifest() {
		return manifest(DEAD_PID);
	}

	private CosmicSessionManifest audioOnlyMeetingManifest() {
		return new CosmicSessionManifest(
				1,
				TS,
				DEAD_PID,
				ISO.format(Instant.ofEpochMilli(TS)),
				dir.resolve("recording-" + TS + ".m4a").toString(),
				null,
				null,
				true,
				MicNoiseReduction.OFF,
				List.of(
						new CosmicAudioTrack(CosmicAudioRole.MIC, micPath(), TS + 480),
						new CosmicAudioTrack(CosmicAudioRole.SYSTEM, systemPath(), null)));
	}

	private void writeFile(String name, int size) throws IOException {
		Files.write(dir.resolve(name), new byte[size]);
	}

	private Path writeSession(CosmicSessionManifest value, String... files) throws IOException {
		CosmicSession.writeSessionManifest(manifestPath, value);
		return manifestPath;
	}

	private List<String> names() throws IOException {
		try (Stream<Path> entries = Files.list(dir)) {
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private String validJson(CosmicSessionManifest value) throws IOException {
		Path temporary = dir.resolve("valid.json");
		CosmicSession.writeSessionManifest(temporary, value);
		String text = Files.readString(temporary, StandardCharsets.UTF_8);
		Files.delete(temporary);
		return text;
	}

	private void writeJson(String text) throws IOException {
		Files.writeString(manifestPath, text, StandardCharsets.UTF_8);
	}

	private static List<String> list(String... values) {
		return List.of(values);
	}

	@Test
	@DisplayName("round-trips and leaves no temp file")
	void roundTripsAndLeavesNoTempFile() throws IOException {
		CosmicSessionManifest value = manifest();
		writeSession(value);
		assertEquals(value, CosmicSession.readSessionManifest(manifestPath));
		assertEquals(List.of(manifestPath.getFileName().toString()), names());
	}

	@Test
	@DisplayName("rejects paths outside the recordings folder")
	void rejectsPathsOutsideTheRecordingsFolder() throws IOException {
		List<Consumer<ObjectNode>> mutations = List.of(
				node -> node.put("outputPath", "/etc/recording-1.mp4"),
				node -> node.put("outputPath", dir.resolve("notes.txt").toString()),
				node -> node.put("videoPath", dir.resolve("..").resolve(VIDEO_NAME).toString()),
				node -> node.put("videoPath", dir.resolve(".recording-999.video.mkv").toString()),
				node -> {
					ArrayNode audio = node.putArray("audio");
					audio.addObject().put("role", "mic").put("path", "/home/user/file.pcm")
							.putNull("startedAtMs");
				});
		for (Consumer<ObjectNode> mutation : mutations) {
			ObjectNode node = (ObjectNode) Json.parseJsonWithByteOrderMark(validJson(manifest()));
			mutation.accept(node);
			writeJson(Json.stringify(node));
			assertNull(CosmicSession.readSessionManifest(manifestPath), node.toString());
		}
	}

	@Test
	@DisplayName("rejects malformed manifests")
	void rejectsMalformedManifests() throws IOException {
		List<String> bad = new ArrayList<>();
		bad.add("not json");
		ObjectNode versionTwo = (ObjectNode) Json.parseJsonWithByteOrderMark(validJson(manifest()));
		versionTwo.put("version", 2);
		bad.add(Json.stringify(versionTwo));
		ObjectNode badStart = (ObjectNode) Json.parseJsonWithByteOrderMark(validJson(manifest()));
		badStart.put("startedAtMs", "soon");
		bad.add(Json.stringify(badStart));
		ObjectNode badRole = (ObjectNode) Json.parseJsonWithByteOrderMark(validJson(manifest()));
		ArrayNode audio = badRole.putArray("audio");
		audio.addObject().put("role", "speaker").put("path", "x");
		bad.add(Json.stringify(badRole));
		ObjectNode wrongTimestamp = (ObjectNode) Json.parseJsonWithByteOrderMark(validJson(manifest()));
		wrongTimestamp.put("timestamp", TS + 1);
		bad.add(Json.stringify(wrongTimestamp));
		for (String text : bad) {
			writeJson(text);
			assertNull(CosmicSession.readSessionManifest(manifestPath), text);
		}
	}

	@Test
	@DisplayName("lists sessions of dead processes with their data size")
	void listsSessionsOfDeadProcesses() throws IOException {
		writeFile(VIDEO_NAME, 5000);
		writeFile(MIC_NAME, 2048);
		CosmicSessionManifest value = manifest();
		writeSession(value);

		assertEquals(
				List.of(new RecoverableRecording(
						String.valueOf(TS),
						manifestPath,
						value.createdAt(),
						true,
						false,
						7048)),
				CosmicSession.listRecoverableCosmicRecordings(dir, null));
	}

	@Test
	@DisplayName("skips the active recording and live processes")
	void skipsTheActiveRecordingAndLiveProcesses() throws IOException {
		writeFile(VIDEO_NAME, 5000);
		CosmicSession.writeSessionManifest(manifestPath, manifest());
		assertEquals(List.of(), CosmicSession.listRecoverableCosmicRecordings(dir, TS));

		CosmicSession.writeSessionManifest(manifestPath, manifest(ProcessHandle.current().pid()));
		assertEquals(List.of(), CosmicSession.listRecoverableCosmicRecordings(dir, null));
		// pid 1 is alive (EPERM for another user, still alive).
		CosmicSession.writeSessionManifest(manifestPath, manifest(1L));
		assertEquals(List.of(), CosmicSession.listRecoverableCosmicRecordings(dir, null));
	}

	@Test
	@DisplayName("silently removes sessions without data")
	void silentlyRemovesSessionsWithoutData() throws IOException {
		writeFile(MIC_NAME, 100);
		CosmicSession.writeSessionManifest(manifestPath, manifest());
		assertEquals(List.of(), CosmicSession.listRecoverableCosmicRecordings(dir, null));
		assertEquals(List.of(), names());
	}

	@Test
	@DisplayName("muxes what survived and removes the session")
	void muxesWhatSurvivedAndRemovesTheSession() throws IOException {
		CosmicSessionManifest value = manifest();
		writeFile(VIDEO_NAME, 5000);
		writeFile(MIC_NAME, 2048);
		writeFile(SYSTEM_NAME, 2048);
		CosmicSession.writeSessionManifest(manifestPath, value);
		AtomicReference<CosmicMuxInput> captured = new AtomicReference<>();
		CosmicSession.CosmicMux mux = input -> {
			captured.set(input);
			Files.writeString(Path.of(input.outputPath()), "muxed");
			return CosmicMuxResult.ok();
		};

		RecoverResult result = CosmicSession.recoverCosmicRecording(manifestPath, mux);

		assertEquals(new RecoverResult(true, value.outputPath(), null, null), result);
		assertEquals(
				new CosmicMuxInput(
						value.videoPath(),
						List.of(
								new CosmicAudioInput(CosmicAudioRole.MIC, value.audio().get(0).path(), TS + 480),
								// Unknown start: aligned to the reference.
								new CosmicAudioInput(CosmicAudioRole.SYSTEM, value.audio().get(1).path(), TS + 500)),
						TS + 500,
						value.outputPath(),
						false,
						MicNoiseReduction.OFF,
						true),
				captured.get());
		assertEquals(List.of(Path.of(value.outputPath()).getFileName().toString()), names());
	}

	@Test
	@DisplayName("aligns an audio-only meeting to its earliest audio")
	void alignsAnAudioOnlyMeeting() throws IOException {
		CosmicSessionManifest value = audioOnlyMeetingManifest();
		writeFile(MIC_NAME, 2048);
		CosmicSession.writeSessionManifest(manifestPath, value);
		AtomicReference<CosmicMuxInput> captured = new AtomicReference<>();
		CosmicSession.CosmicMux mux = input -> {
			captured.set(input);
			Files.writeString(Path.of(input.outputPath()), "muxed");
			return CosmicMuxResult.ok();
		};

		assertEquals(
				new RecoverResult(true, value.outputPath(), new MeetingInfo(false), null),
				CosmicSession.recoverCosmicRecording(manifestPath, mux));
		assertEquals(null, captured.get().videoPath());
		assertEquals(TS + 480, captured.get().startedAtMs());
		assertEquals(
				List.of(new CosmicAudioInput(CosmicAudioRole.MIC, micPath(), TS + 480)),
				captured.get().audio());
	}

	@Test
	@DisplayName("refuses when already recovered or empty")
	void refusesWhenAlreadyRecoveredOrEmpty() throws IOException {
		CosmicSessionManifest value = manifest();
		CosmicSession.writeSessionManifest(manifestPath, value);
		assertEquals(
				RecoverResult.err("Nothing to recover."),
				CosmicSession.recoverCosmicRecording(manifestPath, okMux()));
		Files.writeString(Path.of(value.outputPath()), "existing");
		assertEquals(
				RecoverResult.err(
						"This recording was already saved. Discard to remove the leftover files."),
				CosmicSession.recoverCosmicRecording(manifestPath, okMux()));
	}

	private static CosmicSession.CosmicMux okMux() {
		return input -> {
			Files.writeString(Path.of(input.outputPath()), "muxed");
			return CosmicMuxResult.ok();
		};
	}

	@Test
	@DisplayName("keeps the files when the mux fails")
	void keepsTheFilesWhenTheMuxFails() throws IOException {
		writeFile(VIDEO_NAME, 5000);
		CosmicSession.writeSessionManifest(manifestPath, manifest());
		RecoverResult result = CosmicSession.recoverCosmicRecording(
				manifestPath,
				input -> CosmicMuxResult.err("moov atom not found"));
		assertEquals(RecoverResult.err("moov atom not found"), result);
		assertEquals(
				list(".recording-" + TS + ".session.json", VIDEO_NAME).stream().sorted().toList(),
				names());
	}

	@Test
	@DisplayName("deletes only the session's own files")
	void deletesOnlyTheSessionsOwnFiles() throws IOException {
		Files.writeString(dir.resolve("recording-1.mp4"), "keep");
		writeFile(VIDEO_NAME, 5000);
		writeFile(MIC_NAME, 2048);
		CosmicSession.writeSessionManifest(manifestPath, manifest());
		CosmicSession.discardRecoverableCosmicRecording(manifestPath);
		assertEquals(List.of("recording-1.mp4"), names());
	}

	@Test
	@DisplayName("accepts only timestamp ids")
	void acceptsOnlyTimestampIds() {
		assertEquals(
				CosmicSession.sessionManifestPath(dir, TS),
				CosmicSession.recoverableManifestPath(dir, String.valueOf(TS)));
		assertNull(CosmicSession.recoverableManifestPath(dir, "../x"));
		assertNull(CosmicSession.recoverableManifestPath(dir, 5));
	}
}
