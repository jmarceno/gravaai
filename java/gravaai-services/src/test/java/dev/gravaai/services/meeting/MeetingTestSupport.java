package dev.gravaai.services.meeting;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.gravaai.services.meeting.OpenAiClient.ChatCompletionRequest;
import dev.gravaai.services.meeting.OpenAiClient.OpenAiEndpoint;
import dev.gravaai.services.meeting.OpenAiClient.RequestOptions;
import dev.gravaai.services.meeting.OpenAiClient.TranscriptionRequest;
import dev.gravaai.services.meeting.OpenAiClient.TranscriptionResult;
import dev.gravaai.services.meeting.ports.FileDownloader;
import dev.gravaai.services.meeting.ports.MeetingApiKeyStore;
import dev.gravaai.services.meeting.ports.MeetingAudioRecorder;
import dev.gravaai.services.meeting.ports.MeetingRecordingOptions;
import dev.gravaai.services.meeting.ports.MediaTools;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;
import dev.gravaai.services.meeting.ports.RecordingDirectories;
import dev.gravaai.services.meeting.ports.Reveal;
import dev.gravaai.services.meeting.ports.SettingsStore;
import dev.gravaai.services.meeting.ports.Trash;

/** Small in-memory fakes for the meeting service ports. */
final class MeetingTestSupport {

	private MeetingTestSupport() {
	}

	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T18:00:00.123Z"),
			ZoneOffset.UTC);

	static final class FakeSettingsStore implements SettingsStore {

		final Map<String, Object> values = new LinkedHashMap<>();
		int saves;

		@Override
		public Object get(String key) {
			return values.get(key);
		}

		@Override
		public void set(String key, Object value) {
			values.put(key, value);
		}

		@Override
		public void save() {
			saves++;
		}
	}

	static final class FakeApiKeyStore implements MeetingApiKeyStore {

		String key;

		@Override
		public Optional<String> read() {
			return Optional.ofNullable(key);
		}

		@Override
		public void write(String value) {
			key = value;
		}

		@Override
		public void clear() {
			key = null;
		}
	}

	/** Runs every submitted task immediately on the calling thread. */
	static final java.util.concurrent.Executor DIRECT_EXECUTOR = Runnable::run;

	/** Collects submitted tasks so a test can decide when the queue drains. */
	static final class ManualExecutor implements java.util.concurrent.Executor {

		final Deque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			tasks.add(command);
		}

		void runNext() {
			Runnable task = tasks.poll();
			if (task != null) {
				task.run();
			}
		}
	}

	static final class FakeProcessRunner implements ProcessRunnerPort {

		record Call(List<String> command, Duration timeout, Map<String, String> environment) {
		}

		final List<Call> runs = new ArrayList<>();
		final Deque<ProcessResult> results = new ArrayDeque<>();

		@Override
		public ProcessResult run(List<String> command, Duration timeout,
				Map<String, String> environment) {
			runs.add(new Call(command, timeout, environment));
			ProcessResult result = results.poll();
			return result == null ? new ProcessResult(0, "", "") : result;
		}

		@Override
		public RunningProcess spawn(List<String> command, Map<String, String> environment,
				Consumer<String> stderrLines) {
			throw new UnsupportedOperationException("spawn is not used in these tests");
		}
	}

	static final class FakeDownloader implements FileDownloader {

		final Map<String, byte[]> files = new LinkedHashMap<>();
		int downloads;

		@Override
		public void download(URI uri, Path target, IntConsumer onProgress)
				throws IOException {
			downloads++;
			byte[] content = files.getOrDefault(uri.toString(), new byte[0]);
			if (content.length == 0) {
				throw new IOException("No fake download for " + uri);
			}
			onProgress.accept(100);
			Files.write(target, content);
		}
	}

	static final class FakeTrash implements Trash {

		final List<Path> trashed = new ArrayList<>();

		@Override
		public void trash(Path path) {
			trashed.add(path);
		}
	}

	static final class FakeReveal implements Reveal {

		Path revealed;

		@Override
		public void revealInFolder(Path path) {
			revealed = path;
		}
	}

	static final class FakeRecordingDirectories implements RecordingDirectories {

		Path directory;

		FakeRecordingDirectories(Path directory) {
			this.directory = directory;
		}

		@Override
		public Path recordingsDirectory() {
			return directory;
		}
	}

	static final class FakeAudioRecorder implements MeetingAudioRecorder {

		MeetingRecordingOptions options;
		RuntimeException failure;

		@Override
		public void start(MeetingRecordingOptions value) {
			if (failure != null) {
				throw failure;
			}
			options = value;
		}
	}

	static final MediaTools FAKE_MEDIA_TOOLS = new MediaTools() {
		@Override
		public Path ffmpeg() {
			return Path.of("/fake/ffmpeg");
		}

		@Override
		public Path whisperCli() {
			return Path.of("/fake/whisper-cli");
		}
	};

	/** OpenAI client that records requests and returns queued answers. */
	static final class FakeOpenAiClient implements OpenAiClient {

		final List<ChatCompletionRequest> chatRequests = new ArrayList<>();
		final List<TranscriptionRequest> transcriptionRequests = new ArrayList<>();
		final Deque<String> chatAnswers = new ArrayDeque<>();
		TranscriptionResult transcriptionResult = new TranscriptionResult("", List.of(), null);

		@Override
		public String chatCompletion(OpenAiEndpoint endpoint, ChatCompletionRequest request,
				RequestOptions options) {
			chatRequests.add(request);
			String answer = chatAnswers.poll();
			return answer == null ? "## Summary\nAnswer." : answer;
		}

		@Override
		public TranscriptionResult transcribeAudioFile(OpenAiEndpoint endpoint,
				TranscriptionRequest request, RequestOptions options) {
			transcriptionRequests.add(request);
			return transcriptionResult;
		}
	}

	static String utf8(String value) {
		return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}
}
