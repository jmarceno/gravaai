package dev.gravaai.media.export;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.core.project.ClipTransitions;
import dev.gravaai.media.audio.AtempoChain;
import dev.gravaai.media.audio.AudioCleanupFilters;

/**
 * Builds the single libavfilter graph of an export (plan §5.10): per-source timeline segments with
 * trims/speed ({@code atrim}/{@code atempo}), the per-track edit-time cleanup
 * ({@link AudioCleanupFilters}), audio regions ({@code adelay}/{@code volume}), the
 * {@code amix normalize=0} mix, the programme loudness stage, the fade-black gain envelope and the
 * final 48 kHz stereo format.
 *
 * <p>Graph input convention used by {@link Mp4Encoder}: input 0 is the raw RGBA video stream, so
 * every audio file used here starts at input index 1. The builder de-duplicates paths and returns
 * the ffmpeg inputs in that order.
 */
public final class ExportAudioGraph {

	/**
	 * One full-length timeline-bound audio track (system/mic/mixed/embedded).
	 *
	 * @param cleanup the track's edit-time cleanup; {@link AudioCleanup#NONE} leaves it untouched
	 */
	public record TrackInput(String audioPath, double gain, AudioCleanup cleanup) {

		/** A track without cleanup: the shape every pre-cleanup call site has. */
		public TrackInput(String audioPath, double gain) {
			this(audioPath, gain, AudioCleanup.NONE);
		}

		public TrackInput {
			cleanup = cleanup == null ? AudioCleanup.NONE : cleanup;
		}
	}

	/** One additional audio region placed at {@code startMs} on the output timeline. */
	public record RegionInput(String audioPath, double startMs, double endMs, double gain) {}

	/** An output-timeline range (ms) where the recording's own audio is silent: a muted clip. */
	public record MutedRange(double startMs, double endMs) {}

	/**
	 * Everything the graph needs.
	 *
	 * @param loudnessTargetLufs the EBU R128 programme target for the mix, {@code 0} when off
	 * @param rnnoiseModelPath the RNNoise model for the cleanup stages, or null when this build
	 *     lacks it (the heavier noise-reduction levels then fall back to the FFT denoiser)
	 * @param measuredLoudness the measurement pass of the programme, or null for the single-pass
	 *     dynamic loudness stage ({@code ExportAudioLoudness.measure})
	 */
	public record GraphInput(List<TrackInput> tracks, List<RegionInput> regions,
			List<ExportAudioSegments.Segment> sourceSegments,
			List<ClipTransitions.FadeWindow> fadeWindows, double outputDurationMs,
			double masterGain, List<MutedRange> mutedRanges, double loudnessTargetLufs,
			@Nullable String rnnoiseModelPath,
			AudioCleanupFilters.@Nullable MeasuredLoudness measuredLoudness) {
		public GraphInput {
			tracks = tracks == null ? List.of() : List.copyOf(tracks);
			regions = regions == null ? List.of() : List.copyOf(regions);
			sourceSegments = sourceSegments == null ? List.of() : List.copyOf(sourceSegments);
			fadeWindows = fadeWindows == null ? List.of() : List.copyOf(fadeWindows);
			mutedRanges = mutedRanges == null ? List.of() : List.copyOf(mutedRanges);
		}

		/** A copy of this input with the measurement pass applied to its loudness stage. */
		public GraphInput withMeasured(AudioCleanupFilters.MeasuredLoudness measured) {
			return new GraphInput(tracks, regions, sourceSegments, fadeWindows, outputDurationMs,
					masterGain, mutedRanges, loudnessTargetLufs, rnnoiseModelPath, measured);
		}

		public GraphInput(List<TrackInput> tracks, List<RegionInput> regions,
				List<ExportAudioSegments.Segment> sourceSegments,
				List<ClipTransitions.FadeWindow> fadeWindows, double outputDurationMs,
				double masterGain, List<MutedRange> mutedRanges, double loudnessTargetLufs,
				@Nullable String rnnoiseModelPath) {
			this(tracks, regions, sourceSegments, fadeWindows, outputDurationMs, masterGain,
					mutedRanges, loudnessTargetLufs, rnnoiseModelPath, null);
		}

		public GraphInput(List<TrackInput> tracks, List<RegionInput> regions,
				List<ExportAudioSegments.Segment> sourceSegments,
				List<ClipTransitions.FadeWindow> fadeWindows, double outputDurationMs,
				double masterGain, List<MutedRange> mutedRanges) {
			this(tracks, regions, sourceSegments, fadeWindows, outputDurationMs, masterGain,
					mutedRanges, 0, null, null);
		}

		public GraphInput(List<TrackInput> tracks, List<RegionInput> regions,
				List<ExportAudioSegments.Segment> sourceSegments,
				List<ClipTransitions.FadeWindow> fadeWindows, double outputDurationMs,
				double masterGain) {
			this(tracks, regions, sourceSegments, fadeWindows, outputDurationMs, masterGain,
					List.of(), 0, null, null);
		}
	}

