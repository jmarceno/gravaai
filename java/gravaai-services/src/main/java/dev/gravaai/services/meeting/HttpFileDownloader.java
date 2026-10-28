package dev.gravaai.services.meeting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.IntConsumer;

import dev.gravaai.services.meeting.ports.FileDownloader;

/**
 * Plain HTTP(S) download with progress, ported from captions/whisper.ts
 * {@code downloadFileWithProgress}. Redirects are followed by HttpClient (the Electron helper
 * follows up to 5 by hand).
 */
public final class HttpFileDownloader implements FileDownloader {

	private static final int BUFFER_SIZE = 64 * 1024;

	private final HttpClient client;

	public HttpFileDownloader() {
		this(HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build());
	}

	HttpFileDownloader(HttpClient client) {
		this.client = client;
	}

	@Override
	public void download(URI uri, Path target, IntConsumer onProgress)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(uri).GET()
				.timeout(Duration.ofSeconds(30)).build();
		HttpResponse<InputStream> response = client.send(request,
				HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			response.body().close();
			throw new IOException(
					"Whisper model download failed with status " + response.statusCode() + ".");
		}
		long total = response.headers().firstValueAsLong("content-length").orElse(0L);
		long downloaded = 0L;
		try (InputStream input = response.body();
				OutputStream output = Files.newOutputStream(target)) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				output.write(buffer, 0, read);
				downloaded += read;
				if (total > 0) {
					onProgress.accept((int) Math.min(100L, Math.round(downloaded * 100.0 / total)));
				}
			}
		}
	}
}
