package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.playback.PlaybackFrame;

/**
 * A buffer-owning copy of the last decoded playback frame.
 *
 * <p>{@link PlaybackFrame}'s decoded buffers are only valid until the decoder produces the next
 * frame. The preview keeps this snapshot so a paused preview can be composited again after an
 * appearance change or after its renderer is recreated, without asking the playback engine for a
 * seek (which would flush the audio sink while playing). The pixel buffers are reused between
 * frames; callers that map the snapshot must hold its monitor until the submitted input has been
 * copied by the renderer queue (see {@code EditorPreviewView.submitSnapshot}).</p>
 */
final class PreviewFrameSnapshot {

private long frameIndex;
private double timelineSeconds;
private @Nullable Double sourceSeconds;
private @Nullable String clipId;
private boolean webcamVisible;
private @Nullable RgbaFrame sourceFrame;
private @Nullable RgbaFrame webcamFrame;
private @Nullable ByteBuffer sourcePixels;
private @Nullable ByteBuffer webcamPixels;

/** Copies the decoder-owned buffers before the engine may reuse them. */
static PreviewFrameSnapshot capture(PlaybackFrame frame) {
PreviewFrameSnapshot snapshot = new PreviewFrameSnapshot();
snapshot.update(frame);
return snapshot;
}

/** Copies {@code frame} into the reusable snapshot buffers. */
synchronized void update(PlaybackFrame frame) {
if (frame == null) {
throw new IllegalArgumentException("frame must not be null");
}
frameIndex = frame.frameIndex();
timelineSeconds = frame.timelineSeconds();
sourceSeconds = frame.sourceSeconds();
clipId = frame.clipId();
webcamVisible = frame.webcamVisible();
RgbaFrame source = frame.sourceFrame();
if (source == null) {
sourceFrame = null;
sourcePixels = null;
} else {
sourcePixels = copyInto(source, sourcePixels);
sourceFrame = new RgbaFrame(source.width(), source.height(), sourcePixels);
}
RgbaFrame webcam = frame.webcamFrame();
if (webcam == null) {
webcamFrame = null;
webcamPixels = null;
} else {
webcamPixels = copyInto(webcam, webcamPixels);
webcamFrame = new RgbaFrame(webcam.width(), webcam.height(), webcamPixels);
}
}

/** The retained frame, safe to map through a new {@link PreviewFrameMapper.PreviewScene}. */
synchronized PlaybackFrame playbackFrame() {
return new PlaybackFrame(frameIndex, timelineSeconds, sourceSeconds, clipId, sourceFrame,
webcamFrame, webcamVisible);
}

synchronized double timelineSeconds() {
return timelineSeconds;
}

private static ByteBuffer copyInto(RgbaFrame frame, @Nullable ByteBuffer target) {
int needed = frame.width() * frame.height() * 4;
if (target == null || target.capacity() < needed) {
target = ByteBuffer.allocateDirect(needed);
}
target.clear();
ByteBuffer source = frame.pixelsTopFirstRgba().duplicate();
source.position(0);
source.limit(Math.min(source.capacity(), needed));
target.put(source);
target.position(0);
target.limit(target.capacity());
return target;
}
}