	private static final int SAMPLE_RATE = 48_000;

	private ExportAudioGraph() {}

	/** Builds the audio plan, or {@link ExportAudioPlan#NONE} when there is no audio to mix. */
	public static ExportAudioPlan build(GraphInput input) {
		if (input.tracks().isEmpty() && input.regions().isEmpty()) {
			return ExportAudioPlan.NONE;
		}
		Map<String, Integer> inputIndexByPath = new LinkedHashMap<>();
		List<Path> inputs = new ArrayList<>();
		List<String> parts = new ArrayList<>();
		List<String> mixLabels = new ArrayList<>();
		double durationSeconds = (input.outputDurationMs() <= 0 ? 0 : input.outputDurationMs())
				/ 1000.0;
		String duration = seconds(input.outputDurationMs());

		for (TrackInput track : input.tracks()) {
			int inputIndex = inputIndex(inputIndexByPath, inputs, track.audioPath());
			String label = "track" + mixLabels.size();
			parts.add(buildTrackChain(inputIndex, label, input.sourceSegments(), duration, track,
					input.mutedRanges(), input.rnnoiseModelPath()));
			mixLabels.add("[" + label + "]");
		}
		for (RegionInput region : input.regions()) {
			int inputIndex = inputIndex(inputIndexByPath, inputs, region.audioPath());
			String label = "region" + mixLabels.size();
			double regionDurationSec = Math.max(0, region.endMs() - region.startMs()) / 1000.0;
			long delayMs = Math.max(0, Math.round(region.startMs()));
			StringBuilder chain = new StringBuilder();
			chain.append('[').append(inputIndex).append(":a]")
					.append("atrim=duration=").append(seconds(regionDurationSec * 1000))
					.append(",asetpts=PTS-STARTPTS")
					// ":all=1" delays every channel: the recording mixer's form, so a mono
					// region (the click bus) lands on both channels.
					.append(",adelay=").append(delayMs).append(":all=1");
			if (needsVolume(region.gain())) {
				chain.append(",volume=").append(number(region.gain()));
			}
			chain.append('[').append(label).append(']');
			parts.add(chain.toString());
			mixLabels.add("[" + label + "]");
		}
		String durationBound = "atrim=duration=" + duration + ",asetpts=PTS-STARTPTS";
		StringBuilder finalChain = new StringBuilder();
		finalChain.append('[').append("mix").append(']');
		finalChain.append(durationBound);
		if (needsVolume(input.masterGain())) {
			finalChain.append(",volume=").append(number(input.masterGain()));
		}
		// The programme loudness stage sits on the mix, after the master gain it corrects for and
		// before the fade envelope: loudnorm measures what the viewer hears, so the fade must not
		// be inside its measurement window. With a measurement pass it is one linear gain; without
		// one (the preview's per-segment form) it is the dynamic approximation.
		String loudnorm = AudioCleanupFilters.loudnormFilter(input.loudnessTargetLufs(),
				input.measuredLoudness());
		if (loudnorm != null) {
			finalChain.append(',').append(loudnorm);
		}
		if (!input.fadeWindows().isEmpty()) {
			finalChain.append(",volume=eval=frame:volume='").append(fadeGainExpression(input))
					.append('\'');
		}
		finalChain.append(",aresample=").append(SAMPLE_RATE)
				.append(",aformat=sample_fmts=fltp:channel_layouts=stereo[aout]");
		parts.add(mixLabels.stream().reduce("", String::concat)
				+ "amix=inputs=" + mixLabels.size() + ":duration=longest:normalize=0[mix]");
		parts.add(finalChain.toString());
		return new ExportAudioPlan(inputs, String.join(";", parts));
	}

