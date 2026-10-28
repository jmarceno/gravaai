package dev.gravaai.media.export;

import java.nio.file.Path;
import java.util.List;

/**
 * Audio side of an export: the input files and one {@code -filter_complex} graph that must produce
 * the {@code [aout]} label (plan §5.10, "one libavfilter graph string").
 */
public record ExportAudioPlan(List<Path> inputs, String filterComplex) {

	public static final ExportAudioPlan NONE = new ExportAudioPlan(List.of(), null);

	public ExportAudioPlan {
		inputs = inputs == null ? List.of() : List.copyOf(inputs);
		if (inputs.isEmpty()) {
			filterComplex = null;
		} else if (filterComplex == null || filterComplex.isBlank()
				|| !filterComplex.contains("[aout]")) {
			throw new IllegalArgumentException(
					"audio filter graph must be non-blank and end in an [aout] label");
		}
	}

	public boolean hasAudio() {
		return !inputs.isEmpty() && filterComplex != null;
	}

	/** The ffmpeg input arguments for this plan, in graph input order. */
	public List<String> inputArguments() {
		List<String> arguments = new java.util.ArrayList<>();
		for (Path input : inputs) {
			arguments.add("-i");
			arguments.add(input.toString());
		}
		return List.copyOf(arguments);
	}
}
