package dev.gravaai.services.recording;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dev.gravaai.core.json.Json;
import tools.jackson.core.PrettyPrinter;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Crash recovery for COSMIC recordings, ported from
 * {@code electron/ipc/recording/cosmicSession.ts}.
 *
 * <p>The temp files survive a crash (the Matroska video can be read unfinalised, raw PCM has no
 * header), so only the in-memory metadata is lost: this manifest keeps it on disk while recording.
 */
public final class CosmicSession {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** The audio track roles a recording keeps. */
	public enum CosmicAudioRole {
		MIC("mic"),
		SYSTEM("system");

		private final String wire;

		CosmicAudioRole(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		static CosmicAudioRole fromWire(String value) {
			for (CosmicAudioRole role : values()) {
				if (role.wire.equals(value)) {
					return role;
				}
			}
			return null;
		}
	}

	/** One audio track in the manifest. */
	public record CosmicAudioTrack(CosmicAudioRole role, String path, Long startedAtMs) {
	}

	/**
	 * The manifest kept next to the temp files while recording. Version 1 is the only one written;
	 * the same fields and types as {@code CosmicSessionManifest} in the TS source.
	 */
	public record CosmicSessionManifest(
			int version,
			long timestamp,
			long pid,
			String createdAt,
			String outputPath,
			String videoPath,
			Long startedAtMs,
			boolean keepSpeakerTracks,
			MicNoiseReduction micNoiseReduction,
			List<CosmicAudioTrack> audio) {
	}

	/** Only tracks with data, ready for the mux. */
	public record CosmicAudioInput(CosmicAudioRole role, String path, long startedAtMs) {
	}

	/** Everything the mux needs for a normal stop or a recovery. */
	public record CosmicMuxInput(
			String videoPath,
			List<CosmicAudioInput> audio,
			long startedAtMs,
			String outputPath,
			boolean keepSpeakerTracks,
			MicNoiseReduction micNoiseReduction,
			boolean recovering) {
	}

	/** Result of one mux run. */
	public record CosmicMuxResult(boolean success, String error) {

		public static CosmicMuxResult ok() {
			return new CosmicMuxResult(true, null);
		}

		public static CosmicMuxResult err(String error) {
			return new CosmicMuxResult(false, error);
		}
	}

	/** Muxes the temp files into the final recording; the recorder supplies the ffmpeg pipeline. */
	@FunctionalInterface
	public interface CosmicMux {
		CosmicMuxResult mux(CosmicMuxInput input) throws Exception;
	}

	/** One recording offered to the recoverable-recordings banner. */
	public record RecoverableRecording(
			String id,
			Path manifestPath,
			String createdAt,
			boolean hasVideo,
			boolean meeting,
			long bytes) {
	}

	/** {@code {input}} or {@code {error}}, the TS union of buildRecoveryMuxInput. */
	public sealed interface RecoveryMuxInputResult
			permits RecoveryMuxInputResult.Ready, RecoveryMuxInputResult.Failed {

		record Ready(CosmicMuxInput input) implements RecoveryMuxInputResult {
		}

		record Failed(String error) implements RecoveryMuxInputResult {
		}
	}

	/** Meeting recordings keep per-speaker tracks; the caller registers them. */
	public record MeetingInfo(boolean hasVideo) {
	}

	/** Result of a recovery. */
	public record RecoverResult(boolean success, String path, MeetingInfo meeting, String error) {

		static RecoverResult ok(String path, MeetingInfo meeting) {
			return new RecoverResult(true, path, meeting, null);
		}

		static RecoverResult err(String error) {
			return new RecoverResult(false, null, null, error);
		}
	}

	private static final Pattern MANIFEST_PATTERN = Pattern.compile("^\\.recording-(\\d+)\\.session\\.json$");
	private static final Pattern OUTPUT_NAME_PATTERN = Pattern.compile("^recording-\\d+\\.(mp4|m4a)$");
	private static final Pattern TIMESTAMP_ID_PATTERN = Pattern.compile("^\\d+$");
	// Raw PCM below this is effectively empty (about 5 ms of audio).
	private static final long MIN_AUDIO_BYTES = 1024;

	/** {@code JSON.stringify(manifest, null, "\t")}, the indentation the TS writer uses. */
	private static final PrettyPrinter TAB_FORMAT = tabFormat();

	private CosmicSession() {
	}

	public static Path sessionManifestPath(Path recordingsDir, long timestamp) {
		return recordingsDir.resolve(".recording-" + timestamp + ".session.json");
	}

	/** Writes to a temp file and renames it, so a crash never leaves half a manifest. */
	public static void writeSessionManifest(Path manifestPath, CosmicSessionManifest manifest) throws IOException {
		Path temporaryPath = Path.of(manifestPath + ".tmp");
		String text = Json.MAPPER.writer().with(TAB_FORMAT).writeValueAsString(toJson(manifest));
		Files.writeString(temporaryPath, text, StandardCharsets.UTF_8);
		Files.move(temporaryPath, manifestPath, StandardCopyOption.REPLACE_EXISTING);
	}

