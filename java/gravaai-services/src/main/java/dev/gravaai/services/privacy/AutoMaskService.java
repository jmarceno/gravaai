package dev.gravaai.services.privacy;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.privacy.DetectedRegion;
import dev.gravaai.core.privacy.RegionTracker;
import dev.gravaai.media.compose.MediaFrameReader;
import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.detection.FaceBox;
import dev.gravaai.media.detection.FaceDetector;
import dev.gravaai.media.privacy.OcrEngine;
import dev.gravaai.services.meeting.CancelSignal;

/**
 * The auto-masking analysis pass: samples a recording, runs face detection and OCR + PII over each
 * sample, and emits stable timed blur regions.
 *
 * <p>Mirrors {@link dev.gravaai.services.captions.AutoCaptionService}: a JavaFX-free state machine
 * the editor subscribes to, a job the executor runs, and a cancel flag checked between samples.
 * The detector pipeline itself is injected as a {@link Detector} port so the state machine is
 * testable without ONNX or a real video.</p>
 *
 * <p>Sampling is done with {@link MediaFrameReader#decodeForward(double)}: sequential decode pays
 * one decode per source frame instead of a seek per sample, which is what makes a 5-minute
 * recording at 1 fps cheap.</p>
 *
 * <p>Progress is weighted the way the plan splits the work: 70% sampling/detection, 20% the PII
 * pass (which happens inline as each unique line is seen), 10% tracking. The weights are
 * approximate on purpose — a percentage that never stalls matters more than one that maps exactly
 * to CPU time.</p>
 */
