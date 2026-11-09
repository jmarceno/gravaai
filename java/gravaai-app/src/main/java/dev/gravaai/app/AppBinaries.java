package dev.gravaai.app;

import java.nio.file.Path;

import dev.gravaai.media.export.FfmpegCli;
import dev.gravaai.platform.nativehelpers.NativeHelpers;

/**
 * The bundled external tools the app runs: the ffmpeg CLI and the whisper CLI.
 *
 * <p>Six call sites in {@code gravaai-app} built the same
 * {@code NativeHelpers.nativeDir().resolve("ffmpeg/bin/ffmpeg")} path by hand, plus a seventh copy
 * of the whisper path, each with its own assumption about the payload layout. This is the one place
 * that knows those layouts.
 *
 * <p>These are locators, not existence checks: a missing binary surfaces when the process is
 * spawned, where the error can name the operation that needed it. That keeps them usable on the
 * startup path before any media work begins.
 */
public final class AppBinaries {

	private AppBinaries() {}

	/**
	 * The bundled software ffmpeg CLI (plan §5.7, §5.10).
	 *
	 * <p>{@link FfmpegCli} is the media layer's own resolver: it honours
	 * {@code -Dgravaai.ffmpeg.bin} and the development payload ladder, so there is one answer to
	 * "where is ffmpeg" rather than two.
	 */
	public static Path ffmpeg() {
		return FfmpegCli.binary();
	}

	/** The bundled whisper-cli from the whisper runtime (plan §5.12). */
	public static Path whisperCli() {
		return NativeHelpers.payloadPath("whisper/whisper-cli");
	}
}
