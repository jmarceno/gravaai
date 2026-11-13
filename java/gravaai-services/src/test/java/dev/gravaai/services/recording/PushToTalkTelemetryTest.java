package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.PushToTalkTelemetry.MutedSpan;
import dev.gravaai.services.recording.PushToTalkTelemetry.Recording;

/** The {@code .ptt.json} sidecar: its shape, its guards and the video-clock shift. */
class PushToTalkTelemetryTest {

	@TempDir
	Path dir;

	private static Recording sample() {
		return new Recording(List.of(new MutedSpan(0, 1200), new MutedSpan(4000, 5180)));
	}

	@Test
	@DisplayName("a written sidecar reads back the same muted spans")
	void roundTrip() throws IOException {
		String video = dir.resolve("take.mp4").toString();
		PushToTalkTelemetry.write(video, sample());
		assertTrue(Files.isRegularFile(dir.resolve("take.mp4.ptt.json")));
		assertEquals(sample().spans(), PushToTalkTelemetry.read(video).spans());
	}

	@Test
	@DisplayName("a recording without muted spans leaves no file behind")
	void emptyRemovesTheFile() throws IOException {
		String video = dir.resolve("take.mp4").toString();
		PushToTalkTelemetry.write(video, sample());
		PushToTalkTelemetry.write(video, Recording.EMPTY);
		assertEquals(List.of(), PushToTalkTelemetry.read(video).spans());
		assertFalse(Files.isRegularFile(dir.resolve("take.mp4.ptt.json")));
	}

	@Test
	@DisplayName("corrupt or hostile sidecars degrade to no spans")
	void guards() {
		assertEquals(Recording.EMPTY, PushToTalkTelemetry.parse(null));
		assertEquals(Recording.EMPTY, PushToTalkTelemetry.parse(Json.parseJsonWithByteOrderMark("\"x\"")));
		// A future version is not readable here.
		assertEquals(Recording.EMPTY, PushToTalkTelemetry.parse(Json.parseJsonWithByteOrderMark(
				"{\"version\": 99, \"spans\": [{\"startMs\": 0, \"endMs\": 10}]}")));
		// Broken spans are dropped, sane ones survive, and the result is sorted.
		Recording read = PushToTalkTelemetry.parse(Json.parseJsonWithByteOrderMark("""
				{ "version": 1,
				  "spans": [
				    { "startMs": 5000, "endMs": 4000 },
				    { "startMs": -1, "endMs": 10 },
				    { "endMs": 30 },
				    { "startMs": 100, "endMs": 9000 },
				    { "startMs": 40, "endMs": 50 }
				  ] }
				"""));
		assertEquals(List.of(new MutedSpan(40, 50), new MutedSpan(100, 9000)), read.spans());
	}

	@Test
	@DisplayName("the video-clock shift moves every span")
	void shifted() {
		assertEquals(
				List.of(new MutedSpan(120, 1320), new MutedSpan(4120, 5300)),
				sample().shifted(120).spans());
		assertEquals(sample(), sample().shifted(0));
	}
}