	private static ObjectNode toJson(CosmicSessionManifest manifest) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("version", manifest.version());
		node.put("timestamp", manifest.timestamp());
		node.put("pid", manifest.pid());
		node.put("createdAt", manifest.createdAt());
		node.put("outputPath", manifest.outputPath());
		if (manifest.videoPath() == null) {
			node.putNull("videoPath");
		} else {
			node.put("videoPath", manifest.videoPath());
		}
		putNullableLong(node, "startedAtMs", manifest.startedAtMs());
		node.put("keepSpeakerTracks", manifest.keepSpeakerTracks());
		node.put("micNoiseReduction", manifest.micNoiseReduction().wire());
		ArrayNode audio = node.putArray("audio");
		for (CosmicAudioTrack track : manifest.audio()) {
			ObjectNode trackNode = audio.addObject();
			trackNode.put("role", track.role().wire());
			trackNode.put("path", track.path());
			putNullableLong(trackNode, "startedAtMs", track.startedAtMs());
		}
		return node;
	}

	private static void putNullableLong(ObjectNode node, String field, Long value) {
		if (value == null) {
			node.putNull(field);
		} else {
			node.put(field, value.longValue());
		}
	}

	private static PrettyPrinter tabFormat() {
		Separators separators = Separators.createDefaultInstance()
				.withObjectNameValueSpacing(Separators.Spacing.AFTER)
				.withObjectEmptySeparator("")
				.withArrayEmptySeparator("");
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter(separators);
		printer.indentObjectsWith(new DefaultIndenter("\t", "\n"));
		printer.indentArraysWith(new DefaultIndenter("\t", "\n"));
		return printer;
	}

	/** True when the file sits directly in the recordings dir and belongs to this recording. */
	private static boolean isOwnTempFile(JsonNode file, Path recordingsDir, long timestamp) {
		if (file == null || !file.isString()) {
			return false;
		}
		Path path = Path.of(file.asString());
		Path parent = path.getParent();
		return parent != null
				&& parent.normalize().equals(recordingsDir.normalize())
				&& path.getFileName().toString().startsWith(".recording-" + timestamp + ".");
	}

	private static boolean isFiniteNumber(JsonNode node) {
		return node != null && node.isNumber() && Double.isFinite(node.asDouble());
	}

	/** Parses a manifest; anything malformed or pointing outside its folder is null. */
	public static CosmicSessionManifest parseSessionManifest(String text, Path manifestPath) {
		JsonNode value;
		try {
			value = Json.parseJsonWithByteOrderMark(text);
		} catch (RuntimeException error) {
			return null;
		}
		if (value == null || !value.isObject()) {
			return null;
		}
		Path recordingsDir = manifestPath.getParent();
		if (recordingsDir == null) {
			return null;
		}
		JsonNode version = value.get("version");
		JsonNode timestampNode = value.get("timestamp");
		JsonNode pidNode = value.get("pid");
		if (version == null || !version.isNumber() || version.asInt() != 1
				|| !isFiniteNumber(timestampNode) || !isFiniteNumber(pidNode)) {
			return null;
		}
		long timestamp = Math.round(timestampNode.asDouble());
		if (!sessionManifestPath(recordingsDir, timestamp).normalize().equals(manifestPath.normalize())) {
			return null;
		}
		JsonNode createdAtNode = value.get("createdAt");
		if (createdAtNode == null || !createdAtNode.isString()) {
			return null;
		}
		JsonNode outputPathNode = value.get("outputPath");
		if (outputPathNode == null || !outputPathNode.isString()) {
			return null;
		}
		String outputPath = outputPathNode.asString();
		Path outputFile = Path.of(outputPath);
		Path outputParent = outputFile.getParent();
		if (outputParent == null
				|| !outputParent.normalize().equals(recordingsDir.normalize())
				|| !OUTPUT_NAME_PATTERN.matcher(outputFile.getFileName().toString()).matches()) {
			return null;
		}
		JsonNode videoPathNode = value.get("videoPath");
		if (videoPathNode == null || !(videoPathNode.isNull() || isOwnTempFile(videoPathNode, recordingsDir, timestamp))) {
			return null;
		}
		String videoPath = videoPathNode.isNull() ? null : videoPathNode.asString();
		JsonNode startedAtNode = value.get("startedAtMs");
		if (startedAtNode == null || !(startedAtNode.isNull() || isFiniteNumber(startedAtNode))) {
			return null;
		}
		Long startedAtMs = startedAtNode.isNull() ? null : Long.valueOf(Math.round(startedAtNode.asDouble()));
		JsonNode keepSpeakerTracksNode = value.get("keepSpeakerTracks");
		JsonNode audioNode = value.get("audio");
		if (keepSpeakerTracksNode == null || !keepSpeakerTracksNode.isBoolean()
				|| audioNode == null || !audioNode.isArray()) {
			return null;
		}
		List<CosmicAudioTrack> audio = new ArrayList<>();
		for (JsonNode track : audioNode) {
			if (track == null || !track.isObject()) {
				return null;
			}
			JsonNode roleNode = track.get("role");
			if (roleNode == null || !roleNode.isString() || CosmicAudioRole.fromWire(roleNode.asString()) == null) {
				return null;
			}
			JsonNode trackPathNode = track.get("path");
			if (!isOwnTempFile(trackPathNode, recordingsDir, timestamp)) {
				return null;
			}
			JsonNode trackStartedNode = track.get("startedAtMs");
			if (trackStartedNode == null || !(trackStartedNode.isNull() || isFiniteNumber(trackStartedNode))) {
				return null;
			}
			Long trackStartedAtMs = trackStartedNode.isNull()
					? null
					: Long.valueOf(Math.round(trackStartedNode.asDouble()));
			audio.add(new CosmicAudioTrack(
					CosmicAudioRole.fromWire(roleNode.asString()), trackPathNode.asString(), trackStartedAtMs));
		}
		JsonNode micNode = value.get("micNoiseReduction");
		Object micValue = micNode != null && micNode.isString() ? micNode.asString() : null;
		return new CosmicSessionManifest(
				1,
				timestamp,
				Math.round(pidNode.asDouble()),
				createdAtNode.asString(),
				outputPath,
				videoPath,
				startedAtMs,
				keepSpeakerTracksNode.booleanValue(),
				MicNoiseReductionFilters.normalizeMicNoiseReduction(micValue),
				List.copyOf(audio));
	}

