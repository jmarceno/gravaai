package dev.gravaai.media.gaze;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

/**
 * The eye-contact analysis of one webcam recording: per decoded frame, the six eye-contour points
 * of each eye (the layout the warp model was trained on), the iris centres, the face region the
 * glasses segmenter reads, and the signals that decide how much correction the frame gets.
 *
 * <p>Built once per recording by {@link GazeAnalyzer}, smoothed with a centred window (offline,
 * so there is no lag and the export sees exactly what the preview saw) and then reduced to one
 * {@link #weightAt correction weight} per frame. The weight fades the effect out on blinks, head
 * turns, low-confidence frames and deliberate look-aways (the iris far from where it usually sits
 * while reading), with short ramps so the correction never pops.</p>
 *
 * <p>Immutable after construction; safe to share between threads.</p>
 */
public final class GazeTrack {

	/** Floats of eye contour per frame: 2 eyes x 6 points x (x, y), left eye first. */
	public static final int EYE_FLOATS = 24;
	private static final int MAGIC = 0x475A5431; // "GZT1"
	private static final int VERSION = 1;

	/**
	 * Blink fade on lid gap over eye width: below the low openness the weight is 0, above the high
	 * it is 1. Kept low because reading the screen lowers the lids (about 0.2 on the RX 560 test
	 * recording) and that is exactly when the correction is wanted; a blink drops under 0.05.
	 */
	static final double BLINK_LOW = 0.09;
	static final double BLINK_HIGH = 0.15;
	/** Head turn fade on |nose offset| / half eye span. */
	static final double YAW_LOW = 0.32;
	static final double YAW_HIGH = 0.48;
	/** Look-away fade on the normalised iris distance from its usual position. */
	static final double AWAY_LOW = 1.0;
	static final double AWAY_HIGH = 1.5;
	/** Horizontal / vertical iris travel (fraction of eye width) that still counts as reading. */
	static final double AWAY_X = 0.20;
	static final double AWAY_Y = 0.12;
	/** Face-presence logit fade. */
	static final double PRESENCE_LOW = 0;
	static final double PRESENCE_HIGH = 4;
	/** Length of a fade in or out. */
	static final double RAMP_SECONDS = 0.12;

	private final int frameWidth;
	private final int frameHeight;
	private final double[] pts;
	private final boolean[] valid;
	private final float[] eyes;
	private final float[] iris;
	private final float[] roi;
	private final float[] yaw;
	private final float[] presence;
	private final float[] weight;
	/** The analyser's unsmoothed values, kept so a written track reads back identical. */
	private final float[] rawEyes;
	private final float[] rawIris;
	private final float[] rawRoi;

	/**
	 * @param pts      presentation time of each frame, seconds, ascending
	 * @param valid    whether a face was found in the frame
	 * @param eyes     {@link #EYE_FLOATS} per frame
	 * @param iris     4 per frame: left (x, y), right (x, y)
	 * @param roi      3 per frame: face square x, y, side
	 * @param yaw      head-turn ratio per frame (nose offset over half the eye span)
	 * @param presence face-presence logit per frame
	 */
	public GazeTrack(int frameWidth, int frameHeight, double[] pts, boolean[] valid, float[] eyes,
			float[] iris, float[] roi, float[] yaw, float[] presence) {
		int n = pts.length;
		if (valid.length != n || eyes.length != n * EYE_FLOATS || iris.length != n * 4
				|| roi.length != n * 3 || yaw.length != n || presence.length != n) {
			throw new IllegalArgumentException("gaze track arrays disagree on the frame count");
		}
		if (frameWidth <= 0 || frameHeight <= 0) {
			throw new IllegalArgumentException("invalid frame size " + frameWidth + "x"
					+ frameHeight);
		}
		this.frameWidth = frameWidth;
		this.frameHeight = frameHeight;
		this.pts = pts.clone();
		this.valid = valid.clone();
		this.eyes = eyes.clone();
		this.iris = iris.clone();
		this.roi = roi.clone();
		this.yaw = yaw.clone();
		this.presence = presence.clone();
		this.rawEyes = eyes.clone();
		this.rawIris = iris.clone();
		this.rawRoi = roi.clone();
		smooth(this.eyes, EYE_FLOATS);
		smooth(this.iris, 4);
		smooth(this.roi, 3);
		this.weight = weights();
	}

	public int frameWidth() {
		return frameWidth;
	}

	public int frameHeight() {
		return frameHeight;
	}

	/** Number of analysed frames. */
	public int size() {
		return pts.length;
	}

	/** Length of the analysed recording, seconds (the last frame's start). */
	public double durationSeconds() {
		return pts.length == 0 ? 0 : pts[pts.length - 1];
	}