	private static String buildTrackChain(int inputIndex, String label,
			List<ExportAudioSegments.Segment> segments, String duration, TrackInput track,
			List<MutedRange> muted, @Nullable String rnnoiseModelPath) {
		// offlineAudioProcessor mutedSourceOutputRangesSec: the recording's audio (not imported
		// regions) is silent over muted clips, on the output timeline.
		String gainSuffix = (needsVolume(track.gain()) ? ",volume=" + number(track.gain()) : "")
				+ muteSuffix(muted);
		// The cleanup runs on the recording as it was captured: before the clip speed (a denoiser
		// fed sped-up speech hears something it was never trained on) and before the gain and
		// mute, which are programme decisions that must not change how much noise is removed.
		AudioCleanup cleanup = track.cleanup();
		List<String> highPass = cleanup.hasHighPass()
				? List.of("highpass=f=" + cleanup.highPassHz()) : List.of();
		List<String> noiseReduction =
				AudioCleanupFilters.noiseReductionFilters(cleanup, rnnoiseModelPath);
		int delaySamples = AudioCleanupFilters.delaySamples(cleanup, rnnoiseModelPath);
		StringBuilder builder = new StringBuilder();
		if (segments.isEmpty()) {
			builder.append('[').append(inputIndex).append(":a]")
					.append("asetpts=PTS-STARTPTS")
					.append(joined(AudioCleanupFilters.alignDelay(highPass, noiseReduction,
							delaySamples)))
					.append(",apad,atrim=duration=").append(duration)
					.append(",asetpts=PTS-STARTPTS").append(gainSuffix);
			return builder.append('[').append(label).append(']').toString();
		}
		List<String> segmentLabels = new ArrayList<>();
		for (int index = 0; index < segments.size(); index++) {
			ExportAudioSegments.Segment segment = segments.get(index);
			String segmentLabel = label + "_seg" + index;
			// A denoised segment is cut with a warm-up lead-in (the denoiser has converged by
			// the time the cut starts) and a tail as long as its latency; the lead-in and the
			// latency are dropped again afterwards, so the segment keeps its exact length and
			// position however many cuts the timeline has.
			double leadInMs = noiseReduction.isEmpty() ? 0
					: Math.min(DENOISE_LEAD_IN_MS, Math.max(0, segment.startMs()));
			double tailMs = delaySamples * 1000.0 / AudioCleanupFilters.NOISE_REDUCTION_SAMPLE_RATE;
			int dropSamples = delaySamples + (int) Math.round(
					leadInMs * AudioCleanupFilters.NOISE_REDUCTION_SAMPLE_RATE / 1000.0);
			StringBuilder chain = new StringBuilder();
			chain.append('[').append(inputIndex).append(":a]")
					.append("atrim=start=").append(seconds(segment.startMs() - leadInMs))
					.append(":end=").append(seconds(segment.endMs() + tailMs))
					.append(",asetpts=PTS-STARTPTS")
					.append(joined(AudioCleanupFilters.alignDelay(highPass, noiseReduction,
							dropSamples, Math.round(
									Math.max(0, segment.endMs() - segment.startMs())
											* AudioCleanupFilters.NOISE_REDUCTION_SAMPLE_RATE
											/ 1000.0))));
			String atempo = AtempoChain.filterChain(segment.speed());
			if (!atempo.isEmpty()) {
				chain.append(',').append(atempo);
			}
			chain.append('[').append(segmentLabel).append(']');
			builder.append(chain).append(';');
			segmentLabels.add("[" + segmentLabel + "]");
		}
		builder.append(segmentLabels.stream().reduce("", String::concat))
				.append("concat=n=").append(segmentLabels.size()).append(":v=0:a=1[")
				.append(label).append("_concat]")
				.append(';')
				.append('[').append(label).append("_concat]")
				.append("apad,atrim=duration=").append(duration).append(",asetpts=PTS-STARTPTS")
				.append(gainSuffix).append('[').append(label).append(']');
		return builder.toString();
	}

