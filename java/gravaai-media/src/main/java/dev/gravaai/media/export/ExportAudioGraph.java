package dev.gravaai.media.export;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.gravaai.core.project.ClipTransitions;
import dev.gravaai.media.audio.AtempoChain;

/**
 * Builds the single libavfilter graph of an export (plan §5.10): per-source timeline segments with
 * trims/speed ({@code atrim}/{@code atempo}), audio regions ({@code adelay}/{@code volume}), the
 * {@code amix normalize=0} mix, the fade-black gain envelope and the final 48 kHz stereo format.
 *
 * <p>Graph input convention used by {@link Mp4Encoder}: input 0 is the raw RGBA video stream, so
 * every audio file used here starts at input index 1. The builder de-duplicates paths and returns
 * the ffmpeg inputs in that order.
 */
public final class ExportAudioGraph {

	/** One full-length timeline-bound audio track (system/mic/mixed/embedded). */
	public record TrackInput(String audioPath, double gain) {}

	/** One additional audio region placed at {@code startMs} on the output timeline. */
	public record RegionInput(String audioPath, double startMs, double endMs, double gain) {}

	/** An output-timeline range (ms) where the recording's own audio is silent: a muted clip. */
	public record MutedRange(double startMs, double endMs) {}

	/** Everything the graph needs. */
	public record GraphInput(List<TrackInput> tracks, List<RegionInput> regions,
			List<ExportAudioSegments.Segment> sourceSegments,
			List<ClipTransitions.FadeWindow> fadeWindows, double outputDurationMs,
			double masterGain, List<MutedRange> mutedRanges) {
		public GraphInput {
			tracks = tracks == null ? List.of() : List.copyOf(tracks);
			regions = regions == null ? List.of() : List.copyOf(regions);
			sourceSegments = sourceSegments == null ? List.of() : List.copyOf(sourceSegments);
			fadeWindows = fadeWindows == null ? List.of() : List.copyOf(fadeWindows);
			mutedRanges = mutedRanges == null ? List.of() : List.copyOf(mutedRanges);
		}

		public GraphInput(List<TrackInput> tracks, List<RegionInput> regions,
				List<ExportAudioSegments.Segment> sourceSegments,
				List<ClipTransitions.FadeWindow> fadeWindows, double outputDurationMs,
				double masterGain) {
			this(tracks, regions, sourceSegments, fadeWindows, outputDurationMs, masterGain, List.of());
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
			parts.add(buildTrackChain(inputIndex, label, input.sourceSegments(), duration,
					track.gain(), input.mutedRanges()));
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
					.append(",adelay=").append(delayMs).append('|').append(delayMs);
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
			List<ExportAudioSegments.Segment> segments, String duration, double gain,
			List<MutedRange> muted) {
		// offlineAudioProcessor mutedSourceOutputRangesSec: the recording's audio (not imported
		// regions) is silent over muted clips, on the output timeline.
		String gainSuffix = (needsVolume(gain) ? ",volume=" + number(gain) : "") + muteSuffix(muted);
		StringBuilder builder = new StringBuilder();
		if (segments.isEmpty()) {
			builder.append('[').append(inputIndex).append(":a]")
					.append("asetpts=PTS-STARTPTS,apad,atrim=duration=").append(duration)
					.append(",asetpts=PTS-STARTPTS").append(gainSuffix);
			return builder.append('[').append(label).append(']').toString();
		}
		List<String> segmentLabels = new ArrayList<>();
		for (int index = 0; index < segments.size(); index++) {
			ExportAudioSegments.Segment segment = segments.get(index);
			String segmentLabel = label + "_seg" + index;
			StringBuilder chain = new StringBuilder();
			chain.append('[').append(inputIndex).append(":a]")
					.append("atrim=start=").append(seconds(segment.startMs()))
					.append(":end=").append(seconds(segment.endMs()))
					.append(",asetpts=PTS-STARTPTS");
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
