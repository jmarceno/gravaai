package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from electron/ipc/meeting/transcript.test.ts (meeting-owned parts). */
class TranscriptTest {

	@Test
	@DisplayName("names the sidecars after the recording")
	void sidecarNames() {
		assertEquals(Path.of("/r/recording-1.meeting-mic.opus"),
				SpeakerTracks.speakerTrackPath(Path.of("/r/recording-1.mp4"), SpeakerRole.MIC));
		assertEquals(Path.of("/r/recording-1.meeting-system.opus"),
				SpeakerTracks.speakerTrackPath(Path.of("/r/recording-1.m4a"), SpeakerRole.SYSTEM));
	}

	@Test
	@DisplayName("builds a mic-left / system-right stereo file when both tracks exist")
	void stereoWhisperInput() {
		List<String> args = Transcript.buildWhisperInputArgs(new MeetingAudioInputs(
				Path.of("/r/a.m4a"),
				Path.of("/r/a.meeting-mic.opus"),
				Path.of("/r/a.meeting-system.opus")), Path.of("/t/a.wav"));
		int first = args.indexOf("-i");
		assertEquals(List.of("-i", "/r/a.meeting-mic.opus", "-i", "/r/a.meeting-system.opus"),
				args.subList(first, first + 4));
		assertTrue(String.join(" ", args).contains("[l][r]amerge=inputs=2"));
	}

	@Test
	@DisplayName("falls back to a mono mix of the recording")
	void monoWhisperInput() {
		List<String> args = Transcript.buildWhisperInputArgs(
				new MeetingAudioInputs(Path.of("/r/demo.mp4"), null, null), Path.of("/t/a.wav"));
		assertTrue(args.contains("/r/demo.mp4"));
		assertEquals("1", args.get(args.indexOf("-ac") + 1));
	}

	private static final WhisperArgsOptions BASE = new WhisperArgsOptions("m", "w", "o", "", 4,
			false, null);

	@Test
	@DisplayName("only asks whisper to diarize stereo input")
	void whisperMeetingArgsDiarize() {
		assertTrue(Transcript.whisperMeetingArgs(new WhisperArgsOptions("m", "w", "o", "", 4, true,
				null)).contains("-di"));
		List<String> mono = Transcript.whisperMeetingArgs(BASE);
		assertFalse(mono.contains("-di"));
		assertEquals("auto", mono.get(mono.indexOf("-l") + 1));
		assertFalse(mono.contains("--vad"));
	}

	@Test
	@DisplayName("decodes only detected speech when a voice-detection model is given")
	void whisperMeetingArgsVad() {
		List<String> args = Transcript.whisperMeetingArgs(
				new WhisperArgsOptions("m", "w", "o", "", 4, true, "/m/vad.bin"));
		assertTrue(args.contains("--vad"));
		assertEquals("/m/vad.bin", args.get(args.indexOf("-vm") + 1));
	}

	private static final String WHISPER_JSON = """
			{
			  "result": { "language": "pt" },
			  "transcription": [
			    { "offsets": { "from": 0 }, "text": " Bom dia a todos.", "speaker": "0" },
			    { "offsets": { "from": 4000 }, "text": " Vamos começar.", "speaker": "0" },
			    { "offsets": { "from": 7200 }, "text": " Bom dia!", "speaker": "1" },
			    { "offsets": { "from": 9000 }, "text": "  ", "speaker": "1" },
			    { "offsets": { "from": 3725000 }, "text": " Até logo.", "speaker": "?" }
			  ]
			}
			""";

	@Test
	@DisplayName("parses language, speakers and drops empty segments")
	void parseWhisperJsonLanguageAndSpeakers() {
		Transcript.ParsedTranscript parsed = Transcript.parseWhisperJson(WHISPER_JSON);
		assertEquals("pt", parsed.language());
		assertEquals(java.util.Arrays.asList(Speaker.ME, Speaker.ME, Speaker.REMOTE, null),
				parsed.segments().stream().map(TranscriptSegment::speaker).toList());
	}

	@Test
	@DisplayName("renders one paragraph per speaker turn")
	void formatTranscriptOneParagraphPerTurn() {
		String markdown = Transcript.formatTranscriptMarkdown(
				Transcript.parseWhisperJson(WHISPER_JSON).segments());
		assertEquals(String.join("\n\n",
				"[00:00:00] **Me:** Bom dia a todos. Vamos começar.",
				"[00:00:07] **Remote:** Bom dia!",
				"[01:02:05] Até logo."), markdown);
	}

	@Test
	@DisplayName("starts a new paragraph when a monologue runs past a minute")
	void formatTranscriptSplitsLongMonologue() {
		List<TranscriptSegment> segments = List.of(
				new TranscriptSegment(0, "x", Speaker.ME),
				new TranscriptSegment(30_000, "x", Speaker.ME),
				new TranscriptSegment(61_000, "x", Speaker.ME));
		assertEquals(2, Transcript.formatTranscriptMarkdown(segments).split("\n\n", -1).length);
	}

	@Test
	@DisplayName("merges separately transcribed tracks by time")
	void mergeSpeakerSegmentsByTime() {
		List<TranscriptSegment> merged = Transcript.mergeSpeakerSegments(
				List.of(new TranscriptSegment(5000, "b", null)),
				List.of(new TranscriptSegment(1000, "a", null)));
		assertEquals(List.of("Remote:a", "Me:b"),
				merged.stream().map(segment -> segment.speaker().label() + ":" + segment.text())
						.toList());
	}

	@Test
	@DisplayName("formats timestamps")
	void formatsTimestamps() {
		assertEquals("[01:01:01]", Transcript.formatTimestamp(3_661_999));
		assertEquals("[00:00:00]", Transcript.formatTimestamp(0));
	}

	@Test
	@DisplayName("keeps meeting files next to the recording")
	void meetingFilesNextToRecording() {
		MeetingFiles files = MeetingFiles.meetingFilesFor(Path.of("/r/recording-1.m4a"));
		assertEquals(Path.of("/r/recording-1.transcript.md"), files.transcript());
		assertEquals(Path.of("/r/recording-1.notes.md"), files.notes());
		assertEquals(Path.of("/r/recording-1.meeting.json"), files.meta());
	}

	@Test
	@DisplayName("cuts API uploads into 10-minute mono MP3 segments")
	void chunkCommandTenMinuteSegments() {
		List<String> args = ApiTranscription.chunkCommand("/r/a.m4a", "/t/chunk-%03d.mp3");
		assertEquals("600", args.get(args.indexOf("-segment_time") + 1));
		assertEquals("1", args.get(args.indexOf("-ac") + 1));
	}

	@Test
	@DisplayName("resolves the speaker sidecars that exist next to the recording")
	void resolveMeetingAudioInputs() throws java.io.IOException {
		Path directory = java.nio.file.Files.createTempDirectory("gravaai-inputs-");
		try {
			Path media = directory.resolve("recording-1.m4a");
			java.nio.file.Files.writeString(media, "x");
			MeetingAudioInputs absent = Transcript.resolveMeetingAudioInputs(media);
			assertNull(absent.mic());
			assertNull(absent.system());

			java.nio.file.Files.writeString(
					SpeakerTracks.speakerTrackPath(media, SpeakerRole.MIC), "x");
			MeetingAudioInputs present = Transcript.resolveMeetingAudioInputs(media);
			assertEquals(SpeakerTracks.speakerTrackPath(media, SpeakerRole.MIC), present.mic());
			assertNull(present.system());
		} finally {
			FileTrees.deleteQuietly(directory);
		}
	}
}
