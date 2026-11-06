package dev.gravaai.media.segmentation;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * The webcam background-effect segmenter the app wires in: opens the requested model on the first
 * mask request, so a project without an effect enabled pays nothing (no ONNX Runtime load, no model
 * session) and the 25 MB MODNet model is never opened while the fast path is enough.
 *
 * <p>Two instances exist per consumer when both paths are reachable (the preview while playing and
 * after pause); one {@link LazyWebcamSegmenter} is dedicated to each model and remembers its own
 * failure, so a missing MODNet only disables the precise path and the preview keeps working on the
 * fast one. When the native payload or a model is missing — a development checkout where
 * {@code java/native/build-onnxruntime.sh} has not run — the failure is logged once and every mask
 * request for that quality answers {@code null}, which the compositor renders as an untouched
 * webcam instead of failing the preview or the export.</p>
 *
 * <p>Single-caller like the segmenter it wraps: one instance per consumer (the editor preview's
 * frame path, each export run, each agent frame renderer).</p>
 */
public final class LazyWebcamSegmenter implements WebcamSegmenter {

	private static final System.Logger LOG = System.getLogger(LazyWebcamSegmenter.class.getName());

	private final WebcamMaskQuality quality;
	private @Nullable OnnxMaskSegmenter segmenter;
	private boolean unavailable;

	/** A lazy segmenter for the precise (slow) path: exports and still frames. */
	public LazyWebcamSegmenter() {
		this(WebcamMaskQuality.PRECISE);
	}

	public LazyWebcamSegmenter(WebcamMaskQuality quality) {
		this.quality = quality;
	}

	@Override
	public @Nullable RgbaFrame maskFor(RgbaFrame frame, MaskTuning tuning) {
		OnnxMaskSegmenter active = segmenter();
		return active == null ? null : active.maskFor(frame, tuning);
	}

	/** The model quality this instance opens. */
	public WebcamMaskQuality quality() {
		return quality;
	}

	/** The open segmenter, opening it on first use; {@code null} once opening failed. */
	private @Nullable OnnxMaskSegmenter segmenter() {
		if (unavailable) {
			return null;
		}
		if (segmenter == null) {
			try {
				segmenter = switch (quality) {
					case FAST -> SelfieSegmenter.openBundled();
					case PRECISE -> ModnetSegmenter.openBundled();
				};
			} catch (RuntimeException | UnsatisfiedLinkError error) {
				unavailable = true;
				LOG.log(System.Logger.Level.WARNING, "webcam background effect " + quality
						+ " path unavailable (" + error.getMessage()
						+ "): run java/native/build-onnxruntime.sh");
				return null;
			}
		}
		return segmenter;
	}

	/** True once a mask request opened the model (tests/diagnostics). */
	public boolean opened() {
		return segmenter != null;
	}

	@Override
	public void close() {
		OnnxMaskSegmenter open = segmenter;
		segmenter = null;
		if (open != null) {
			open.close();
		}
	}
}
