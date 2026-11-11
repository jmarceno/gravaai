package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;

/**
 * The whole export path (temp file → encode → publish) on the real GPU (card "GPU hardware encoding
 * for export and recording"), and the engine label it reports.
 *
 * <p>Opt-in like the other hardware tests:
 * {@code -Dgravaai.gpuEncodeTests=true -Dgravaai.ffmpeg.bin=/usr/bin/ffmpeg}.
 */
@EnabledIfSystemProperty(named = "gravaai.gpuEncodeTests", matches = "true")
class ExportRunnerHardwareTest {

	@TempDir
	Path temp;

	@Test
	@DisplayName("a 4K export runs end to end on the GPU and reports the encoder that ran")
	void exportsOnTheGpu() throws Exception {
		var captured = new java.util.ArrayList<ExportProgress>();
		Path destination = temp.resolve("gpu-export.mp4");
		ExportRunner.Spec spec = new ExportRunner.Spec(ExportFormat.MP4, 3840, 2160, 30, 24_000_000,
				ExportEncodingMode.BALANCED, 0.2, false, destination, false, true, null,
				ExportAudioPlan.NONE);
		ExportRunner.Outcome outcome = ExportRunner.run(spec,
				new SyntheticFrameProvider(3840, 2160, 30), captured::add, ExportCancellation.NONE,
				new ExportPublisher(path -> { }));
		assertTrue(java.nio.file.Files.isRegularFile(outcome.path()));
		assertEquals("h264",
				dev.gravaai.media.ffmpeg.MediaProbe.probe(outcome.path()).video().orElseThrow().codec());
		String encoderName = captured.getLast().encoderName();
		assertTrue(encoderName != null && encoderName.endsWith("(GPU)"),
				"the dialog label must name the GPU encoder, got: " + encoderName);
		assertTrue(encoderName.startsWith("h264_"), encoderName);
	}

	@Test
	@DisplayName("a 1080p export stays on libx264, where it is measurably faster on this card")
	void keepsSmallExportsOnTheCpu() throws Exception {
		var captured = new java.util.ArrayList<ExportProgress>();
		ExportRunner.Spec spec = new ExportRunner.Spec(ExportFormat.MP4, 1920, 1080, 30, 8_000_000,
				ExportEncodingMode.BALANCED, 0.2, false, temp.resolve("cpu-export.mp4"), false, true,
				null, ExportAudioPlan.NONE);
		ExportRunner.run(spec, new SyntheticFrameProvider(1920, 1080, 30), captured::add,
				ExportCancellation.NONE, new ExportPublisher(path -> { }));
		assertEquals("libx264 (software, CPU)", captured.getLast().encoderName(),
				"a 1080p export must not be pushed onto the slower GPU path");
	}
}
