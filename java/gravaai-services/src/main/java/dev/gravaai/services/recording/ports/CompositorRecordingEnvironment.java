package dev.gravaai.services.recording.ports;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.platform.compositor.CaptureBackend;
import dev.gravaai.platform.compositor.CursorBackend;
import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.platform.nativehelpers.NativeHelpers;

/**
 * {@link RecordingEnvironment} that resolves the screen-capture and pointer helpers for the current
 * compositor.
 *
 * <p>Everything else is delegated unchanged, so this stays additive: on COSMIC both helpers are
 * exactly the ones used before, while portal-only compositors (KDE Plasma, GNOME, wlroots) get
 * {@code gravaai-portal-capture} and the pointer source their desktop supports. The helpers of
 * each kind share one command line and one stdout contract, so {@code CosmicRecorder} and
 * {@code CursorCaptureSession} record identically.
 *
 * <p>Which helper to use is decided once, by the {@link DesktopSession} the app classifies at
 * startup; this class only turns that decision into paths.
 *
 * <p>If a chosen helper is missing from the payload the request fails loudly rather than falling
 * back to another backend: they capture different things (direct output vs. the portal's own
 * picker), so a silent swap would record the wrong surface.
 */
public final class CompositorRecordingEnvironment implements RecordingEnvironment {

	private final RecordingEnvironment delegate;
	private final CaptureBackend capture;
	private final CursorBackend cursor;
	private final Path nativeDir;

	/**
	 * Resolves the helpers the session asks for.
	 *
	 * @param delegate the platform environment the non-compositor parts delegate to
	 * @param session the classified session
	 */
	public CompositorRecordingEnvironment(RecordingEnvironment delegate, DesktopSession session) {
		this(delegate, session, NativeHelpers.nativeDir());
	}

	/**
	 * Resolves helpers from an explicit directory; used by tests and by callers that already know
	 * where the native payload lives.
	 */
	public CompositorRecordingEnvironment(
			RecordingEnvironment delegate, DesktopSession session, Path nativeDir) {
		this(delegate, session.captureBackend(), session.cursorBackend(), nativeDir);
	}

	/**
	 * Resolves helpers from explicit backends and directory; used by tests that pin one mechanism
	 * without going through a whole session.
	 */
	public CompositorRecordingEnvironment(
			RecordingEnvironment delegate, CaptureBackend capture, CursorBackend cursor, Path nativeDir) {
		this.delegate = delegate;
		this.capture = capture;
		this.cursor = cursor;
		this.nativeDir = nativeDir;
	}

	/** The capture backend this environment resolved for the session. */
	public CaptureBackend backend() {
		return capture;
	}

	/** The pointer source this environment resolved for the session. */
	public CursorBackend cursorBackend() {
		return cursor;
	}

	@Override
	public Path captureHelper() {
		if (capture == CaptureBackend.EXT_IMAGE_COPY) {
			return delegate.captureHelper();
		}
		return requireHelper(capture.helperName());
	}

	@Override
	public Path cursorHelper() {
		if (cursor == CursorBackend.EXT_IMAGE_COPY) {
			return delegate.cursorHelper();
		}
		return requireHelper(cursor.helperName());
	}

	private Path requireHelper(String name) {
		Path helper = nativeDir.resolve(name);
		if (!Files.isRegularFile(helper)) {
			throw new IllegalStateException("native helper '" + name + "' is missing: " + helper
					+ " (rebuild the native payload with -Pdist)");
		}
		return helper;
	}

	@Override
	public Path ffmpeg() {
		return delegate.ffmpeg();
	}

	@Override
	public @Nullable Path rnnoiseModel() {
		return delegate.rnnoiseModel();
	}

	@Override
	public @Nullable MicDenoiser micDenoiser() {
		return delegate.micDenoiser();
	}

	@Override
	public Path command(String name) throws IOException {
		return delegate.command(name);
	}
}