public final class AutoMaskService implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(AutoMaskService.class.getName());

	/** Samples per second taken from the recording. */
	public static final int SAMPLES_PER_SECOND = 1;

	public enum State {
		IDLE,
		EXTRACTING,
		DETECTING,
		STABILIZING,
		DONE,
		ERROR,
		CANCELLED
	}

	/**
	 * One immutable status snapshot pushed to listeners. {@code regions} is never null (empty when
	 * there is nothing to report yet); {@code result} and {@code error} are the terminal payloads
	 * of {@code DONE} and {@code ERROR} respectively.
	 */
	public record Status(
			State state,
			int progress,
			String message,
			List<RegionTracker.TimedRegion> regions,
			@Nullable Result result,
			@Nullable String error) {

		public Status {
			regions = List.copyOf(regions);
		}

		public boolean running() {
			return state == State.EXTRACTING || state == State.DETECTING
					|| state == State.STABILIZING;
		}
	}

	/** What the analysis pass runs on. */
	public record Options(Path recording, long durationMs, boolean detectFaces,
			boolean detectText) {

		public Options {
			if (recording == null) {
				throw new IllegalArgumentException("recording path must not be null");
			}
			if (!Files.isRegularFile(recording)) {
				throw new IllegalArgumentException("recording not found: " + recording);
			}
			if (durationMs <= 0) {
				throw new IllegalArgumentException("recording duration must be positive: "
						+ durationMs);
			}
			if (!detectFaces && !detectText) {
				throw new IllegalArgumentException("at least one detector must be enabled");
			}
		}
	}

	/** A run's result. */
	public record Result(List<RegionTracker.TimedRegion> regions, int faceRegions,
			int textRegions, int sampledFrames, int distinctTexts, int frameWidth,
			int frameHeight) {

		public Result {
			regions = List.copyOf(regions);
		}
	}

	/**
	 * The sampling + detection pipeline, injectable so the state machine can be tested with a fake.
	 *
	 * @param progress 0-100
	 * @param signal   checked between samples
	 */
	@FunctionalInterface
	public interface Detector {
		Result analyse(Options options, IntConsumer progress, CancelSignal signal);
	}

	private final Detector detector;
	private final ExecutorService executor;
	private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
	private volatile Status status = new Status(State.IDLE, 0, "", List.of(), null, null);
	private volatile CancelSignal.@Nullable Flag cancelSignal;

	public AutoMaskService(Detector detector, ExecutorService executor) {
		this.detector = detector;
		this.executor = executor;
	}

	/** A service running the real ONNX pipeline on a daemon thread. */
	public static AutoMaskService withRealDetectors(Path glinerModelDir) {
		MediaDetector real = new MediaDetector(glinerModelDir);
		return new AutoMaskService(real, Threads.daemon("gravaai-automask"));
	}

	public Status status() {
		return status;
	}

	public AutoCloseable addListener(Consumer<Status> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Starts a run; replaces any in-flight run by cancelling it first. */
	public void start(Options options) {
		cancel();
		CancelSignal.Flag flag = new CancelSignal.Flag();
		cancelSignal = flag;
		executor.execute(() -> runAnalysis(options, flag));
	}

	public void cancel() {
		CancelSignal.Flag flag = cancelSignal;
		if (flag != null) {
			flag.cancel();
		}
	}

	/**
	 * Reports first-use model download progress (0-100) so the inspector can show it. The download
	 * runs before the analysis pass, so this deliberately does not flip the state out of IDLE: it
	 * only replaces the message and percentage of the current snapshot.
	 */
	public void reportDownloadProgress(int percent) {
		Status current = status;
		if (current.running()) {
			return;
		}
		publish(new Status(State.IDLE, Math.clamp(percent, 0, 100),
				percent >= 100 ? "Model installed." : "Downloading the detection model…",
				List.of(), null, null));
	}

	private void runAnalysis(Options options, CancelSignal.Flag flag) {
		publish(new Status(State.EXTRACTING, 0, "Sampling frames…", List.of(), null, null));
		try {
			flag.throwIfCancelled();
			Result result = detector.analyse(options, percent -> {
				if (!flag.isCancelled()) {
					// The pipeline reports 0-100 for its own work; map it onto this state machine's
					// own stage so the bar reflects where the run is, not just raw progress.
					State state = percent < 70 ? State.EXTRACTING
							: percent < 90 ? State.DETECTING : State.STABILIZING;
					publish(new Status(state, percent, messageFor(state), List.of(), null, null));
				}
			}, flag);
			flag.throwIfCancelled();
			publish(new Status(State.DONE, 100, doneMessage(result), result.regions(), result,
					null));
		} catch (CancelSignal.CancelledException cancelled) {
			publish(new Status(State.CANCELLED, 0, "Cancelled.", List.of(), null, null));
		} catch (RuntimeException error) {
			publish(failure(error));
		} catch (Error fatal) {
			// An Error (OOM while copying a tensor, for instance) would otherwise kill the worker
			// with the status still saying EXTRACTING, leaving the inspector's bar spinning on a
			// run that no longer exists. Report it, then let it propagate as Errors must.
			publish(failure(fatal));
			throw fatal;
		}
	}

	/** The ERROR status for a failed run, with its message or a generic fallback. */
	private static Status failure(Throwable error) {
		String message = error.getMessage() == null || error.getMessage().isBlank()
				? "Failed to detect masks automatically"
				: error.getMessage();
		return new Status(State.ERROR, 0, message, List.of(), null, message);
	}

	private static String messageFor(State state) {
		return switch (state) {
			case EXTRACTING -> "Scanning faces and screen text…";
			case DETECTING -> "Checking screen text for personal data…";
			case STABILIZING -> "Stabilising regions…";
			default -> "";
		};
	}

	/** The success toast of the {@code detect-auto-masks} handler. */
	static String doneMessage(Result result) {
		if (result.regions().isEmpty()) {
			return "No faces or personal data found.";
		}
		// Count from the regions themselves: the record's face/text tallies are a convenience for
		// callers, and deriving them here keeps the toast from disagreeing with what was written.
		int faces = (int) result.regions().stream().filter(region ->
				region.region().source() == DetectedRegion.Source.FACE).count();
		int texts = result.regions().size() - faces;
		List<String> parts = new ArrayList<>(2);
		if (faces > 0) {
			parts.add(faces + (faces == 1 ? " face" : " faces"));
		}
		if (texts > 0) {
			parts.add(texts + (texts == 1 ? " text region" : " text regions"));
		}
		return "Masked " + String.join(" and ", parts) + ".";
	}

	/**
	 * Publishes a snapshot to every listener. A listener that throws must not take the run down or
	 * be mistaken for an analysis failure: the UI rebuilds JavaFX nodes from here, and a bad rebuild
	 * is a UI bug, not a reason to abort masking and report a bogus error to the user.
	 */
	private void publish(Status next) {
		status = next;
		for (Consumer<Status> listener : listeners) {
			try {
				listener.accept(next);
			} catch (RuntimeException brokenListener) {
				LOG.log(System.Logger.Level.WARNING,
						"auto mask listener failed: " + brokenListener);
			}
		}
	}

	@Override
	public void close() {
		cancel();
		executor.shutdownNow();
	}

	/**
	 * The real pipeline: sample, detect, track. Owns the ONNX detectors for one run, because ORT
	 * sessions are single-caller and a run is the natural unit (a re-run rebuilds them, which also
	 * picks up a freshly downloaded model).
	 */
	static final class MediaDetector implements Detector {

		private static final System.Logger LOG =
				System.getLogger(MediaDetector.class.getName());
		/** Recognised lines below this mean confidence are ignored. */
		private static final float MIN_TEXT_CONFIDENCE = 0.5f;
		/** Span margin and union padding applied by the tracker. */
		private static final RegionTracker.Options TRACKING = RegionTracker.Options.DEFAULTS;

		private final Path glinerModelDir;

		MediaDetector(Path glinerModelDir) {
			this.glinerModelDir = glinerModelDir;
		}

		@Override
		public Result analyse(Options options, IntConsumer progress, CancelSignal signal) {
			Path recording = options.recording();
			long durationMs = options.durationMs();
			try (MediaFrameReader reader = new MediaFrameReader(recording, durationMs / 1000.0,
					true);
					FaceDetector faces = options.detectFaces() ? FaceDetector.openBundled() : null;
					OcrEngine ocr = options.detectText() ? OcrEngine.openBundled() : null;
					GlinerPiiAdapter pii = options.detectText()
							? GlinerPiiAdapter.open(glinerModelDir) : null) {
				PiiCache cache = pii == null ? null : new PiiCache(pii);
				List<RegionTracker.Sample> samples = new ArrayList<>();
				int sampleCount = (int) Math.max(1, (durationMs + 999) / 1000);
				long frameWidth = 0;
				long frameHeight = 0;
				for (int index = 0; index < sampleCount; index++) {
					signal.throwIfCancelled();
					long timeMs = Math.min(durationMs, (long) index * 1000);
					RgbaFrame frame = reader.decodeForward(timeMs / 1000.0);
					if (frame == null) {
						break;
					}
					frameWidth = frame.width();
					frameHeight = frame.height();
					List<DetectedRegion> detections = new ArrayList<>();
					if (faces != null) {
						for (FaceBox box : faces.detect(frame)) {
							detections.add(new DetectedRegion(box.x(), box.y(),
									box.x() + box.width(), box.y() + box.height(),
									DetectedRegion.Source.FACE));
						}
					}
					if (ocr != null && cache != null) {
						for (OcrEngine.TextLine line : ocr.read(frame, MIN_TEXT_CONFIDENCE)) {
							// Only lines whose PII spans are non-empty become regions; the whole
							// box is masked because the text is what leaks, not just the substring.
							if (!cache.detect(line.text()).isEmpty()) {
								detections.add(new DetectedRegion(line.left(), line.top(),
										line.right(), line.bottom(),
										DetectedRegion.Source.TEXT));
							}
						}
					}
					samples.add(new RegionTracker.Sample(timeMs, detections));
					progress.accept((index + 1) * 70 / sampleCount);
				}
				if (frameWidth <= 0 || frameHeight <= 0) {
					return new Result(List.of(), 0, 0, samples.size(),
							cache == null ? 0 : cache.distinctTexts(), 0, 0);
				}
				progress.accept(85);
				RegionTracker tracker = new RegionTracker(TRACKING, frameWidth, frameHeight);
				List<RegionTracker.TimedRegion> regions = tracker.track(samples, durationMs);
				progress.accept(95);
				int faceRegions = (int) regions.stream().filter(region ->
						region.region().source() == DetectedRegion.Source.FACE).count();
				int textRegions = regions.size() - faceRegions;
				LOG.log(System.Logger.Level.DEBUG, () -> "auto mask: " + samples.size()
						+ " samples, " + regions.size() + " regions ("
						+ (cache == null ? 0 : cache.distinctTexts()) + " distinct text lines, "
						+ (pii == null ? "faces only" : pii.describe()) + ")");
				return new Result(regions, faceRegions, textRegions, samples.size(),
						cache == null ? 0 : cache.distinctTexts(), (int) frameWidth,
						(int) frameHeight);
			}
		}
	}
}
