package dev.gravaai.services.meeting.ports;

import java.nio.file.Path;

/** Bundled media executables; the Lead wires this to platform.NativeHelpers. */
public interface MediaTools {

	/** The ffmpeg CLI (plan §5.7). */
	Path ffmpeg();

	/** whisper-cli from the bundled whisper runtime (electron/ipc/captions/generate.ts). */
	Path whisperCli();
}
