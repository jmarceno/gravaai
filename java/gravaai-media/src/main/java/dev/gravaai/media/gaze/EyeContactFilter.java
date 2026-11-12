package dev.gravaai.media.gaze;

import java.util.Optional;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.compose.WebcamFrameFilter;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * Eye contact as a {@link WebcamFrameFilter}: looks the frame up in the recording's
 * {@link GazeTrack} and corrects it with an {@link EyeContactCorrector} opened on first use, on the
 * calling thread (the preview's decode worker or the export thread).
 *
 * <p>Frames pass through untouched while the track is not analysed yet, where the timeline asks
 * for no correction, and for good once the models fail to open (logged once).</p>
 */
public final class EyeContactFilter implements WebcamFrameFilter {

	private static final System.Logger LOG = System.getLogger(EyeContactFilter.class.getName());

	private final Supplier<Optional<GazeTrack>> track;
	private final DoubleUnaryOperator angleAtTimelineSeconds;
	private final Supplier<OnnxWebGpuPlugin.Selection> selection;
	private Optional<EyeContactCorrector> corrector = Optional.empty();
	private boolean failed;

	/**
	 * @param track                  the recording's analysis, empty until it is ready
	 * @param angleAtTimelineSeconds the lift in degrees at a timeline time, 0 for none
	 * @param selection              the ONNX provider, probed when the models first open
	 */
	public EyeContactFilter(Supplier<Optional<GazeTrack>> track,
			DoubleUnaryOperator angleAtTimelineSeconds,
			Supplier<OnnxWebGpuPlugin.Selection> selection) {
		this.track = track;
		this.angleAtTimelineSeconds = angleAtTimelineSeconds;
		this.selection = selection;
	}

	@Override
	public void apply(RgbaFrame frame, double webcamSeconds, double timelineSeconds) {
		if (failed) {
			return;
		}
		double angle = angleAtTimelineSeconds.applyAsDouble(timelineSeconds);
		if (!(angle > 0)) {
			return;
		}
		Optional<GazeTrack> analysed = track.get();
		if (analysed.isEmpty()) {
			return;
		}
		GazeTrack known = analysed.get();
		Optional<GazeTrack.Sample> sample = known.sampleAt(webcamSeconds).map(found -> found.scaled(
				(double) frame.width() / known.frameWidth(),
				(double) frame.height() / known.frameHeight()));
		if (sample.isEmpty()) {
			return;
		}
		try {
			if (corrector.isEmpty()) {
				EyeContactCorrector opened = new EyeContactCorrector(selection.get());
				LOG.log(System.Logger.Level.INFO, "eye contact on " + opened.providerSummary());
				corrector = Optional.of(opened);
			}
			corrector.get().correct(frame, sample.get(), angle);
		} catch (RuntimeException error) {
			failed = true;
			LOG.log(System.Logger.Level.WARNING, "eye contact disabled: " + error);
			close();
		}
	}

	@Override
	public void close() {
		corrector.ifPresent(EyeContactCorrector::close);
		corrector = Optional.empty();
	}
}
