package dev.gravaai.services.agent;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.ClipRegion;

/** Test helper: the core's {@code removeSourceRangesFromClips} with an id generator. */
final class AgentTranscriptTestSupport {

	private AgentTranscriptTestSupport() {}

	static List<ClipRegion> removeSourceRanges(List<ClipRegion> clips,
			List<AgentTranscript.Range> ranges) {
		AtomicInteger counter = new AtomicInteger();
		List<TranscriptCut.SourceRange> coreRanges = ranges.stream()
				.map(range -> new TranscriptCut.SourceRange(range.startMs(), range.endMs()))
				.toList();
		return TranscriptCut.removeSourceRangesFromClips(clips, coreRanges,
				() -> "new" + (counter.getAndIncrement() == 0 ? "" : counter.get()));
	}
}