	/** One frame of the track, smoothed. */
	public record Sample(int index, double ptsSeconds, float[] eyes, float[] iris, float[] roi,
			double weight) {

		/** The same sample on a frame scaled by (sx, sy) from the analysed one. */
		public Sample scaled(double sx, double sy) {
			if (sx == 1 && sy == 1) {
				return this;
			}
			float[] e = eyes.clone();
			for (int i = 0; i < e.length; i += 2) {
				e[i] *= (float) sx;
				e[i + 1] *= (float) sy;
			}
			float[] r = iris.clone();
			for (int i = 0; i < r.length; i += 2) {
				r[i] *= (float) sx;
				r[i + 1] *= (float) sy;
			}
			double s = Math.sqrt(sx * sy);
			float[] q = {(float) (roi[0] * sx), (float) (roi[1] * sy), (float) (roi[2] * s)};
			return new Sample(index, ptsSeconds, e, r, q, weight);
		}

		/** Contour point {@code k} (0..5) of {@code eye} (0 = left, 1 = right), x. */
		public float x(int eye, int k) {
			return eyes[eye * 12 + k * 2];
		}

		/** Contour point {@code k} (0..5) of {@code eye} (0 = left, 1 = right), y. */
		public float y(int eye, int k) {
			return eyes[eye * 12 + k * 2 + 1];
		}
	}

	/**
	 * The frame showing at {@code webcamSeconds} (the last frame starting at or before it, the
	 * same frame {@code MediaFrameReader.decodeForward} returns), or empty when no face was found
	 * there or the time is outside the track.
	 */
	public Optional<Sample> sampleAt(double webcamSeconds) {
		int index = indexAt(webcamSeconds);
		if (index < 0 || !valid[index]) {
			return Optional.empty();
		}
		return Optional.of(new Sample(index, pts[index],
				Arrays.copyOfRange(eyes, index * EYE_FLOATS, (index + 1) * EYE_FLOATS),
				Arrays.copyOfRange(iris, index * 4, index * 4 + 4),
				Arrays.copyOfRange(roi, index * 3, index * 3 + 3), weight[index]));
	}

	/** The correction weight (0..1) of the frame showing at {@code webcamSeconds}. */
	public double weightAt(double webcamSeconds) {
		int index = indexAt(webcamSeconds);
		return index < 0 ? 0 : weight[index];
	}

	private int indexAt(double seconds) {
		if (pts.length == 0 || !Double.isFinite(seconds)) {
			return -1;
		}
		// Half a millisecond of slack absorbs the microsecond rounding of the decoder.
		int found = Arrays.binarySearch(pts, seconds + 0.0005);
		int index = found >= 0 ? found : -found - 2;
		if (index < 0) {
			return seconds >= pts[0] - 0.05 ? 0 : -1;
		}
		double frame = index + 1 < pts.length ? pts[index + 1] - pts[index] : 0.1;
		return seconds - pts[index] <= Math.max(0.1, frame * 2) ? index : -1;
	}

	/** Gaussian (sigma 1.2 frames) over valid neighbours, per channel, never across a gap. */
	private void smooth(float[] data, int stride) {
		float[] source = data.clone();
		double[] kernel = {1, Math.exp(-1 / 2.88), Math.exp(-4 / 2.88), Math.exp(-9 / 2.88)};
		for (int i = 0; i < pts.length; i++) {
			if (!valid[i]) {
				continue;
			}
			for (int c = 0; c < stride; c++) {
				double sum = 0;
				double norm = 0;
				for (int d = -3; d <= 3; d++) {
					int j = i + d;
					if (j < 0 || j >= pts.length || !valid[j]) {
						continue;
					}
					// A neighbour after a gap belongs to a different take of the face.
					boolean contiguous = true;
					for (int k = Math.min(i, j); k < Math.max(i, j); k++) {
						if (!valid[k]) {
							contiguous = false;
							break;
						}
					}
					if (!contiguous) {
						continue;
					}
					double w = kernel[Math.abs(d)];
					sum += w * source[j * stride + c];
					norm += w;
				}
				data[i * stride + c] = (float) (sum / norm);
			}
		}
	}

	private float[] weights() {
		int n = pts.length;
		float[] raw = new float[n];
		double[] relX = new double[n];
		double[] relY = new double[n];
		int count = 0;
		double[] xs = new double[n];
		double[] ys = new double[n];
		for (int i = 0; i < n; i++) {
			if (!valid[i]) {
				continue;
			}
			double rx = 0;
			double ry = 0;
			for (int e = 0; e < 2; e++) {
				int base = i * EYE_FLOATS + e * 12;
				double cx = (eyes[base] + eyes[base + 6]) / 2;
				double cy = (eyes[base + 1] + eyes[base + 7]) / 2;
				double w = eyeWidth(i, e);
				rx += (iris[i * 4 + e * 2] - cx) / w / 2;
				ry += (iris[i * 4 + e * 2 + 1] - cy) / w / 2;
			}
			relX[i] = rx;
			relY[i] = ry;
			xs[count] = rx;
			ys[count] = ry;
			count++;
		}
		double medX = median(xs, count);
		double medY = median(ys, count);
		for (int i = 0; i < n; i++) {
			if (!valid[i]) {
				raw[i] = 0;
				continue;
			}
			double open = Math.min(openness(i, 0), openness(i, 1));
			double away = Math.hypot((relX[i] - medX) / AWAY_X, (relY[i] - medY) / AWAY_Y);
			double w = smoothstep(BLINK_LOW, BLINK_HIGH, open)
					* (1 - smoothstep(YAW_LOW, YAW_HIGH, Math.abs(yaw[i])))
					* (1 - smoothstep(AWAY_LOW, AWAY_HIGH, away))
					* smoothstep(PRESENCE_LOW, PRESENCE_HIGH, presence[i]);
			raw[i] = (float) w;
		}
		return ramp(raw);
	}