	public static CosmicSessionManifest readSessionManifest(Path manifestPath) {
		try {
			return parseSessionManifest(Files.readString(manifestPath, StandardCharsets.UTF_8), manifestPath);
		} catch (IOException error) {
			return null;
		}
	}

	private static long fileSize(Path file) {
		try {
			return Files.size(file);
		} catch (IOException error) {
			return 0;
		}
	}

	private static boolean isProcessAlive(long pid) {
		return ProcessHandle.of(pid).isPresent();
	}

	private static List<Path> manifestFiles(CosmicSessionManifest manifest) {
		List<Path> files = new ArrayList<>();
		for (CosmicAudioTrack track : manifest.audio()) {
			files.add(Path.of(track.path()));
		}
		if (manifest.videoPath() != null) {
			files.add(Path.of(manifest.videoPath()));
		}
		return files;
	}

	/** Removes the manifest's temp files and the manifest itself. */
	private static void removeSession(Path manifestPath, CosmicSessionManifest manifest) {
		List<Path> files = manifest == null ? List.of() : manifestFiles(manifest);
		for (Path file : files) {
			deleteQuietly(file);
		}
		deleteQuietly(manifestPath);
		deleteQuietly(Path.of(manifestPath + ".tmp"));
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException error) {
			// Best-effort cleanup, like fs.rm(file, { force: true }).catch(() => undefined).
		}
	}

	public static List<RecoverableRecording> listRecoverableCosmicRecordings(
			Path recordingsDir, Long activeTimestamp) {
		List<String> names;
		try (Stream<Path> entries = Files.list(recordingsDir)) {
			names = entries.map(path -> path.getFileName().toString()).sorted().toList();
		} catch (IOException error) {
			return List.of();
		}
		long ownPid = ProcessHandle.current().pid();
		List<RecoverableRecording> items = new ArrayList<>();
		for (String name : names) {
			Matcher match = MANIFEST_PATTERN.matcher(name);
			if (!match.matches()) {
				continue;
			}
			long timestamp = Long.parseLong(match.group(1));
			if (activeTimestamp != null && activeTimestamp == timestamp) {
				continue;
			}
			Path manifestPath = recordingsDir.resolve(name);
			CosmicSessionManifest manifest = readSessionManifest(manifestPath);
			if (manifest == null) {
				LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable recording manifest " + name);
				continue;
			}
			// This process, or a second instance that is still recording.
			if (manifest.pid() == ownPid || isProcessAlive(manifest.pid())) {
				continue;
			}
			long videoBytes = manifest.videoPath() == null ? 0 : fileSize(Path.of(manifest.videoPath()));
			long audioBytes = 0;
			boolean anyAudio = false;
			for (CosmicAudioTrack track : manifest.audio()) {
				long bytes = fileSize(Path.of(track.path()));
				audioBytes += bytes;
				if (bytes > MIN_AUDIO_BYTES) {
					anyAudio = true;
				}
			}
			if (videoBytes == 0 && !anyAudio) {
				LOG.log(System.Logger.Level.INFO, "Removing empty interrupted recording " + name);
				removeSession(manifestPath, manifest);
				continue;
			}
			items.add(new RecoverableRecording(
					String.valueOf(manifest.timestamp()),
					manifestPath,
					manifest.createdAt(),
					videoBytes > 0,
					manifest.keepSpeakerTracks(),
					videoBytes + audioBytes));
		}
		items.sort(Comparator.comparing(RecoverableRecording::createdAt).reversed());
		return items;
	}

	/** Manifest path for a banner item id, or null if the id is not a timestamp. */
	public static Path recoverableManifestPath(Path recordingsDir, Object id) {
		if (id instanceof String value && TIMESTAMP_ID_PATTERN.matcher(value).matches()) {
			return sessionManifestPath(recordingsDir, Long.parseLong(value));
		}
		return null;
	}

	private static boolean pathExists(Path file) {
		return Files.exists(file);
	}

	/** Builds the mux input from what survived, or explains why there is nothing to mux. */
	public static RecoveryMuxInputResult buildRecoveryMuxInput(CosmicSessionManifest manifest) {
		String videoPath = manifest.videoPath() != null && fileSize(Path.of(manifest.videoPath())) > 0
				? manifest.videoPath()
				: null;
		List<CosmicAudioTrack> present = new ArrayList<>();
		for (CosmicAudioTrack track : manifest.audio()) {
			if (fileSize(Path.of(track.path())) > MIN_AUDIO_BYTES) {
				present.add(track);
			}
		}
		if (videoPath == null && present.isEmpty()) {
			return new RecoveryMuxInputResult.Failed("Nothing to recover.");
		}
		List<Long> knownStarts = new ArrayList<>();
		for (CosmicAudioTrack track : present) {
			if (track.startedAtMs() != null) {
				knownStarts.add(track.startedAtMs());
			}
		}
		Long earliestAudio = knownStarts.isEmpty() ? null : knownStarts.stream().min(Long::compare).orElse(null);
		long startedAtMs;
		if (manifest.startedAtMs() != null) {
			startedAtMs = manifest.startedAtMs();
		} else {
			if (videoPath != null && earliestAudio != null) {
				LOG.log(System.Logger.Level.WARNING,
						"Video start time is unknown; aligning to the earliest audio.");
			}
			startedAtMs = earliestAudio != null ? earliestAudio : manifest.timestamp();
		}
		long reference = startedAtMs;
		List<CosmicAudioInput> inputs = new ArrayList<>();
		for (CosmicAudioTrack track : present) {
			inputs.add(new CosmicAudioInput(
					track.role(), track.path(), track.startedAtMs() != null ? track.startedAtMs() : reference));
		}
		return new RecoveryMuxInputResult.Ready(new CosmicMuxInput(
				videoPath,
				List.copyOf(inputs),
				reference,
				manifest.outputPath(),
				manifest.keepSpeakerTracks(),
				manifest.micNoiseReduction(),
				true));
	}

	public static RecoverResult recoverCosmicRecording(Path manifestPath, CosmicMux mux) {
		CosmicSessionManifest manifest = readSessionManifest(manifestPath);
		if (manifest == null) {
			return RecoverResult.err("The recording information is missing.");
		}
		if (pathExists(Path.of(manifest.outputPath()))) {
			return RecoverResult.err(
					"This recording was already saved. Discard to remove the leftover files.");
		}
		RecoveryMuxInputResult built = buildRecoveryMuxInput(manifest);
		if (built instanceof RecoveryMuxInputResult.Failed failed) {
			return RecoverResult.err(failed.error());
		}
		CosmicMuxInput input = ((RecoveryMuxInputResult.Ready) built).input();
		LOG.log(System.Logger.Level.INFO, "Recovering " + manifest.outputPath());
		CosmicMuxResult result;
		try {
			result = mux.mux(input);
		} catch (Exception error) {
			return RecoverResult.err(String.valueOf(error));
		}
		// On failure the files stay so the user can retry or discard.
		if (!result.success()) {
			return RecoverResult.err(result.error() != null ? result.error() : "Recovery failed.");
		}
		removeSession(manifestPath, manifest);
		LOG.log(System.Logger.Level.INFO, "Recovered " + manifest.outputPath());
		return RecoverResult.ok(
				manifest.outputPath(),
				manifest.keepSpeakerTracks() ? new MeetingInfo(input.videoPath() != null) : null);
	}

	public static void discardRecoverableCosmicRecording(Path manifestPath) {
		removeSession(manifestPath, readSessionManifest(manifestPath));
	}

	private static String formatDuration(long bytes) {
		return Long.toString(bytes);
	}
}
