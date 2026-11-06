package dev.gravaai.media.segmentation;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * The webcam background-blur segmenter the app wires in: opens the bundled
 * {@link SelfieSegmenter} on the first mask request, so a project without the blur enabled pays
 * nothing (no ONNX Runtime load, no model session). When the native payload or the model is
 * missing — a development checkout where {@code java/native/build-onnxruntime.sh} has not run —
 * the failure is logged once and every mask request answers {@code null}, which the compositor
 * renders as an unblurred webcam instead of failing the preview or the export.
 *
 * <p>Single-caller like the segmenter it wraps: one instance per consumer (the editor preview's
 * frame path, each export run, each agent frame renderer).</p>
 */
public final class LazyWebcamSegmenter implements WebcamSegmenter {

	private static final System.Logger LOG = System.getLogger(LazyWebcamSegmenter.class.getName());

	private @Nullable SelfieSegmenter segmenter;
	private boolean unavailable;

	@Override
	public @Nullable RgbaFrame maskFor(RgbaFrame frame, MaskTuning tuning) {
		SelfieSegmenter active = segmenter();
		return active == null ? null : active.maskFor(frame, tuning);
	}

	/** The open segmenter, opening it on first use; {@code null} once opening failed. */
	private @Nullable SelfieSegmenter segmenter() {
		if (unavailable) {
			return null;
		}
		if (segmenter == null) {
			try {
				segmenter = SelfieSegmenter.openBundled();
			} catch (RuntimeException | UnsatisfiedLinkError error) {
				unavailable = true;
				LOG.log(System.Logger.Level.WARNING, "webcam background blur unavailable ("
						+ error.getMessage() + "): run java/native/build-onnxruntime.sh");
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
		SelfieSegmenter open = segmenter;
		segmenter = null;
		if (open != null) {
			open.close();
		}
	}

}