	/**
	 * Limits how fast the weight may change: each frame takes the minimum over its neighbours of
	 * (their weight + time distance / ramp), so a dip to 0 is reached gradually and left gradually
	 * over {@link #RAMP_SECONDS} on both sides.
	 */
	private float[] ramp(float[] raw) {
		int n = raw.length;
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			double best = raw[i];
			for (int j = i - 1; j >= 0 && pts[i] - pts[j] < RAMP_SECONDS; j--) {
				best = Math.min(best, raw[j] + (pts[i] - pts[j]) / RAMP_SECONDS);
			}
			for (int j = i + 1; j < n && pts[j] - pts[i] < RAMP_SECONDS; j++) {
				best = Math.min(best, raw[j] + (pts[j] - pts[i]) / RAMP_SECONDS);
			}
			out[i] = (float) Math.max(0, Math.min(1, best));
		}
		return out;
	}

	private double eyeWidth(int frame, int eye) {
		int base = frame * EYE_FLOATS + eye * 12;
		return Math.max(1e-3, Math.hypot(eyes[base] - eyes[base + 6],
				eyes[base + 1] - eyes[base + 7]));
	}

	/** Lid gap over eye width, from the two upper (1, 2) and two lower (4, 5) contour points. */
	private double openness(int frame, int eye) {
		int base = frame * EYE_FLOATS + eye * 12;
		double ux = (eyes[base + 2] + eyes[base + 4]) / 2;
		double uy = (eyes[base + 3] + eyes[base + 5]) / 2;
		double lx = (eyes[base + 8] + eyes[base + 10]) / 2;
		double ly = (eyes[base + 9] + eyes[base + 11]) / 2;
		return Math.hypot(ux - lx, uy - ly) / eyeWidth(frame, eye);
	}

	private static double median(double[] values, int count) {
		if (count == 0) {
			return 0;
		}
		double[] sorted = Arrays.copyOf(values, count);
		Arrays.sort(sorted);
		return sorted[count / 2];
	}

	static double smoothstep(double low, double high, double x) {
		double t = Math.max(0, Math.min(1, (x - low) / (high - low)));
		return t * t * (3 - 2 * t);
	}

	/** Writes the track (its unsmoothed inputs) atomically; {@link #read} rebuilds it exactly. */
	public void write(Path file) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		if (dir != null) {
			Files.createDirectories(dir);
		}
		Path part = file.resolveSibling(file.getFileName() + ".part");
		try (DataOutputStream out = new DataOutputStream(
				new BufferedOutputStream(Files.newOutputStream(part)))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			out.writeInt(frameWidth);
			out.writeInt(frameHeight);
			out.writeInt(pts.length);
			for (int i = 0; i < pts.length; i++) {
				out.writeDouble(pts[i]);
				out.writeBoolean(valid[i]);
				for (int k = 0; k < EYE_FLOATS; k++) {
					out.writeFloat(rawEyes[i * EYE_FLOATS + k]);
				}
				for (int k = 0; k < 4; k++) {
					out.writeFloat(rawIris[i * 4 + k]);
				}
				for (int k = 0; k < 3; k++) {
					out.writeFloat(rawRoi[i * 3 + k]);
				}
				out.writeFloat(yaw[i]);
				out.writeFloat(presence[i]);
			}
		}
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
	}

	/** Reads a track written by {@link #write}. */
	public static GazeTrack read(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC || in.readInt() != VERSION) {
				throw new IOException("not a gaze track: " + file);
			}
			int width = in.readInt();
			int height = in.readInt();
			int n = in.readInt();
			if (n < 0 || n > 10_000_000) {
				throw new IOException("corrupt gaze track frame count " + n);
			}
			double[] pts = new double[n];
			boolean[] valid = new boolean[n];
			float[] eyes = new float[n * EYE_FLOATS];
			float[] iris = new float[n * 4];
			float[] roi = new float[n * 3];
			float[] yaw = new float[n];
			float[] presence = new float[n];
			for (int i = 0; i < n; i++) {
				pts[i] = in.readDouble();
				valid[i] = in.readBoolean();
				for (int k = 0; k < EYE_FLOATS; k++) {
					eyes[i * EYE_FLOATS + k] = in.readFloat();
				}
				for (int k = 0; k < 4; k++) {
					iris[i * 4 + k] = in.readFloat();
				}
				for (int k = 0; k < 3; k++) {
					roi[i * 3 + k] = in.readFloat();
				}
				yaw[i] = in.readFloat();
				presence[i] = in.readFloat();
			}
			return new GazeTrack(width, height, pts, valid, eyes, iris, roi, yaw, presence);
		}
	}
}
