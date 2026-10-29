package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.StorageFiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code <video>.gravaai-session.json} webcam link, ported from
 * {@code electron/ipc/project/session.ts} (plan §5.6.6: the webcam track keeps its own time
 * offset so the editor can align it with the screen recording).
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class RecordingSessionManifest {

	/** What the UI/editor needs to know about a recording's linked webcam track. */
	public @Nullable record RecordingSessionData(String videoPath, @Nullable String webcamPath, long timeOffsetMs) {
	}

	/** The manifest shape written by version 2. */
	@Nullable record RecordingSessionManifestFile(int version, String videoFileName, String webcamFileName, long timeOffsetMs) {
	}

	private static final int MANIFEST_VERSION = 2;

	@Nullable private RecordingSessionManifest() {
	}

	private static @Nullable long normalizeRecordingTimeOffsetMs(@Nullable Object value) {
		return value instanceof Number number && Double.isFinite(number.doubleValue())
				? Math.round(number.doubleValue())
				: 0;
	}

	/** {@code getRecordingSessionManifestPath}: next to the video, with the video's stem. */
	public static @Nullable Path getRecordingSessionManifestPath(String videoPath) {
		Path path = Path.of(videoPath);
		String fileName = path.getFileName().toString();
		int extensionStart = fileName.lastIndexOf('.');
		String baseName = extensionStart > 0 ? fileName.substring(0, extensionStart) : fileName;
		Path parent = path.getParent();
		return (parent == null ? Path.of("") : parent)
				.resolve(baseName + StorageFiles.RECORDING_SESSION_MANIFEST_SUFFIX);
	}

	private static @Nullable String normalizeVideoSourcePath(String videoPath) {
		if (videoPath == null) {
			return null;
		}
		String trimmed = videoPath.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** {@code persistRecordingSessionManifest}: writes the link, or removes it without a webcam. */
	public static void persistRecordingSessionManifest(RecordingSessionData session) throws IOException {
		String normalizedVideoPath = normalizeVideoSourcePath(session.videoPath());
		if (normalizedVideoPath == null) {
			return;
		}
		String normalizedWebcamPath = normalizeVideoSourcePath(session.webcamPath());
		Path manifestPath = getRecordingSessionManifestPath(normalizedVideoPath);
		if (normalizedWebcamPath == null) {
			Files.deleteIfExists(manifestPath);
			return;
		}
		ObjectNode manifest = Json.nodeFactory().objectNode();
		manifest.put("version", MANIFEST_VERSION);
		manifest.put("videoFileName", Path.of(normalizedVideoPath).getFileName().toString());
		manifest.put("webcamFileName", Path.of(normalizedWebcamPath).getFileName().toString());
		manifest.put("timeOffsetMs", normalizeRecordingTimeOffsetMs(session.timeOffsetMs()));
		Json.writeFile(manifestPath, manifest);
	}

	/** {@code resolveRecordingSessionManifest}: the manifest when the webcam file still exists. */
	public static @Nullable RecordingSessionData resolveRecordingSessionManifest(String videoPath) {
		String normalizedVideoPath = normalizeVideoSourcePath(videoPath);
		if (normalizedVideoPath == null) {
			return null;
		}
		Path manifestPath = getRecordingSessionManifestPath(normalizedVideoPath);
		try {
			JsonNode parsed = Json.parseJsonWithByteOrderMark(
					Files.readString(manifestPath, StandardCharsets.UTF_8));
			JsonNode version = parsed.get("version");
			if (version == null || !version.isNumber() || (version.asInt() != 1 && version.asInt() != 2)) {
				return null;
			}
			JsonNode webcamFileNameNode = parsed.get("webcamFileName");
			String webcamFileName = webcamFileNameNode != null && webcamFileNameNode.isString()
					? webcamFileNameNode.asString().trim()
					: "";
			long timeOffsetMs = normalizeRecordingTimeOffsetMs(
					parsed.get("timeOffsetMs") == null ? null : parsed.get("timeOffsetMs").asDouble());
			if (webcamFileName.isEmpty()) {
				return new RecordingSessionData(normalizedVideoPath, null, timeOffsetMs);
			}
			Path webcamPath = manifestPath.getParent() == null
					? Path.of(webcamFileName)
					: manifestPath.getParent().resolve(webcamFileName);
			boolean webcamExists = Files.exists(webcamPath);
			return new RecordingSessionData(
					normalizedVideoPath, webcamExists ? webcamPath.toString() : null, timeOffsetMs);
		} catch (IOException | RuntimeException error) {
			return null;
		}
	}

	/** {@code resolveLinkedWebcamPath}: the legacy {@code <stem>-webcam.<ext>} sibling. */
	public static @Nullable String resolveLinkedWebcamPath(String videoPath) {
		String normalizedVideoPath = normalizeVideoSourcePath(videoPath);
		if (normalizedVideoPath == null) {
			return null;
		}
		Path path = Path.of(normalizedVideoPath);
		String fileName = path.getFileName().toString();
		int extensionStart = fileName.lastIndexOf('.');
		String baseName = extensionStart > 0 ? fileName.substring(0, extensionStart) : fileName;
		String extension = extensionStart > 0 ? fileName.substring(extensionStart) : "";
		if (baseName.isEmpty() || baseName.endsWith("-webcam")) {
			return null;
		}
		List<String> candidates = new ArrayList<>();
		for (String candidate : List.of(extension, ".webm", ".mp4", ".mov", ".mkv", ".avi")) {
			if (!candidate.isEmpty() && !candidates.contains(candidate)) {
				candidates.add(candidate);
			}
		}
		for (String candidate : candidates) {
			Path candidatePath = (path.getParent() == null ? Path.of("") : path.getParent())
					.resolve(baseName + "-webcam" + candidate);
			if (Files.exists(candidatePath)) {
				return candidatePath.toString();
			}
		}
		return null;
	}

	/** {@code resolveRecordingSession}: manifest first, legacy sibling second. */
	public static @Nullable RecordingSessionData resolveRecordingSession(String videoPath) {
		RecordingSessionData manifestSession = resolveRecordingSessionManifest(videoPath);
		if (manifestSession != null) {
			return manifestSession;
		}
		String normalizedVideoPath = normalizeVideoSourcePath(videoPath);
		if (normalizedVideoPath == null) {
			return null;
		}
		return new RecordingSessionData(
				normalizedVideoPath, resolveLinkedWebcamPath(normalizedVideoPath), 0);
	}
}
