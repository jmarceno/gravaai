package dev.gravaai.services.meeting.ports;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * Downloads a meeting asset to a file, reporting 0-100 progress. Electron uses
 * {@code downloadFileWithProgress} from captions/whisper.ts (plain text output, no library).
 */
public interface FileDownloader {

	void download(URI uri, Path target, IntConsumer onProgress) throws IOException, InterruptedException;
}
