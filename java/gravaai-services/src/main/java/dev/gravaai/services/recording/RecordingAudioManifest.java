package dev.gravaai.services.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The record of a recording's untouched audio tracks: {@code <recording>.audio-tracks.json}, written
 * next to a recording that keeps its capture tracks as sidecars ({@code <recording>.mic.wav},
 * {@code <recording>.system.m4a}).
 *
 * <p>A recording made with microphone noise reduction has the cleaned microphone baked into its
 * own audio, which is what any player hears. The sidecars keep what was actually captured, so the
 * editor can start from the untouched microphone and apply the same noise reduction reversibly.
 * The manifest is what tells the editor and the exporter that the sidecars are <em>complete</em>:
 * they cover every captured track, so the recording's own (already mixed) audio must not be
 * played on top of them. A sidecar without a manifest keeps its old meaning (an extra track next
 * to the recording's audio).
 *
 * @param tracks the captured roles the sidecars cover, {@code "mic"} and/or {@code "system"}
 * @param micNoiseReduction the record-time microphone noise reduction level ({@code "off"},
 *     {@code "light"}, {@code "balanced"}, {@code "strong"})
 */
public record RecordingAudioManifest(List<String> tracks, String micNoiseReduction) {

	/** Suffix of the manifest next to the recording (the stem replaces the extension). */
	public static final String SUFFIX = ".audio-tracks.json";

	/** The untouched microphone: lossless, so nothing is lost before the editor's cleanup. */
	public static final String MIC_SIDECAR_SUFFIX = ".mic.wav";

	/** The system audio: AAC like the recording's own track, which is all it ever was. */
	public static final String SYSTEM_SIDECAR_SUFFIX = ".system.m4a";

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	public RecordingAudioManifest {
		tracks = tracks == null ? List.of() : List.copyOf(tracks);
		micNoiseReduction = micNoiseReduction == null || micNoiseReduction.isBlank()
				? MicNoiseReduction.OFF.wire() : micNoiseReduction;
	}

	/** {@code <dir>/<stem>} of a recording path: what every sidecar name starts with. */
	public static String stemOf(Path recording) {
		String path = recording.toString();
		int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		int dot = path.lastIndexOf('.');
		return dot > slash ? path.substring(0, dot) : path;
	}

	public static Path pathFor(Path recording) {
		return Path.of(stemOf(recording) + SUFFIX);
	}

	public static Path micSidecar(Path recording) {
		return Path.of(stemOf(recording) + MIC_SIDECAR_SUFFIX);
	}

	public static Path systemSidecar(Path recording) {
		return Path.of(stemOf(recording) + SYSTEM_SIDECAR_SUFFIX);
	}

	/**
	 * The manifest of a recording whose listed sidecars are all still on disk. A missing or
	 * unreadable manifest, or one whose sidecar was deleted, reads as "no manifest": the recording
	 * then plays its own audio, which is always complete.
	 */
	public static Optional<RecordingAudioManifest> read(Path recording) {
		Path file = pathFor(recording);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			JsonNode root = Json.readFile(file);
			List<String> tracks = new ArrayList<>();
			JsonNode listed = root.get("tracks");
			if (listed != null && listed.isArray()) {
				for (JsonNode track : listed) {
					if (track.isString()) {
						tracks.add(track.asString());
					}
				}
			}
			JsonNode level = root.get("micNoiseReduction");
			RecordingAudioManifest manifest = new RecordingAudioManifest(tracks,
					level != null && level.isString() ? level.asString() : "");
			return manifest.tracks().isEmpty() || manifest.sidecars(recording).size()
					!= manifest.tracks().size() ? Optional.empty() : Optional.of(manifest);
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "unreadable audio manifest " + file + ": " + error);
			return Optional.empty();
		}
	}

	/** The sidecar files of the listed tracks that exist, system first (the mixer's order). */
	public List<Path> sidecars(Path recording) {
		List<Path> files = new ArrayList<>();
		if (tracks.contains("system") && Files.isRegularFile(systemSidecar(recording))) {
			files.add(systemSidecar(recording));
		}
		if (tracks.contains("mic") && Files.isRegularFile(micSidecar(recording))) {
			files.add(micSidecar(recording));
		}
		return List.copyOf(files);
	}

	/** The path of the untouched microphone sidecar when this manifest lists one. */
	public Optional<Path> mic(Path recording) {
		Path file = micSidecar(recording);
		return tracks.contains("mic") && Files.isRegularFile(file) ? Optional.of(file)
				: Optional.empty();
	}

	/** Writes the manifest next to {@code recording}. */
	public void write(Path recording) throws IOException {
		ObjectNode root = Json.nodeFactory().objectNode();
		root.put("version", 1);
		ArrayNode listed = root.putArray("tracks");
		tracks.forEach(listed::add);
		root.put("micNoiseReduction", micNoiseReduction);
		Json.writeFile(pathFor(recording), root);
	}
}
