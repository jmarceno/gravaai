package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.MediaFrameReader;

/**
 * The video-wallpaper frame loop shared by the editor preview surface and the dashboard hover
 * preview: one {@link MediaFrameReader} per wallpaper path, the timeline time looped over the
 * wallpaper duration (the same mapping the export uses).
 *
 * <p>Used on the decode/engine threads; the owning surface closes it exactly once on dispose.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class PreviewWallpaper implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.ui.preview");

	private final Object lock = new Object();
	private @Nullable MediaFrameReader reader;
	private @Nullable Path readerPath;
	private volatile boolean closed;

	/**
	 * The background for a frame: the scene's static wallpaper, or the video wallpaper frame at
	 * the timeline time looped over its duration (the same mapping the export uses).
	 */
	public Background backgroundFor(PreviewFrameMapper.PreviewScene current,
			double timelineSeconds) {
		synchronized (lock) {
			Path video = closed ? null : current.videoWallpaper();
			if (video == null || !video.equals(readerPath)) {
				closeReaderLocked();
			}
			if (video == null) {
				return current.background();
			}
			if (reader == null) {
				reader = new MediaFrameReader(video, 0, true);
				readerPath = video;
			}
			try {
				double duration = reader.durationSeconds();
				double loop = duration > 0
						? ((timelineSeconds % duration) + duration) % duration
						: Math.max(0, timelineSeconds);
				var decoded = reader.decodeForward(loop);
				return decoded == null ? current.background()
						: new Background.VideoFrame(decoded);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "video wallpaper decode failed: " + error);
				closeReaderLocked();
				return current.background();
			}
		}
	}

	private void closeReaderLocked() {
		if (reader != null) {
			reader.close();
			reader = null;
			readerPath = null;
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			closed = true;
			closeReaderLocked();
		}
	}
}
