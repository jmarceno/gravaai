package dev.gravaai.media.gaze;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

import dev.gravaai.media.compose.MediaFrameReader;
import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.detection.FaceBox;
import dev.gravaai.media.detection.FaceDetector;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * Walks every frame of a webcam recording once and builds its {@link GazeTrack}: finds the face
 * (YuNet), runs the face mesh on the face square and keeps the eye contours, irises, head turn and
 * face presence. The mesh runs on the GPU; a 720p30 recording analyses several times faster than
 * real time on the RX 560.
 */
public final class GazeAnalyzer {

	private static final System.Logger LOG = System.getLogger(GazeAnalyzer.class.getName());
	/** Below this face-presence the mesh is not trusted (and the frame counts as faceless). */
	static final float MIN_PRESENCE = -2;

	/** Frames between two face detections while the mesh keeps the face. */
	static final int REANCHOR_FRAMES = 30;

	private GazeAnalyzer() {}

	private record Located(FaceSquare square, FaceMeshLandmarker.Landmarks landmarks) {}

	/** The mesh over {@code square}, when it still sees a face there. */
	private static Optional<Located> locate(FaceMeshLandmarker mesh, RgbaFrame frame,
			FaceSquare square) {
		FaceMeshLandmarker.Landmarks lm = mesh.detect(frame, square);
		return lm.presence() < MIN_PRESENCE ? Optional.empty() : Optional.of(new Located(square, lm));
	}

	/**
	 * How the detector's face square relates to the mesh's bounding box (scale and centre offset,
	 * in units of the box's larger side), measured at each anchor so the tracked square matches
	 * the detected one the models were verified with.
	 */
	record Fit(double scale, double dx, double dy) {

		static final Fit DEFAULT = new Fit(1.25, 0, 0.05);

		static Fit of(FaceSquare square, FaceMeshLandmarker.Landmarks lm) {
			double[] box = box(lm);
			double span = Math.max(box[2] - box[0], box[3] - box[1]);
			if (!(span > 1)) {
				return DEFAULT;
			}
			return new Fit(square.side() / span,
					(square.x() + square.side() / 2 - (box[0] + box[2]) / 2) / span,
					(square.y() + square.side() / 2 - (box[1] + box[3]) / 2) / span);
		}

		FaceSquare square(FaceMeshLandmarker.Landmarks lm) {
			double[] box = box(lm);
			double span = Math.max(1, Math.max(box[2] - box[0], box[3] - box[1]));
			double side = span * scale;
			double cx = (box[0] + box[2]) / 2 + dx * span;
			double cy = (box[1] + box[3]) / 2 + dy * span;
			return new FaceSquare(cx - side / 2, cy - side / 2, side);
		}

		private static double[] box(FaceMeshLandmarker.Landmarks lm) {
			double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
					Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
			// The 468 face points; the ten iris points sit inside them.
			for (int i = 0; i < 468; i++) {
				box[0] = Math.min(box[0], lm.x()[i]);
				box[1] = Math.min(box[1], lm.y()[i]);
				box[2] = Math.max(box[2], lm.x()[i]);
				box[3] = Math.max(box[3], lm.y()[i]);
			}
			return box;
		}
	}

	/**
	 * Analyses {@code webcam}.
	 *
	 * @param progress  0..1, called on the analysing thread
	 * @param cancelled polled per frame; a true answer aborts with {@link CancellationException}
	 */
	public static GazeTrack analyze(Path webcam, OnnxWebGpuPlugin.Selection selection,
			DoubleConsumer progress, BooleanSupplier cancelled) {
		OnnxLibs.ensureLoaded();
		try (MediaFrameReader reader = new MediaFrameReader(webcam, 0, false);
				FaceDetector detector = new FaceDetector(OnnxLibs.resolveFaceModelFile(),
						FaceDetector.DEFAULT_INPUT_SIZE);
				FaceMeshLandmarker mesh = new FaceMeshLandmarker(selection)) {
			double duration = reader.durationSeconds();
			TrackBuilder track = new TrackBuilder();
			Optional<FaceSquare> previous = Optional.empty();
			Optional<FaceSquare> tracked = Optional.empty();
			Fit fit = Fit.DEFAULT;
			long frames = 0;
			long lastAnchor = 0;
			int width = 0;
			int height = 0;
			long started = System.nanoTime();
			while (true) {
				if (cancelled.getAsBoolean()) {
					throw new CancellationException("gaze analysis cancelled");
				}
				RgbaFrame frame = reader.decodeNext();
				if (frame == null) {
					break;
				}
				width = frame.width();
				height = frame.height();
				double pts = reader.lastPtsSeconds();
				// The face detector costs twice the mesh, so it only anchors the face: on the first frame,
				// after a loss and every REANCHOR_FRAMES; in between the square follows the mesh.
				boolean anchor = tracked.isEmpty() || frames - lastAnchor >= REANCHOR_FRAMES;
				Optional<Located> located = anchor ? Optional.empty()
						: locate(mesh, frame, tracked.get());
				if (located.isEmpty()) {
					lastAnchor = frames;
					Optional<FaceSquare> square = faceSquare(detector.detect(frame), previous);
					located = square.flatMap(found -> locate(mesh, frame, found));
					if (located.isPresent()) {
						fit = Fit.of(located.get().square(), located.get().landmarks());
					}
				}
				if (located.isEmpty()) {
					track.addMissing(pts);
					previous = Optional.empty();
					tracked = Optional.empty();
				} else {
					track.add(pts, located.get().landmarks(), located.get().square());
					previous = Optional.of(located.get().square());
					tracked = Optional.of(fit.square(located.get().landmarks()));
				}
				frames++;
				if (duration > 0) {
					progress.accept(Math.min(1, pts / duration));
				}
			}
			if (width == 0) {
				throw new IllegalStateException("no decodable frames in " + webcam.getFileName());
			}
			GazeTrack result = track.build(width, height);
			long ms = (System.nanoTime() - started) / 1_000_000;
			LOG.log(System.Logger.Level.INFO, "gaze analysis of " + webcam.getFileName() + ": "
					+ result.size() + " frames in " + ms + " ms, mesh on "
					+ mesh.provider().label() + ", presence " + track.presenceRange());
			progress.accept(1);
			return result;
		}
	}

