package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.playback.FrameConsumer;
import dev.gravaai.media.playback.PlaybackController;
import dev.gravaai.media.playback.PlaybackFrame;
import dev.gravaai.media.playback.PlaybackMedia;
import dev.gravaai.media.playback.PlaybackState;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.media.playback.PlaybackTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import javafx.stage.Stage;

/**
 * Appearance repaints must never seek the playback engine. A seek flushes the audio sink while
 * playing, and the preview instead re-composites the retained last frame with the new scene.
 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class EditorPreviewViewRepaintTest {

private static final String FIXTURE_EDITOR = """
{
  "wallpaper": "#1b2735",
  "shadowIntensity": 0.4,
  "backgroundBlur": 0,
  "aspectRatio": "16:9",
  "borderRadius": 0,
  "padding": {"top": 20, "bottom": 20, "left": 20, "right": 20, "linked": true},
  "clipRegions": [{"id": "clip-1", "startMs": 0, "endMs": 6000, "speed": 1}]
}
""";

@Start
void start(Stage testFxStage) {
// TestFX starts the JavaFX toolkit; the view is created directly in the test.
}

@DisplayName("appearance repaint while playing never calls seek and retains the last frame")
@Test
void appearanceRepaintDoesNotSeekWhilePlaying() {
EditorStore store = new EditorStore(ProjectPersistence.normalizeProjectEditor(
Json.parseJsonWithByteOrderMark(FIXTURE_EDITOR)));
FakePlayback playback = new FakePlayback();
EditorPreviewView view = new EditorPreviewView();
view.setScene(PreviewFrameMapper.sceneFor(store.state()));
view.bind(playback);

playback.emitStatus(new PlaybackStatus(PlaybackState.PLAYING, 1.0, 6.0, 1.0));
playback.emitFrame(frame(1, 1.0));

assertNotNull(view.lastFrameSnapshot(), "the preview must retain the decoded frame");
view.requestRepaint();
view.requestRepaint();

assertEquals(0, playback.seekCalls,
"appearance repaint must not seek (PlaybackEngine.seek flushes audio)");
view.dispose();
}

private static PlaybackFrame frame(long index, double timelineSeconds) {
ByteBuffer pixels = ByteBuffer.allocateDirect(2 * 2 * 4);
for (int pixel = 0; pixel < 4; pixel++) {
pixels.put((byte) 10).put((byte) 20).put((byte) 30).put((byte) 255);
}
pixels.position(0);
pixels.limit(pixels.capacity());
return new PlaybackFrame(index, timelineSeconds, timelineSeconds, "clip-1",
new RgbaFrame(2, 2, pixels), null, false);
}

/** Minimal controller seam: records seeks and lets the test push frames/status. */
private static final class FakePlayback implements PlaybackController {
private final List<FrameConsumer> frameListeners = new CopyOnWriteArrayList<>();
private final List<Consumer<PlaybackStatus>> statusListeners = new CopyOnWriteArrayList<>();
private final List<Consumer<PlaybackTime>> timeListeners = new CopyOnWriteArrayList<>();
private volatile PlaybackStatus status =
new PlaybackStatus(PlaybackState.PAUSED, 1.0, 6.0, 1.0);
private int seekCalls;

void emitFrame(PlaybackFrame frame) {
for (FrameConsumer listener : frameListeners) {
listener.accept(frame);
}
}

void emitStatus(PlaybackStatus next) {
status = next;
for (Consumer<PlaybackStatus> listener : statusListeners) {
listener.accept(next);
}
}

@Override
public void setMedia(PlaybackMedia media) {
}

@Override
public void play() {
}

@Override
public void pause() {
}

@Override
public void togglePlayPause() {
}

@Override
public void seek(double timelineSeconds) {
seekCalls++;
}

@Override
public void scrub(double timelineSeconds) {
}

@Override
public void setRate(double rate) {
}

@Override
public double rate() {
return 1;
}

@Override
public boolean isPlaying() {
return status.state() == PlaybackState.PLAYING;
}

@Override
public PlaybackStatus status() {
return status;
}

@Override
public AutoCloseable onFrame(FrameConsumer consumer) {
frameListeners.add(consumer);
return () -> frameListeners.remove(consumer);
}

@Override
public AutoCloseable onStatus(Consumer<PlaybackStatus> consumer) {
statusListeners.add(consumer);
return () -> statusListeners.remove(consumer);
}

@Override
public AutoCloseable onTime(Consumer<PlaybackTime> consumer) {
timeListeners.add(consumer);
return () -> timeListeners.remove(consumer);
}

@Override
public void close() {
}
}
}
