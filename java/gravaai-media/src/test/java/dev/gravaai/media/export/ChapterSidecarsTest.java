package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.core.project.Chapters;

/** {@link ChapterSidecars}: the ffmetadata document, the sidecar text and the escaped names. */
class ChapterSidecarsTest {

	@TempDir
	Path tempDir;

	private static final List<Chapters.Chapter> CHAPTERS = List.of(
			new Chapters.Chapter(0, "Intro"),
			new Chapters.Chapter(5_000, "Demo = fun; #1"),
			new Chapters.Chapter(12_500, "Wrap up"));

	@Test
	@DisplayName("the ffmetadata document starts at 00:00 and closes the last chapter at the duration")
	void ffmetadataDocument() {
		String document = ChapterSidecars.ffmetadata(CHAPTERS, 20_000);
		assertTrue(document.startsWith(";FFMETADATA1\n"));
		assertTrue(document.contains("TIMEBASE=1/1000"));
		assertTrue(document.contains("START=0\nEND=5000\ntitle=Intro"));
		assertTrue(document.contains("START=5000\nEND=12500"));
		// The last chapter ends at the export duration, not at its own start.
		assertTrue(document.contains("START=12500\nEND=20000\ntitle=Wrap up"));
	}

	@Test
	@DisplayName("ffmetadata special characters are escaped so ffmpeg reads the title verbatim")
	void escapesTitles() {
		assertEquals("Demo \\= fun\\; \\#1", ChapterSidecars.escape("Demo = fun; #1"));
		assertEquals("back\\\\slash", ChapterSidecars.escape("back\\slash"));
		String document = ChapterSidecars.ffmetadata(CHAPTERS, 20_000);
		assertTrue(document.contains("title=Demo \\= fun\\; \\#1"), document);
	}

	@Test
	@DisplayName("the sidecar is <video base>.chapters.txt written next to the published file")
	void writesSidecar() throws Exception {
		Path video = tempDir.resolve("clip.mp4");
		Path sidecar = ChapterSidecars.sidecarPath(video);
		assertEquals("clip.chapters.txt", sidecar.getFileName().toString());
		ChapterSidecars.WriteResult result = ChapterSidecars.writeBestEffort(video,
				new ChapterSidecars.Payload(CHAPTERS));
		assertTrue(result.wrote());
		assertTrue(result.error().isEmpty());
		assertTrue(Files.isRegularFile(sidecar));
		assertEquals("00:00 Intro\n00:05 Demo = fun; #1\n00:13 Wrap up\n",
				readString(sidecar));
	}

	@Test
	@DisplayName("an export without chapters writes nothing and reports no sidecar")
	void noChapters() {
		Path video = tempDir.resolve("clip.mp4");
		ChapterSidecars.WriteResult result = ChapterSidecars.writeBestEffort(video,
				ChapterSidecars.Payload.NONE);
		assertFalse(result.wrote());
		assertFalse(Files.exists(ChapterSidecars.sidecarPath(video)));
	}

	@Test
	@DisplayName("the metadata temp file is only written when there are chapters and is cleaned by the caller")
	void metadataFile() throws Exception {
		Path exportFile = tempDir.resolve(".gravaai-export-1.mp4");
		assertTrue(ChapterSidecars.writeMetadataFile(exportFile, List.of(), 1000).isEmpty());
		Path metadata = ChapterSidecars.writeMetadataFile(exportFile, CHAPTERS, 20_000).orElseThrow();
		assertTrue(Files.isRegularFile(metadata));
		assertEquals(ChapterSidecars.ffmetadata(CHAPTERS, 20_000), readString(metadata));
		assertEquals(".gravaai-export-1.mp4.chapters.txt",
				metadata.getFileName().toString());
	}

	@Test
	@DisplayName("the MP4 encoder adds the ffmetadata input and maps its chapters onto the output")
	void encoderArguments() {
		Path metadata = tempDir.resolve("export.mp4.chapters.txt");
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 1.0, tempDir.resolve("export.mp4"), ExportAudioPlan.NONE,
				CHAPTERS);
		List<String> arguments = Mp4Encoder.buildArguments(request, true,
				VideoEncoderSpec.SOFTWARE, java.util.Optional.of(metadata));
		int metadataInput = arguments.indexOf(metadata.toString());
		assertTrue(metadataInput > 0);
		// The document is read as "-f ffmetadata -i <path>".
		assertEquals("-i", arguments.get(metadataInput - 1));
		assertEquals("ffmetadata", arguments.get(metadataInput - 2));
		assertEquals("-f", arguments.get(metadataInput - 3));
		// The metadata input is input #1 (rawvideo is #0), so the chapters come from index 1.
		assertTrue(arguments.contains("-map_chapters"));
		assertEquals("1", arguments.get(arguments.indexOf("-map_chapters") + 1));
		assertTrue(arguments.contains("+faststart"));

		// Without chapters there is no extra input at all.
		List<String> none = Mp4Encoder.buildArguments(request, true, VideoEncoderSpec.SOFTWARE,
				java.util.Optional.empty());
		assertFalse(none.contains("ffmetadata"));
		assertFalse(none.contains("-map_chapters"));
	}

	@Test
	@DisplayName("the audio inputs push the chapter metadata input index down")
	void encoderArgumentsWithAudio() throws Exception {
		Path audio = tempDir.resolve("mix.wav");
		Files.write(audio, new byte[] {1});
		ExportAudioPlan plan = new ExportAudioPlan(List.of(audio), "[0:a]atrim=0:1[aout]");
		Path metadata = tempDir.resolve("export.mp4.chapters.txt");
		Mp4ExportRequest request = new Mp4ExportRequest(160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 1.0, tempDir.resolve("export.mp4"), plan, CHAPTERS);
		List<String> arguments = Mp4Encoder.buildArguments(request, true,
				VideoEncoderSpec.SOFTWARE, java.util.Optional.of(metadata));
		// rawvideo #0, one audio input #1, then the ffmetadata #2.
		assertEquals("2", arguments.get(arguments.indexOf("-map_chapters") + 1));
	}

	@Test
	@DisplayName("the export outcome carries the chapter sidecar result")
	void runnerWritesChapters() throws Exception {
		Path destination = tempDir.resolve("exports").resolve("chaptered.mp4");
		ExportRunner.Spec spec = new ExportRunner.Spec(ExportFormat.MP4, 160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 0.5, false, destination, false, false, null,
				ExportAudioPlan.NONE, CHAPTERS);
		ExportRunner.Outcome outcome = ExportRunner.run(spec,
				new SyntheticFrameProvider(160, 90, 24), ExportProgressListener.NONE,
				ExportCancellation.NONE, new ExportPublisher(null));
		assertTrue(outcome.chapterSidecars().wrote());
		assertTrue(Files.isRegularFile(tempDir.resolve("exports").resolve(
				"chaptered.chapters.txt")));
		// The ffmetadata temp file does not survive the encode (the sidecar is a different file).
		assertFalse(Files.exists(destination.resolveSibling(
				".gravaai-export-1.mp4.chapters.txt")));
		assertFalse(hasMetadataTempFile(tempDir.resolve("exports")));
		assertTrue(outcome.captionSidecars() == null || !outcome.captionSidecars().wroteAny());
	}

	private static String readString(Path file) throws Exception {
		return Files.readString(file);
	}

	private static boolean hasMetadataTempFile(Path dir) throws Exception {
		try (var stream = Files.list(dir)) {
			return stream.anyMatch(path -> path.getFileName().toString()
					.startsWith(".gravaai-export-"));
		}
	}
}