	/**
	 * The face square for this frame: the detected face nearest the previous one (the largest when
	 * there was none). Falls back to the previous square when the detector misses a frame.
	 */
	static Optional<FaceSquare> faceSquare(List<FaceBox> faces, Optional<FaceSquare> previous) {
		if (faces.isEmpty()) {
			return previous;
		}
		Comparator<FaceBox> order = previous.isPresent()
				? Comparator.comparingDouble(face -> distance(face, previous.get()))
				: Comparator.comparingDouble((FaceBox face) -> -face.width() * face.height());
		FaceBox face = faces.stream().min(order).orElseThrow();
		return Optional.of(FaceSquare.around(face.x(), face.y(), face.width(), face.height()));
	}

	private static double distance(FaceBox face, FaceSquare square) {
		return Math.hypot(face.x() + face.width() / 2 - (square.x() + square.side() / 2),
				face.y() + face.height() / 2 - (square.y() + square.side() / 2));
	}

	/** Grows the track's arrays frame by frame. */
	private static final class TrackBuilder {
		private double[] pts = new double[1024];
		private boolean[] valid = new boolean[1024];
		private float[] eyes = new float[1024 * GazeTrack.EYE_FLOATS];
		private float[] iris = new float[1024 * 4];
		private float[] roi = new float[1024 * 3];
		private float[] yaw = new float[1024];
		private float[] presence = new float[1024];
		private int size;
		private double lastPts = Double.NEGATIVE_INFINITY;

		void addMissing(double ptsSeconds) {
			if (next(ptsSeconds) < 0) {
				return;
			}
			size++;
		}

		void add(double ptsSeconds, FaceMeshLandmarker.Landmarks lm, FaceSquare square) {
			int i = next(ptsSeconds);
			if (i < 0) {
				return;
			}
			valid[i] = true;
			float[] xs = lm.x();
			float[] ys = lm.y();
			for (int eye = 0; eye < 2; eye++) {
				for (int k = 0; k < 6; k++) {
					int point = EyeContactMath.EYE_POINTS[eye][k];
					eyes[i * GazeTrack.EYE_FLOATS + eye * 12 + k * 2] = xs[point];
					eyes[i * GazeTrack.EYE_FLOATS + eye * 12 + k * 2 + 1] = ys[point];
				}
				iris[i * 4 + eye * 2] = xs[EyeContactMath.IRIS_POINTS[eye]];
				iris[i * 4 + eye * 2 + 1] = ys[EyeContactMath.IRIS_POINTS[eye]];
			}
			roi[i * 3] = (float) square.x();
			roi[i * 3 + 1] = (float) square.y();
			roi[i * 3 + 2] = (float) square.side();
			double outer = xs[33];
			double inner = xs[263];
			double half = Math.max(1, Math.abs(inner - outer) / 2);
			yaw[i] = (float) ((xs[EyeContactMath.NOSE_TIP] - (outer + inner) / 2) / half);
			presence[i] = lm.presence();
			size++;
		}

		/** The slot for a new frame, or -1 for a timestamp that does not move forward. */
		private int next(double ptsSeconds) {
			if (!(ptsSeconds > lastPts)) {
				return -1;
			}
			lastPts = ptsSeconds;
			if (size == pts.length) {
				int grown = pts.length * 2;
				pts = java.util.Arrays.copyOf(pts, grown);
				valid = java.util.Arrays.copyOf(valid, grown);
				eyes = java.util.Arrays.copyOf(eyes, grown * GazeTrack.EYE_FLOATS);
				iris = java.util.Arrays.copyOf(iris, grown * 4);
				roi = java.util.Arrays.copyOf(roi, grown * 3);
				yaw = java.util.Arrays.copyOf(yaw, grown);
				presence = java.util.Arrays.copyOf(presence, grown);
			}
			pts[size] = ptsSeconds;
			valid[size] = false;
			return size;
		}

		String presenceRange() {
			double min = Double.POSITIVE_INFINITY;
			double max = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < size; i++) {
				if (valid[i]) {
					min = Math.min(min, presence[i]);
					max = Math.max(max, presence[i]);
				}
			}
			return String.format("%.2f..%.2f", min, max);
		}

		GazeTrack build(int width, int height) {
			int n = size;
			return new GazeTrack(width, height, java.util.Arrays.copyOf(pts, n),
					java.util.Arrays.copyOf(valid, n),
					java.util.Arrays.copyOf(eyes, n * GazeTrack.EYE_FLOATS),
					java.util.Arrays.copyOf(iris, n * 4), java.util.Arrays.copyOf(roi, n * 3),
					java.util.Arrays.copyOf(yaw, n), java.util.Arrays.copyOf(presence, n));
		}
	}
}
