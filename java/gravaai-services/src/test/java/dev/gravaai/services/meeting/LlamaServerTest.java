package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.services.meeting.ports.FileDownloader;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/** llama-server command line and lifecycle for the local summarizer (llamaServer.ts). */
class LlamaServerTest {

	@Test
	@DisplayName("builds the pinned llama-server arguments")
	void buildsPinnedArguments() {
		assertEquals(List.of(
				"-m", "/models/qwen.gguf",
				"--host", "127.0.0.1",
				"--port", "12345",
				"-c", "16384",
				"-np", "1",
				"--fit", "on",
				"--no-webui"), LlamaServer.llamaServerArgs("/models/qwen.gguf", 12345));
		assertEquals(16_384, LlamaServer.LOCAL_CONTEXT_TOKENS);
	}

	@Test
	@DisplayName("rejects an unknown local model before starting anything")
	void rejectsUnknownLocalModel(@TempDir Path directory) {
		MeetingAssets assets = new MeetingAssets(directory);
		AssetInstaller installer = new AssetInstaller(new NoDownloader(), new NoProcesses());
		LlamaServer server = new LlamaServer(assets, installer, new NoProcesses());
		assertThrows(IllegalArgumentException.class,
				() -> server.start("unknown-model", MeetingStatusCallback.IGNORING));
		assertFalse(server.isRunning());
		server.stop();
		server.close();
	}

	private static final class NoDownloader implements FileDownloader {
		@Override
		public void download(java.net.URI uri, Path target, java.util.function.IntConsumer onProgress) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class NoProcesses implements ProcessRunnerPort {
		@Override
		public ProcessResult run(List<String> command, java.time.Duration timeout,
				java.util.Map<String, String> environment) {
			throw new UnsupportedOperationException();
		}

		@Override
		public RunningProcess spawn(List<String> command, java.util.Map<String, String> environment,
				java.util.function.Consumer<String> stderrLines) {
			throw new UnsupportedOperationException();
		}
	}
}