	/**
	 * How much audio before a cut a denoised segment decodes so the denoiser has settled when the
	 * cut starts. RNNoise converges within a few frames; half a second also covers the FFT
	 * denoiser's noise tracking.
	 */
	private static final double DENOISE_LEAD_IN_MS = 500;

	/** The filters as a chain suffix ({@code ",a,b"}), or an empty string when there are none. */
	private static String joined(List<String> filters) {
		return filters.isEmpty() ? "" : "," + String.join(",", filters);
	}

	private static String muteSuffix(List<MutedRange> muted) {
		List<String> terms = new ArrayList<>();
		for (MutedRange range : muted) {
			if (range.endMs() > range.startMs()) {
				terms.add("between(t," + number(Math.max(0, range.startMs()) / 1000.0) + ","
						+ number(range.endMs() / 1000.0) + ")");
			}
		}
		if (terms.isEmpty()) {
			return "";
		}
		return ",volume=eval=frame:volume='if(" + String.join("+", terms) + ",0,1)'";
	}

	private static int inputIndex(Map<String, Integer> indexByPath, List<Path> inputs, String path) {
		Integer existing = indexByPath.get(path);
		if (existing != null) {
			return existing;
		}
		int index = inputs.size() + 1; // input 0 is the raw RGBA video pipe
		indexByPath.put(path, index);
		inputs.add(Path.of(path));
		return index;
	}

	private static boolean needsVolume(double gain) {
		return Double.isFinite(gain) && Math.abs(gain - 1) > 0.0005;
	}

	private static String seconds(double milliseconds) {
		return String.format(Locale.ROOT, "%.3f", Math.max(0, milliseconds) / 1000.0);
	}

	private static String number(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return Long.toString((long) value);
		}
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private static String fadeGainExpression(GraphInput input) {
		List<String> amounts = new ArrayList<>();
		for (ClipTransitions.FadeWindow window : input.fadeWindows()) {
			amounts.add(fadeAmountExpression(window));
		}
		String maxAmount = amounts.size() == 1
				? amounts.get(0)
				: "max(" + String.join(",", amounts) + ")";
		return "1-" + maxAmount;
	}

	private static String fadeAmountExpression(ClipTransitions.FadeWindow window) {
		double start = window.startMs() / 1000.0;
		double peak = window.peakMs() / 1000.0;
		double holdEnd = ClipTransitions.getFadeWindowHoldEnd(window) / 1000.0;
		double end = window.endMs() / 1000.0;
		if (!(end > start) || !(peak >= start)) {
			return "0";
		}
		String inProgress = peak > start ? "((t-" + number(start) + ")/" + number(peak - start) + ")"
				: "1";
		String inAmount = smoothstep(clamp01(inProgress));
		String holdPhase = holdEnd > peak
				? "if(lt(t," + number(holdEnd) + "),1," + outPhase(holdEnd, end) + ")"
				: outPhase(peak, end);
		return "if(lt(t," + number(start) + "),0,"
				+ "if(lt(t," + number(peak) + ")," + inAmount + ","
				+ "if(lt(t," + number(end) + ")," + holdPhase + ",0)))";
	}

	private static String outPhase(double holdEnd, double end) {
		if (!(end > holdEnd)) {
			return "1";
		}
		String outProgress = "((" + number(end) + "-t)/" + number(end - holdEnd) + ")";
		return smoothstep(clamp01(outProgress));
	}

	private static String clamp01(String expression) {
		return "min(max(" + expression + ",0),1)";
	}

	private static String smoothstep(String progress) {
		String clamped = "min(max(" + progress + ",0),1)";
		return clamped + "*" + clamped + "*(3-2*" + clamped + ")";
	}
}
