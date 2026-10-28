package dev.gravaai.services.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.media.export.SyntheticFrameProvider;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import tools.jackson.databind.node.ObjectNode;

/** {@link ProjectExport}: assembles the coordinator request/audio plan/caption payload. */
class ProjectExportTest {

	@TempDir
	Path tempDir;

	private static Path fixturesDirectory() {
		Path directory = Path.of("").toAbsolutePath();
		for (int depth = 0; depth < 6 && directory != null; depth++) {
			Path candidate = directory.resolve(Path.of("parity", "fixtures", "media"));
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
			directory = directory.getParent();
		}
		throw new IllegalStateException("parity fixtures not found");
	}

	private static Path media(String name) {
		return fixturesDirectory().resolve(name);
	}

	private static ExportPreferences preferences(ExportQuality quality,
			ExportEncodingMode encodingMode, ExportFormat format, boolean captionSidecar) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("exportQuality", quality.wire());
		node.put("exportEncodingMode", encodingMode.wire());
		node.put("exportFormat", format.wire());
		node.put("mp4FrameRate", 30);
		node.put("gifFrameRate", 20);
		node.put("gifLoop", true);
		node.put("gifSizePreset", "medium");
		node.put("includeCaptionSidecar", captionSidecar);
		return ExportPreferences.normalizeExportPreferences(node);
	}

	/** A 2 s project: one clip 0-2000 ms of the 2 s H.264 fixture. */
	private static ProjectEditorState twoSecondProject(String videoPath) {
		ObjectNode editor = Json.nodeFactory().objectNode();
		editor.put("aspectRatio", "16:9");
		var clips = editor.putArray("clipRegions");
		var clip = clips.addObject();
		clip.put("id", "clip-1");
		clip.put("startMs", 0);
		clip.put("endMs", 2000);
		editor.putArray("trimRegions");
		editor.putArray("speedRegions");
		editor.putArray("audioRegions");
		editor.putArray("autoCaptions");
		return ProjectPersistence.normalizeProjectEditor(editor);
	}

	@Test
	@DisplayName("prepares a contiguous 2 s project with the companion audio track")
	void preparesTwoSecondProject() {
		Path video = media("h264-1080p60.mp4");
		ExportPreferences preferences = preferences(ExportQuality.MEDIUM,
				ExportEncodingMode.FAST, ExportFormat.MP4, false);
		ProjectExport.Input input = new ProjectExport.Input(
				twoSecondProject(video.toString()), video, null, List.of(media("aac-48k-stereo.m4a")),
				preferences, tempDir.resolve("two-seconds.mp4"), false, false, 0);
		ProjectExport.Prepared prepared = ProjectExport.prepare(input);
		assertEquals(ExportFormat.MP4, prepared.format());
		assertEquals(1152, prepared.outputWidth());
		assertEquals(648, prepared.outputHeight());
		assertEquals(30, prepared.frameRate());
		assertEquals(2_500_000, prepared.bitrate());
		assertEquals(2.0, prepared.durationSeconds(), 0.01);
		assertEquals(2, prepared.request().captionSidecars() == null ? 2 : 2);
		assertTrue(prepared.audioPlan().hasAudio());
		assertEquals(1, prepared.audioInputs().size());
		assertEquals(media("aac-48k-stereo.m4a").toAbsolutePath().normalize(),
				prepared.audioInputs().get(0).toAbsolutePath().normalize());
	}

	@Test
	@DisplayName("audio follows the clip sequence (clip speed, muted clip), not speed regions")
	void audioFollowsTheClipSequence() {
		// A real export of an edited recording drifted 1 s after a speed region (the audio
		// applied it, the picture did not), ignored clip speed and kept a muted clip's audio.
		Path video = media("h264-1080p60.mp4");
		ObjectNode editor = Json.nodeFactory().objectNode();
		editor.put("aspectRatio", "16:9");
		var clips = editor.putArray("clipRegions");
		clips.addObject().put("id", "clip-1").put("startMs", 0).put("endMs", 500).put("muted", true);
		clips.addObject().put("id", "clip-2").put("startMs", 500).put("endMs", 1000)
				.put("sourceStartMs", 1000).put("speed", 2);
		editor.putArray("speedRegions").addObject().put("id", "speed-1").put("startMs", 200)
				.put("endMs", 400).put("speed", 2);
		ProjectExport.Input input = new ProjectExport.Input(
				ProjectPersistence.normalizeProjectEditor(editor), video, null,
				List.of(media("aac-48k-stereo.m4a")),
				preferences(ExportQuality.MEDIUM, ExportEncodingMode.FAST, ExportFormat.MP4, false),
				tempDir.resolve("clips.mp4"), false, false, 0);
		String graph = ProjectExport.prepare(input).audioPlan().filterComplex();
		assertTrue(graph.contains("atrim=start=0.000:end=0.500"), graph);
		assertTrue(graph.contains("atrim=start=1.000:end=2.000,asetpts=PTS-STARTPTS,atempo=2"), graph);
		assertTrue(!graph.contains("start=0.200") && !graph.contains("end=0.200"),
				"speed regions are overlays with a clip timeline: " + graph);
		assertTrue(graph.contains("between(t,0,0.500)"), "the muted clip is silent: " + graph);
	}

	@Test
	@DisplayName("sizes the GIF canvas from the raw source dims, not the aspect-adjusted base")
	void preparesGifFromRawSourceDimensions() {
		Path video = media("h264-1080p60.mp4");
		ExportPreferences preferences = preferences(ExportQuality.MEDIUM,
				ExportEncodingMode.FAST, ExportFormat.GIF, false);
		ObjectNode editor = Json.nodeFactory().objectNode();
		editor.put("aspectRatio", "1:1");
		var clips = editor.putArray("clipRegions");
		var clip = clips.addObject();
		clip.put("id", "clip-1");
		clip.put("startMs", 0);
		clip.put("endMs", 2000);
		editor.putArray("trimRegions");
		editor.putArray("speedRegions");
		editor.putArray("audioRegions");
		editor.putArray("autoCaptions");
		ProjectExport.Input input = new ProjectExport.Input(
				ProjectPersistence.normalizeProjectEditor(editor), video, null, List.of(),
				preferences, tempDir.resolve("two-seconds.gif"), false, false, 0);
		ProjectExport.Prepared prepared = ProjectExport.prepare(input);
		assertEquals(ExportFormat.GIF, prepared.format());
		// React: calculateOutputDimensions(1920, 1080, medium) → 1280x720 (the 1:1 aspect would
		// have shrunk the base to 1080x1080 → 720x720).
		assertEquals(1280, prepared.outputWidth());
		assertEquals(720, prepared.outputHeight());
		assertEquals(20, prepared.frameRate());
		assertEquals(0, prepared.bitrate());
		assertTrue(prepared.request().gifLoop());
	}

	@Test
	@DisplayName("encodes a 2 s project to an H.264/AAC MP4 that probes back correctly")
	void encodesTwoSecondProject() throws Exception {
		Path video = media("h264-1080p60.mp4");
		ExportPreferences preferences = preferences(ExportQuality.MEDIUM,
				ExportEncodingMode.FAST, ExportFormat.MP4, false);
		Path destination = tempDir.resolve("encoded.mp4");
		ProjectExport.Input input = new ProjectExport.Input(
				twoSecondProject(video.toString()), video, null,
				List.of(media("aac-48k-stereo.m4a")), preferences, destination, false, false, 0);
		ProjectExport.Prepared prepared = ProjectExport.prepare(input);
		ExportRunner.Outcome outcome = ProjectExport.run(input,
				new SyntheticFrameProvider(prepared.outputWidth(), prepared.outputHeight(),
						prepared.frameRate()),
				new ExportPublisher(null));
		assertEquals(destination.toAbsolutePath(), outcome.path());
		assertTrue(Files.isRegularFile(destination));
		MediaInfo info = MediaProbe.probe(destination);
		assertTrue(info.hasVideo());
		assertEquals("h264", info.video().orElseThrow().codec());
		assertEquals(prepared.outputWidth(), info.video().orElseThrow().width());
		assertEquals(prepared.outputHeight(), info.video().orElseThrow().height());
		assertTrue(info.hasAudio());
		assertEquals("aac", info.audio().orElseThrow().codec());
		assertEquals(2.0, info.durationSeconds(), 0.2);
	}

	@Test
	@DisplayName("prepares the 2-minute acceptance fixture with its embedded main audio")
	void preparesAcceptanceFixture() throws Exception {
		Path projectFile = fixturesDirectory().resolve("..").resolve("projects")
				.resolve("11-export-2min.gravaai").normalize();
		assertTrue(Files.isRegularFile(projectFile), "acceptance fixture missing: " + projectFile);
		ProjectDocument document = ProjectDocument.load(projectFile);
		Path video = Path.of(document.videoPath());
		ProjectExport.Prepared prepared = ProjectExport.prepare(new ProjectExport.Input(
				document.editor(), video, null, List.of(), ExportPreferences.defaults(),
				tempDir.resolve("acceptance.mp4"), false, false, 0));
		assertEquals(ExportFormat.MP4, prepared.format());
		assertEquals(1280, prepared.outputWidth());
		assertEquals(720, prepared.outputHeight());
		assertEquals(120.0, prepared.durationSeconds(), 0.01);
		assertTrue(prepared.audioPlan().hasAudio(),
				"the 2-minute fixture has embedded main audio");
	}

	@Test
	@Disabled("slow: full 2-minute MP4 encode for the manual P2.11/mpv acceptance")
	@DisplayName("encodes the full 2-minute acceptance fixture")
	void encodesAcceptanceFixture() throws Exception {
		Path projectFile = fixturesDirectory().resolve("..").resolve("projects")
				.resolve("11-export-2min.gravaai").normalize();
		ProjectDocument document = ProjectDocument.load(projectFile);
		Path video = Path.of(document.videoPath());
		ExportPreferences preferences = preferences(ExportQuality.MEDIUM,
				ExportEncodingMode.FAST, ExportFormat.MP4, false);
		Path destination = tempDir.resolve("acceptance-2min.mp4");
		ProjectExport.Input input = new ProjectExport.Input(document.editor(), video, null,
				List.of(), preferences, destination, false, false, 0);
		ProjectExport.Prepared prepared = ProjectExport.prepare(input);
		ExportRunner.Outcome outcome = ProjectExport.run(input,
				new SyntheticFrameProvider(prepared.outputWidth(), prepared.outputHeight(),
						prepared.frameRate()),
				new ExportPublisher(null));
		assertEquals(destination.toAbsolutePath(), outcome.path());
		MediaInfo info = MediaProbe.probe(destination);
		assertEquals(120.0, info.durationSeconds(), 0.5);
		assertTrue(info.hasVideo() && info.hasAudio());
	}
}
