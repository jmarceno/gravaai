package dev.gravaai.services.agent;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import dev.gravaai.core.project.ClipSpanChanges;
import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.core.project.AnnotationTextStyle;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.core.project.ArrowDirection;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.AutoCaptionAnimation;
import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionOps;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipSequence;
import dev.gravaai.core.project.ClipSplits;
import dev.gravaai.core.project.ClipTransition;
import dev.gravaai.core.project.ClipTransitionKind;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.FigureData;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomFocus;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;
import dev.gravaai.services.agent.AgentOpReader.Geometry;
import dev.gravaai.services.agent.AgentOpReader.OpError;
import dev.gravaai.services.agent.AgentOpReader.StyleInput;
import dev.gravaai.services.agent.AgentOpReader.TransitionInput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pure planner for the agent {@code edit} tool (port of
 * {@code src/components/video-editor/agent/applyAgentOps.ts}, plan §5.16).
 *
 * <p>Applies a batch of validated ops to an immutable {@link ProjectEditorState} and returns the new
 * state plus a one-line summary and transient timeline highlights; any failure leaves the input
 * state untouched and names the failing op index. The app's EditorPort executes the resulting state
 * (through its core {@code EditorStore} commands).
 */
public final class AgentPlanner {

	/** What the pure core cannot know: source length, cursor telemetry, id counters. */
	public record Context(double sourceDurationMs, List<AgentAutoZoom.Sample> cursorTelemetry,
			Function<IdKind, String> createId) {
		public Context {
			cursorTelemetry = cursorTelemetry == null ? List.of() : List.copyOf(cursorTelemetry);
			if (createId == null) {
				throw new IllegalArgumentException("createId is required");
			}
		}

		public static Context of(double sourceDurationMs) {
			return new Context(sourceDurationMs, List.of(), new SequentialIds());
		}
	}

	/** Region kinds that can be created by an op; each has its own id counter. */
	public enum IdKind {
		ZOOM("zoom"), CLIP("clip"), ANNOTATION("annotation"), AUDIO("audio"), SPEED("speed");

		private final String wire;

		IdKind(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	/** Default id factory: {@code zoom-1}, {@code clip-2}, … (tests override it). */
	public static final class SequentialIds implements Function<IdKind, String> {
		private final Map<IdKind, Integer> counters = new LinkedHashMap<>();

		@Override
		public String apply(IdKind kind) {
			int next = counters.merge(kind, 1, Integer::sum);
			return kind.wire() + "-" + next;
		}
	}

	/** One transient timeline highlight. */
	public record Highlight(Kind kind, long startMs, long endMs, String label) {
		public enum Kind {
			CUT("cut"),
			ADDED("added"),
			CHANGED("changed");

			private final String wire;

			Kind(String wire) {
				this.wire = wire;
			}

			public String wire() {
				return wire;
			}
		}
	}

	/** Result of one batch. */
	public sealed interface Result permits Ok, Failure {}

	/** Successful batch. */
	public record Ok(ProjectEditorState state, String summary, List<Highlight> highlights)
			implements Result {
		public Ok {
			highlights = List.copyOf(highlights);
		}
	}

	/** Failed batch: the input state is untouched. */
	public record Failure(@Nullable String error, int opIndex) implements Result {}

	private static final Set<String> HISTORY_OPS = Set.of("undo", "redo", "revert_ai");
	private static final Set<String> ARRAY_SETTING_KEYS = Set.of("zoomRegions", "trimRegions",
			"clipRegions", "speedRegions", "annotationRegions", "audioRegions", "autoCaptions",
			"sourceAudioTrackSettingsByClip", "defaultSourceAudioTrackSettings");

	/** Applies a batch of raw ops; the returned failure carries the first failing op index. */
	public static Result apply(ProjectEditorState state, JsonNode opsInput, Context context) {
		if (opsInput == null || !opsInput.isArray() || opsInput.isEmpty()) {
			return new Failure("edit needs \"ops\": a non-empty array", -1);
		}
		List<JsonNode> historyOps = new ArrayList<>();
		for (int index = 0; index < opsInput.size(); index++) {
			JsonNode op = opsInput.get(index);
			if (op != null && op.isObject() && op.path("op").isString()
					&& HISTORY_OPS.contains(op.path("op").asString())) {
				historyOps.add(op);
			}
		}
		if (!historyOps.isEmpty()) {
			int index = indexOf(opsInput, historyOps.get(0));
			if (opsInput.size() > 1) {
				return new Failure("undo/redo/revert_ai cannot be mixed with other ops;"
						+ " send them alone", index);
			}
			return new Failure(historyOps.get(0).path("op").asString()
					+ " is applied by the editor, not by the op core", index);
		}
		Draft draft = new Draft(state, context);
		for (int index = 0; index < opsInput.size(); index++) {
			try {
				ObjectNode raw = AgentOpReader.readRawOp(opsInput.get(index), index);
				applyOne(draft, raw, index);
			} catch (OpError error) {
				return new Failure(error.getMessage(), index);
			} catch (RuntimeException error) {
				return new Failure(error.getMessage() == null ? error.toString()
						: error.getMessage(), index);
			}
		}
		return new Ok(draft.state, resolveShortRefs(String.join(", ", draft.summary), draft.state),
				finishHighlights(draft.highlights));
	}

	private static int indexOf(JsonNode ops, JsonNode target) {
		for (int index = 0; index < ops.size(); index++) {
			if (ops.get(index) == target) {
				return index;
			}
		}
		return 0;
	}

	private static List<Highlight> finishHighlights(List<Highlight> highlights) {
		List<Highlight> result = new ArrayList<>();
		for (int index = 0; index < highlights.size(); index++) {
			Highlight highlight = highlights.get(index);
			result.add(new Highlight(highlight.kind(), Math.max(0, Math.round(highlight.startMs())),
					Math.round(highlight.endMs()), highlight.label()));
		}
		return result;
	}

	// --- draft state -------------------------------------------------------------------------

	private static final class Draft {
		ProjectEditorState state;
		final List<String> summary = new ArrayList<>();
		final List<Highlight> highlights = new ArrayList<>();
		final Context ctx;
		final AgentIds ids;
		final Map<String, AgentTranscript.Word> wordByKey = new LinkedHashMap<>();
		final List<AgentTranscript.Word> words;

		Draft(ProjectEditorState state, Context ctx) {
			this.state = state;
			this.ctx = ctx;
			this.ids = AgentIds.of(state);
			this.words = ids.words();
			for (AgentTranscript.Word word : words) {
				wordByKey.put(word.key(), word);
			}
		}
	}

	private record Span(long startMs, long endMs) {}

	private static void push(Draft draft, Highlight.Kind kind, long startMs, long endMs,
			String label) {
		draft.highlights.add(new Highlight(kind, startMs, endMs, label));
	}

	/** Builds the validation error; call sites always {@code throw} it. */
	private static OpError fail(String message) {
		return new OpError(message);
	}

	private static long toMs(double seconds) {
		return Math.round(seconds * 1000);
	}

	private static double speedOf(ClipRegion clip) {
		return Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
	}

	private static long clipsEndMs(List<ClipRegion> clips) {
		long end = 0;
		for (ClipRegion clip : clips) {
			end = Math.max(end, Math.round(clip.endMs()));
		}
		return end;
	}

	private static long clipsSourceEndMs(List<ClipRegion> clips, long fallback) {
		long end = fallback;
		for (ClipRegion clip : clips) {
			end = Math.max(end, Clips.getClipSourceEndMs(clip));
		}
		return end;
	}

	private static long timelineDurationMs(Draft draft) {
		long end = clipsEndMs(draft.state.clipRegions());
		return end > 0 ? end : Math.max(0, Math.round(draft.ctx.sourceDurationMs()));
	}

	private static long sourceDurationMs(Draft draft) {
		long end = clipsSourceEndMs(draft.state.clipRegions(), 0);
		return Math.max(end, Math.max(0, Math.round(draft.ctx.sourceDurationMs())));
	}

	private static long toSourceMs(long timeMs, ClipRegion clip) {
		return Math.round(Clips.getClipSourceStartMs(clip)
				+ (Math.max(clip.startMs(), timeMs) - clip.startMs()) * speedOf(clip));
	}

	private static @Nullable ClipRegion clipForSource(long sourceMs, List<ClipRegion> clips, boolean startEdge) {
		for (ClipRegion clip : clips) {
			long start = Clips.getClipSourceStartMs(clip);
			long end = Clips.getClipSourceEndMs(clip);
			if (startEdge ? sourceMs >= start && sourceMs < end : sourceMs > start && sourceMs <= end) {
				return clip;
			}
		}
		return null;
	}

	private static long sourceToTimelineMs(long sourceMs, ClipRegion clip) {
		return Math.round(clip.startMs() + (sourceMs - Clips.getClipSourceStartMs(clip))
				/ speedOf(clip));
	}

	private static @Nullable Span mapSourcePiece(long startSourceMs, long endSourceMs,
			List<ClipRegion> after) {
		ClipRegion startClip = clipForSource(startSourceMs, after, true);
		ClipRegion endClip = clipForSource(endSourceMs, after, false);
		if (startClip == null) {
			if (endClip == null) {
				return null;
			}
			return spanBetween(endClip.startMs(), sourceToTimelineMs(endSourceMs, endClip));
		}
		long startMs = sourceToTimelineMs(startSourceMs, startClip);
		long endMs = endClip == null ? startClip.endMs()
				: sourceToTimelineMs(endSourceMs, endClip);
		return spanBetween(startMs, endMs);
	}

	private static @Nullable Span spanBetween(long firstMs, long secondMs) {
		long lo = Math.min(firstMs, secondMs);
		long hi = Math.max(firstMs, secondMs);
		return hi > lo ? new Span(lo, hi) : null;
	}

	private static List<ZoomRegion> remapZooms(List<ZoomRegion> regions, List<ClipRegion> before,
			List<ClipRegion> after) {
		if (before.isEmpty()) {
			return regions;
		}
		List<ZoomRegion> next = new ArrayList<>();
		for (ZoomRegion region : regions) {
			List<Span> pieces = new ArrayList<>();
			for (ClipRegion clip : before) {
				long startMs = Math.max(region.startMs(), clip.startMs());
				long endMs = Math.min(region.endMs(), clip.endMs());
				if (endMs <= startMs) {
					continue;
				}
				Span piece = mapSourcePiece(toSourceMs(startMs, clip), toSourceMs(endMs, clip),
						after);
				if (piece != null) {
					pieces.add(piece);
				}
			}
			if (pieces.isEmpty()) {
				continue;
			}
			next.add(new ZoomRegion(region.id(), pieces.stream().mapToLong(Span::startMs).min()
					.orElse(region.startMs()), pieces.stream().mapToLong(Span::endMs).max()
							.orElse(region.endMs()), region.depth(), region.focus(), region.mode()));
		}
		return List.copyOf(next);
	}

	private static List<SpeedRegion> remapSpeeds(List<SpeedRegion> regions, List<ClipRegion> before,
			List<ClipRegion> after) {
		if (before.isEmpty()) {
			return regions;
		}
		List<SpeedRegion> next = new ArrayList<>();
		for (SpeedRegion region : regions) {
			List<Span> pieces = new ArrayList<>();
			for (ClipRegion clip : before) {
				long startMs = Math.max(region.startMs(), clip.startMs());
				long endMs = Math.min(region.endMs(), clip.endMs());
				if (endMs <= startMs) {
					continue;
				}
				Span piece = mapSourcePiece(toSourceMs(startMs, clip), toSourceMs(endMs, clip),
						after);
				if (piece != null) {
					pieces.add(piece);
				}
			}
			if (pieces.isEmpty()) {
				continue;
			}
			next.add(new SpeedRegion(region.id(), pieces.stream().mapToLong(Span::startMs).min()
					.orElse(region.startMs()), pieces.stream().mapToLong(Span::endMs).max()
							.orElse(region.endMs()), region.speed()));
		}
		return List.copyOf(next);
	}

	private static List<AnnotationRegion> remapAnnotations(List<AnnotationRegion> regions,
			List<ClipRegion> before, List<ClipRegion> after) {
		if (before.isEmpty()) {
			return regions;
		}
		List<AnnotationRegion> next = new ArrayList<>();
		for (AnnotationRegion region : regions) {
			List<Span> pieces = new ArrayList<>();
			for (ClipRegion clip : before) {
				long startMs = Math.max(region.startMs(), clip.startMs());
				long endMs = Math.min(region.endMs(), clip.endMs());
				if (endMs <= startMs) {
					continue;
				}
				Span piece = mapSourcePiece(toSourceMs(startMs, clip), toSourceMs(endMs, clip),
						after);
				if (piece != null) {
					pieces.add(piece);
				}
			}
			if (pieces.isEmpty()) {
				continue;
			}
			next.add(new AnnotationRegion(region.id(),
					pieces.stream().mapToLong(Span::startMs).min().orElse(region.startMs()),
					pieces.stream().mapToLong(Span::endMs).max().orElse(region.endMs()),
					region.type(), region.content(), region.textContent(), region.imageContent(),
					region.position(), region.size(), region.style(), region.zIndex(),
					region.trackIndex(), region.figureData(), region.blurIntensity(),
					region.blurColor()));
		}
		return List.copyOf(next);
	}

	private static List<AudioRegion> remapAnchors(List<AudioRegion> regions,
			List<ClipRegion> before, List<ClipRegion> after) {
		if (before.isEmpty()) {
			return regions;
		}
		long beforeEnd = clipsEndMs(before);
		long afterEnd = clipsEndMs(after);
		List<AudioRegion> next = new ArrayList<>();
		for (AudioRegion region : regions) {
			Long startMs = null;
			for (ClipRegion clip : before) {
				if (region.startMs() < clip.startMs() || region.startMs() >= clip.endMs()) {
					continue;
				}
				Span piece = mapSourcePiece(toSourceMs(region.startMs(), clip),
						toSourceMs(Math.min(region.startMs() + 1, clip.endMs()), clip), after);
				if (piece != null) {
					startMs = piece.startMs();
				}
				break;
			}
			if (startMs == null) {
				List<ClipRegion> following = before.stream()
						.filter(clip -> clip.startMs() >= region.startMs()
								&& after.stream().anyMatch(nextClip -> nextClip.id().equals(clip.id())))
						.sorted(Comparator.comparingLong(ClipRegion::startMs)).toList();
				if (!following.isEmpty()) {
					ClipRegion followingClip = following.get(0);
					ClipRegion mapped = after.stream()
							.filter(nextClip -> nextClip.id().equals(followingClip.id())).findFirst()
							.orElse(null);
					startMs = mapped != null ? mapped.startMs()
							: Math.min(afterEnd, region.startMs());
				} else if (region.startMs() >= beforeEnd) {
					startMs = Math.max(0, afterEnd + region.startMs() - beforeEnd);
				} else {
					startMs = Math.min(afterEnd, region.startMs());
				}
			}
			long duration = region.endMs() - region.startMs();
			next.add(new AudioRegion(region.id(), startMs, startMs + duration,
					region.audioPath(), region.volume(), region.normalize(), region.trackIndex()));
		}
		return List.copyOf(next);
	}

	private static void commitClipSequence(Draft draft, List<ClipRegion> nextClips) {
		List<ClipRegion> before = draft.state.clipRegions();
		List<ClipRegion> sorted = new ArrayList<>(nextClips);
		sorted.sort(Comparator.comparingLong(ClipRegion::startMs));
		List<ClipRegion> after = ClipSequence.packClipSequence(sorted);
		List<ZoomRegion> zooms = remapZooms(draft.state.zoomRegions(), before, after);
		List<SpeedRegion> speeds = remapSpeeds(draft.state.speedRegions(), before, after);
		List<AnnotationRegion> annotations = remapAnnotations(draft.state.annotationRegions(),
				before, after);
		List<AudioRegion> audio = remapAnchors(draft.state.audioRegions(), before, after);
		draft.state = draft.state.toBuilder()
				.clipRegions(after)
				.zoomRegions(zooms)
				.speedRegions(speeds)
				.annotationRegions(annotations)
				.audioRegions(audio)
				.build();
		markNewJoins(draft, before, after);
	}

	private record Join(String key, long atMs) {}

	private static List<Join> sourceJoins(List<ClipRegion> clips) {
		List<ClipRegion> sorted = new ArrayList<>(clips);
		sorted.sort(Comparator.comparingLong(ClipRegion::startMs));
		List<Join> joins = new ArrayList<>();
		for (int index = 1; index < sorted.size(); index++) {
			long endMs = Clips.getClipSourceEndMs(sorted.get(index - 1));
			long startMs = Clips.getClipSourceStartMs(sorted.get(index));
			if (endMs != startMs) {
				joins.add(new Join(endMs + ">" + startMs, sorted.get(index).startMs()));
			}
		}
		return joins;
	}

	private static void markNewJoins(Draft draft, List<ClipRegion> before, List<ClipRegion> after) {
		Set<String> existing = new LinkedHashSet<>();
		for (Join join : sourceJoins(before)) {
			existing.add(join.key());
		}
		for (Join join : sourceJoins(after)) {
			if (!existing.contains(join.key())) {
				push(draft, Highlight.Kind.CUT, join.atMs(), join.atMs() + 1, "cut");
			}
		}
	}

	private static List<TranscriptCut.SourceRange> timelineRangeToSourceRanges(
			List<ClipRegion> clips, long fromMs, long toMs) {
		List<TranscriptCut.SourceRange> ranges = new ArrayList<>();
		for (ClipRegion clip : clips) {
			long startMs = Math.max(fromMs, clip.startMs());
			long endMs = Math.min(toMs, clip.endMs());
			if (endMs <= startMs) {
				continue;
			}
			long startSource = toSourceMs(startMs, clip);
			long endSource = Math.max(toSourceMs(endMs, clip), startSource + 1);
			ranges.add(new TranscriptCut.SourceRange(startSource, endSource));
		}
		return ranges;
	}

	private static void removeTimelineRanges(Draft draft, long fromMs, long toMs) {
		List<ClipRegion> before = draft.state.clipRegions();
		List<TranscriptCut.SourceRange> ranges = AgentTranscript.mergeSourceRanges(
				timelineRangeToSourceRanges(before, fromMs, toMs).stream()
						.map(range -> new AgentTranscript.Range(range.startMs(), range.endMs()))
						.toList()).stream()
				.map(range -> new TranscriptCut.SourceRange(range.startMs(), range.endMs())).toList();
		List<ClipRegion> next = TranscriptCut.removeSourceRangesFromClips(before, ranges,
				() -> draft.ctx.createId().apply(IdKind.CLIP));
		if (next == before) {
			throw fail("nothing to cut at " + AgentIds.formatSeconds(fromMs) + "–"
					+ AgentIds.formatSeconds(toMs) + "s; the edited timeline is 0.00–"
					+ AgentIds.formatSeconds(clipsEndMs(before)) + "s");
		}
		commitClipSequence(draft, next);
		long removedMs = Math.max(0, clipsEndMs(before) - clipsEndMs(draft.state.clipRegions()));
		draft.summary.add("cut " + AgentIds.formatSeconds(removedMs) + "s (" + ranges.size() + " "
				+ (ranges.size() == 1 ? "range" : "ranges") + ")");
	}

	private static long restoreSourceRanges(Draft draft, List<TranscriptCut.SourceRange> ranges) {
		List<TranscriptCut.SourceRange> merged = TranscriptCut.mergeSourceRanges(ranges);
		if (merged.isEmpty()) {
			throw fail("nothing to restore");
		}
		List<ClipRegion> clips = new ArrayList<>(draft.state.clipRegions());
		clips.sort(Comparator.comparingLong(ClipRegion::startMs));
		long restoredMs = 0;
		for (TranscriptCut.SourceRange range : merged) {
			ClipRegion tail = null;
			for (ClipRegion clip : clips) {
				if (Clips.getClipSourceEndMs(clip) == range.startMs()) {
					tail = clip;
					break;
				}
			}
			ClipRegion head = tail == null ? null : null;
			if (tail == null) {
				for (ClipRegion clip : clips) {
					if (Clips.getClipSourceStartMs(clip) == range.endMs()) {
						head = clip;
						break;
					}
				}
			}
			ClipRegion target = tail != null ? tail : head;
			if (target == null) {
				throw fail("cannot restore " + AgentIds.formatSeconds(range.startMs()) + "–"
						+ AgentIds.formatSeconds(range.endMs()) + "s: no cut borders that footage");
			}
			long growthMs = Math.max(1,
					Math.round((range.endMs() - range.startMs()) / speedOf(target)));
			String extendedId = target.id();
			List<ClipRegion> nextClips = new ArrayList<>();
			for (ClipRegion clip : clips) {
				if (!clip.id().equals(extendedId)) {
					nextClips.add(clip);
					continue;
				}
				if (tail != null) {
					nextClips.add(new ClipRegion(clip.id(), clip.startMs(), clip.endMs() + growthMs,
							clip.sourceStartMs(), clip.sourceMinMs(), clip.sourceMaxMs(),
							clip.speed(), clip.muted(), clip.showSourceAudio(), clip.cameraLayout(),
							clip.transitionOut()));
				} else {
					nextClips.add(new ClipRegion(clip.id(), Math.max(0, clip.startMs() - growthMs),
							clip.endMs(), range.startMs(), clip.sourceMinMs(), clip.sourceMaxMs(),
							clip.speed(), clip.muted(), clip.showSourceAudio(), clip.cameraLayout(),
							clip.transitionOut()));
				}
			}
			clips = nextClips;
			restoredMs += growthMs;
			boolean joined = true;
			while (joined) {
				joined = false;
				ClipRegion current = clips.stream().filter(clip -> clip.id().equals(extendedId))
						.findFirst().orElse(null);
				if (current == null) {
					break;
				}
				ClipRegion other = null;
				for (ClipRegion clip : clips) {
					if (!clip.id().equals(current.id())
							&& Clips.getClipSourceStartMs(clip) == Clips.getClipSourceEndMs(current)
							&& clip.startMs() >= current.startMs()) {
						other = clip;
						break;
					}
				}
				if (other == null) {
					break;
				}
				ClipRegion finalOther = other;
				clips = clips.stream().filter(clip -> !clip.id().equals(finalOther.id()))
						.map(clip -> clip.id().equals(extendedId)
								? new ClipRegion(clip.id(), clip.startMs(),
										clip.endMs() + Math.max(0,
												finalOther.endMs() - finalOther.startMs()),
										clip.sourceStartMs(), clip.sourceMinMs(),
										clip.sourceMaxMs(), clip.speed(), clip.muted(),
										clip.showSourceAudio(), clip.cameraLayout(),
										clip.transitionOut() != null ? clip.transitionOut()
												: finalOther.transitionOut())
								: clip)
						.toList();
				clips = new ArrayList<>(clips);
				joined = true;
			}
		}
		commitClipSequence(draft, clips);
		return restoredMs;
	}

	private static void assertSpanFree(List<? extends Object> regions, Span candidate,
			Map<String, String> shortById, String label, @Nullable String ignoreId) {
		for (Object region : regions) {
			String id;
			long startMs;
			long endMs;
			if (region instanceof ZoomRegion zoom) {
				id = zoom.id();
				startMs = zoom.startMs();
				endMs = zoom.endMs();
			} else if (region instanceof SpeedRegion speed) {
				id = speed.id();
				startMs = speed.startMs();
				endMs = speed.endMs();
			} else if (region instanceof AnnotationRegion annotation) {
				id = annotation.id();
				startMs = annotation.startMs();
				endMs = annotation.endMs();
			} else {
				continue;
			}
			if (id != null && id.equals(ignoreId)) {
				continue;
			}
			if (startMs < candidate.endMs() && endMs > candidate.startMs()) {
				String shortId = shortById.get(id);
				throw fail(label + " overlaps " + (shortId == null ? "another region" : shortId) + " ("
						+ AgentIds.formatSeconds(startMs) + "–"
						+ AgentIds.formatSeconds(endMs) + "s); update it or delete it first");
			}
		}
	}

	private static Span clampTimelineSpan(Draft draft, long startMs, long endMs) {
		long duration = timelineDurationMs(draft);
		long start = Math.max(0, Math.min(startMs, duration - 1));
		long end = Math.max(start + 1, Math.min(endMs, duration));
		if (startMs > duration) {
			throw fail(AgentIds.formatSeconds(startMs) + "s is past the end of the edited timeline ("
					+ AgentIds.formatSeconds(duration) + "s)");
		}
		return new Span(start, end);
	}

	private static AnnotationPosition annotationPosition(Geometry geometry) {
		double width = Math.min(100, Math.max(1, geometry.w() * 100));
		double height = Math.min(100, Math.max(1, geometry.h() * 100));
		return new AnnotationPosition(Math.min(100 - width,
				Math.max(0, geometry.x() * 100 - width / 2)),
				Math.min(100 - height, Math.max(0, geometry.y() * 100 - height / 2)));
	}

	private static AnnotationSize annotationSize(Geometry geometry) {
		return new AnnotationSize(Math.min(100, Math.max(1, geometry.w() * 100)),
				Math.min(100, Math.max(1, geometry.h() * 100)));
	}

	private static Set<String> selectWordsByPhrase(List<AgentTranscript.Word> words, String phrase) {
		List<String> tokens = new ArrayList<>();
		for (String token : phrase.split("\\s+")) {
			String normalized = AgentTranscript.normalizeWordText(token);
			if (!normalized.isEmpty()) {
				tokens.add(normalized);
			}
		}
		Set<String> keys = new LinkedHashSet<>();
		if (tokens.isEmpty()) {
			return keys;
		}
		List<String> normalized = words.stream().map(word -> AgentTranscript.normalizeWordText(
				word.text())).toList();
		for (int index = 0; index + tokens.size() <= normalized.size(); index++) {
			boolean matches = true;
			for (int offset = 0; offset < tokens.size(); offset++) {
				if (!tokens.get(offset).equals(normalized.get(index + offset))) {
					matches = false;
					break;
				}
			}
			if (!matches) {
				continue;
			}
			for (int offset = 0; offset < tokens.size(); offset++) {
				keys.add(words.get(index + offset).key());
			}
		}
		return keys;
	}

	private static List<AgentTranscript.Word> wordsForIds(Draft draft, List<String> ids) {
		List<AgentTranscript.Word> words = new ArrayList<>();
		for (String shortId : ids) {
			String key = draft.ids.wordKeyByShort().get(shortId.trim());
			AgentTranscript.Word word = key == null ? null : draft.wordByKey.get(key);
			if (word == null) {
				throw fail(shortId + " is not a transcript word (" + draft.words.size() + " words)");
			}
			words.add(word);
		}
		return words;
	}

	private static long applyCutWords(Draft draft, List<AgentTranscript.Word> words,
			Set<String> keys, String label) {
		if (keys.isEmpty()) {
			throw fail(label + ": no matching words in the transcript");
		}
		List<AgentTranscript.Range> ranges = AgentTranscript.wordsToCutRanges(words, keys);
		if (ranges.isEmpty()) {
			throw fail(label + ": nothing to cut");
		}
		List<ClipRegion> before = draft.state.clipRegions();
		List<TranscriptCut.SourceRange> coreRanges = ranges.stream()
				.map(range -> new TranscriptCut.SourceRange(range.startMs(), range.endMs()))
				.toList();
		List<ClipRegion> next = TranscriptCut.removeSourceRangesFromClips(before, coreRanges,
				() -> draft.ctx.createId().apply(IdKind.CLIP));
		if (next == before) {
			throw fail(label + ": that footage is already cut");
		}
		long removedMs = Math.max(0, clipsEndMs(before) - clipsEndMs(
				ClipSequence.packClipSequence(next)));
		commitClipSequence(draft, next);
		return removedMs;
	}

	private static String shortRef(String kind, String internalId) {
		return "{{" + kind + ":" + internalId + "}}";
	}

	private static String resolveShortRefs(String text, ProjectEditorState state) {
		if (!text.contains("{{")) {
			return text;
		}
		AgentIds map = AgentIds.of(state);
		String result = text;
		for (String kind : List.of("z", "s", "a", "m", "q")) {
			Map<String, String> lookup = switch (kind) {
				case "z" -> map.zoomShortById();
				case "s" -> map.speedShortById();
				case "a" -> map.annotationShortById();
				case "m" -> map.audioShortById();
				default -> map.cueShortById();
			};
			for (Map.Entry<String, String> entry : lookup.entrySet()) {
				result = result.replace("{{" + kind + ":" + entry.getKey() + "}}",
						entry.getValue());
			}
		}
		return result;
	}

	private static Map<String, String> shortLookup(Draft draft, String kind) {
		return switch (kind) {
			case "z" -> draft.ids.zoomShortById();
			case "s" -> draft.ids.speedShortById();
			case "a" -> draft.ids.annotationShortById();
			case "m" -> draft.ids.audioShortById();
			default -> draft.ids.cueShortById();
		};
	}

	private static void applyOne(Draft draft, ObjectNode raw, int index) {
		String op = AgentOpReader.opName(raw, index);
		switch (op) {
			case "cut" -> {
				double[] range = AgentOpReader.readRange(raw, "from", "to");
				removeTimelineRanges(draft, toMs(range[0]), toMs(range[1]));
			}
			case "keep" -> keep(draft, raw);
			case "split" -> split(draft, raw);
			case "delete_clip" -> deleteClip(draft, raw);
			case "move_clip" -> moveClip(draft, raw);
			case "clip" -> updateClip(draft, raw);
			case "trim_clip" -> trimClip(draft, raw);
			case "cut_words" -> cutWords(draft, raw);
			case "restore_words" -> restoreWords(draft, raw);
			case "cut_fillers" -> cutFillers(draft, raw);
			case "cut_pauses" -> cutPauses(draft, raw);
			case "cut_range_src" -> cutRangeSource(draft, raw);
			case "zoom" -> addZoom(draft, raw);
			case "update_zoom" -> updateZoom(draft, raw);
			case "delete_zoom" -> deleteZoom(draft, raw);
			case "auto_zoom" -> autoZoom(draft, raw);
			case "speed" -> addSpeed(draft, raw);
			case "delete_speed" -> deleteSpeed(draft, raw);
			case "text", "image", "figure", "blur" -> addAnnotation(draft, op, raw);
			case "update_annotation" -> updateAnnotation(draft, raw);
			case "delete_annotation" -> deleteAnnotation(draft, raw);
			case "audio" -> addAudio(draft, raw);
			case "update_audio" -> updateAudio(draft, raw);
			case "delete_audio" -> deleteAudio(draft, raw);
			case "source_audio" -> sourceAudio(draft, raw);
			case "caption" -> addCaption(draft, raw);
			case "update_caption" -> updateCaption(draft, raw);
			case "split_caption" -> splitCaption(draft, raw);
			case "merge_captions" -> mergeCaptions(draft, raw);
			case "delete_caption" -> deleteCaption(draft, raw);
			case "captions_style" -> captionsStyle(draft, raw);
			case "set" -> setSettings(draft, raw);
			default -> throw fail("unknown op \"" + op + "\"; call inspect help for the op list");
		}
	}

	// Clip ops ------------------------------------------------------------------------------

	private static ClipRegion findClip(Draft draft, String shortId) {
		String id = draft.ids.clipIdByShort().get(shortId.trim());
		if (id == null) {
			throw fail(shortId + " not found; clips: "
					+ AgentIds.describeShorts(draft.ids.clipIds(), "c"));
		}
		ClipRegion clip = draft.state.clipRegions().stream()
				.filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
		if (clip == null) {
			throw fail(shortId + " not found");
		}
		return clip;
	}

	private static void keep(Draft draft, ObjectNode raw) {
		List<double[]> ranges = new ArrayList<>(AgentOpReader.readRanges(raw));
		ranges.sort(Comparator.comparingDouble(range -> range[0]));
		List<TranscriptCut.SourceRange> removeRanges = new ArrayList<>();
		long cursor = 0;
		long durationMs = timelineDurationMs(draft);
		for (double[] range : ranges) {
			long from = toMs(range[0]);
			long to = toMs(range[1]);
			if (from > cursor) {
				removeRanges.addAll(timelineRangeToSourceRanges(draft.state.clipRegions(), cursor,
						from));
			}
			cursor = Math.max(cursor, to);
		}
		if (cursor < durationMs) {
			removeRanges.addAll(timelineRangeToSourceRanges(draft.state.clipRegions(), cursor,
					durationMs));
		}
		if (removeRanges.isEmpty()) {
			throw fail("keep already covers the whole timeline; nothing to remove");
		}
		List<ClipRegion> before = draft.state.clipRegions();
		List<ClipRegion> next = TranscriptCut.removeSourceRangesFromClips(before, removeRanges,
				() -> draft.ctx.createId().apply(IdKind.CLIP));
		if (next == before) {
			throw fail("keep would remove all footage");
		}
		long removedMs = Math.max(0, clipsEndMs(before) - clipsEndMs(
				ClipSequence.packClipSequence(next)));
		commitClipSequence(draft, next);
		draft.summary.add("kept " + ranges.size() + " "
				+ (ranges.size() == 1 ? "range" : "ranges") + " (−"
				+ AgentIds.formatSeconds(removedMs) + "s)");
	}

	private static void split(Draft draft, ObjectNode raw) {
		long atMs = toMs(AgentOpReader.number(raw, "at"));
		ClipSplits.Plan plan = ClipSplits.planClipSplit(draft.state.clipRegions(), atMs,
				() -> draft.ctx.createId().apply(IdKind.CLIP));
		if (plan == null) {
			throw fail("nothing to split at " + AgentIds.formatSeconds(atMs)
					+ "s; the split must fall inside a clip (0.00–"
					+ AgentIds.formatSeconds(timelineDurationMs(draft)) + "s)");
		}
		List<ClipRegion> next = new ArrayList<>();
		for (ClipRegion clip : draft.state.clipRegions()) {
			if (!clip.id().equals(plan.targetId())) {
				next.add(clip);
				continue;
			}
			next.add(new ClipRegion(clip.id(), plan.left().startMs(), plan.left().endMs(),
					plan.left().sourceStartMs(), plan.left().sourceMinMs(),
					plan.left().sourceMaxMs(), plan.left().speed(), plan.left().muted(),
					plan.left().showSourceAudio(), plan.left().cameraLayout(), null));
			next.add(plan.right());
		}
		commitClipSequence(draft, next);
		draft.summary.add("split clip at " + AgentIds.formatSeconds(atMs) + "s");
		push(draft, Highlight.Kind.CUT, atMs, atMs + 1, "split");
	}

	private static void deleteClip(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		ClipRegion clip = findClip(draft, shortId);
		if (draft.state.clipRegions().size() == 1) {
			throw fail("cannot delete the only clip; use cut to trim it instead");
		}
		commitClipSequence(draft, draft.state.clipRegions().stream()
				.filter(candidate -> !candidate.id().equals(clip.id())).toList());
		draft.summary.add("deleted " + shortId);
	}

	private static void moveClip(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		ClipRegion clip = findClip(draft, shortId);
		int toIndex = (int) Math.round(AgentOpReader.number(raw, "toIndex"));
		List<ClipRegion> next = ClipSequence.reorderClipSequence(draft.state.clipRegions(),
				clip.id(), toIndex);
		commitClipSequence(draft, next);
		draft.summary.add("moved " + shortId + " to index " + toIndex);
	}

	private static void updateClip(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		ClipRegion clip = findClip(draft, shortId);
		Double speed = AgentOpReader.optionalNumber(raw, "speed");
		if (speed != null && speed <= 0) {
			throw fail("\"speed\" must be greater than 0");
		}
		Boolean muted = AgentOpReader.optionalBoolean(raw, "muted");
		dev.gravaai.core.project.ClipCameraLayout cameraLayout = AgentOpReader
				.readCameraLayout(raw);
		TransitionInput transition = AgentOpReader.readTransition(raw);
		ClipRegion next = clip;
		if (speed != null) {
			double sourceDuration = (clip.endMs() - clip.startMs()) * speedOf(clip);
			next = new ClipRegion(next.id(), next.startMs(),
					Math.max(clip.startMs() + 1,
							Math.round(clip.startMs() + sourceDuration / speed)),
					next.sourceStartMs(), next.sourceMinMs(), next.sourceMaxMs(), speed,
					next.muted(), next.showSourceAudio(), next.cameraLayout(),
					next.transitionOut());
		}
		if (muted != null) {
			next = new ClipRegion(next.id(), next.startMs(), next.endMs(), next.sourceStartMs(),
					next.sourceMinMs(), next.sourceMaxMs(), next.speed(), muted,
					next.showSourceAudio(), next.cameraLayout(), next.transitionOut());
		}
		if (cameraLayout != null) {
			next = new ClipRegion(next.id(), next.startMs(), next.endMs(), next.sourceStartMs(),
					next.sourceMinMs(), next.sourceMaxMs(), next.speed(), next.muted(),
					next.showSourceAudio(), cameraLayout, next.transitionOut());
		}
		if (transition.present()) {
			ClipTransition nextTransition = transition.ms() == null ? null
					: new ClipTransition(ClipTransitionKind.FADE_BLACK, transition.ms());
			next = new ClipRegion(next.id(), next.startMs(), next.endMs(), next.sourceStartMs(),
					next.sourceMinMs(), next.sourceMaxMs(), next.speed(), next.muted(),
					next.showSourceAudio(), next.cameraLayout(), nextTransition);
		}
		ClipRegion updated = next;
		commitClipSequence(draft, draft.state.clipRegions().stream()
				.map(candidate -> candidate.id().equals(clip.id()) ? updated : candidate).toList());
		List<String> parts = new ArrayList<>();
		parts.add(shortId);
		if (speed != null) {
			parts.add(speed + "x");
		}
		if (muted != null) {
			parts.add(muted ? "muted" : "unmuted");
		}
		if (cameraLayout != null) {
			parts.add(cameraLayout.wire());
		}
		if (transition.present()) {
			parts.add(transition.ms() == null ? "no transition" : "fade " + transition.ms() + "ms");
		}
		draft.summary.add("updated " + String.join(" ", parts));
	}

	private static void trimClip(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		ClipRegion clip = findClip(draft, shortId);
		Double start = AgentOpReader.optionalNumber(raw, "start");
		Double end = AgentOpReader.optionalNumber(raw, "end");
		if (start == null && end == null) {
			throw fail("trim_clip needs \"start\" and/or \"end\"");
		}
		long startMs = start != null ? toMs(start) : clip.startMs();
		long endMs = end != null ? toMs(end) : clip.endMs();
		if (endMs <= startMs) {
			throw fail("\"end\" must be after \"start\"");
		}
		ClipRegion trimmed = ClipSpanChanges.changeClipSpan(clip, startMs, endMs,
				sourceDurationMs(draft));
		if (trimmed.startMs() == clip.startMs() && trimmed.endMs() == clip.endMs()) {
			throw fail(shortId + " already spans " + AgentIds.formatSeconds(startMs) + "–"
					+ AgentIds.formatSeconds(endMs) + "s");
		}
		commitClipSequence(draft, draft.state.clipRegions().stream()
				.map(candidate -> candidate.id().equals(clip.id()) ? trimmed : candidate).toList());
		draft.summary.add("trimmed " + shortId + " to "
				+ AgentIds.formatSeconds(trimmed.startMs()) + "–"
				+ AgentIds.formatSeconds(trimmed.endMs()) + "s");
		push(draft, Highlight.Kind.CHANGED, Math.min(trimmed.startMs(), clip.startMs()),
				Math.max(trimmed.endMs(), clip.endMs()), "trim");
	}

	// Transcript ops ------------------------------------------------------------------------

	private static void cutWords(Draft draft, ObjectNode raw) {
		if (draft.words.isEmpty()) {
			throw fail("no transcript yet; run job transcribe first");
		}
		List<String> ids = raw.path("ids").isMissingNode() || raw.path("ids").isNull() ? null
				: AgentOpReader.readStringArray(raw, "ids");
		String text = AgentOpReader.optionalString(raw, "text");
		Boolean all = AgentOpReader.optionalBoolean(raw, "all");
		if (ids == null && text == null && !Boolean.TRUE.equals(all)) {
			throw fail("cut_words needs \"ids\", \"text\" or \"all\": true");
		}
		Set<String> keys;
		if (Boolean.TRUE.equals(all)) {
			keys = new LinkedHashSet<>(draft.words.stream().map(AgentTranscript.Word::key).toList());
		} else if (ids != null) {
			keys = new LinkedHashSet<>(wordsForIds(draft, ids).stream()
					.map(AgentTranscript.Word::key).toList());
		} else {
			if (text == null) {
				throw fail("cut_words needs \"ids\", \"text\" or \"all\": true");
			}
			keys = selectWordsByPhrase(draft.words, text);
		}
		long removedMs = applyCutWords(draft, draft.words, keys, "cut_words");
		draft.summary.add("cut " + keys.size() + " "
				+ (keys.size() == 1 ? "word" : "words") + " (−"
				+ AgentIds.formatSeconds(removedMs) + "s)");
	}

	private static void restoreWords(Draft draft, ObjectNode raw) {
		List<String> ids = AgentOpReader.readStringArray(raw, "ids");
		List<AgentTranscript.Word> selected = wordsForIds(draft, ids);
		Set<String> keys = new LinkedHashSet<>(selected.stream()
				.map(AgentTranscript.Word::key).toList());
		List<AgentTranscript.Range> ranges = AgentTranscript.wordsToCutRanges(draft.words, keys);
		List<TranscriptCut.SourceRange> coreRanges = ranges.stream()
				.map(range -> new TranscriptCut.SourceRange(range.startMs(), range.endMs()))
				.toList();
		long restoredMs = restoreSourceRanges(draft, coreRanges);
		draft.summary.add("restored " + selected.size() + " "
				+ (selected.size() == 1 ? "word" : "words") + " (+"
				+ AgentIds.formatSeconds(restoredMs) + "s)");
	}

	private static void cutFillers(Draft draft, ObjectNode raw) {
		if (draft.words.isEmpty()) {
			throw fail("no transcript yet; run job transcribe first");
		}
		String lang = AgentOpReader.optionalString(raw, "lang");
		if (lang == null) {
			lang = draft.state.autoCaptionSettings().language();
		}
		Set<String> keys = AgentTranscript.selectFillerWords(draft.words, lang);
		if (keys.isEmpty()) {
			draft.summary.add("no filler words found");
			return;
		}
		long removedMs = applyCutWords(draft, draft.words, keys, "cut_fillers");
		draft.summary.add("cut " + keys.size() + " filler "
				+ (keys.size() == 1 ? "word" : "words") + " (−"
				+ AgentIds.formatSeconds(removedMs) + "s)");
	}

	private static void cutPauses(Draft draft, ObjectNode raw) {
		if (draft.words.size() < 2) {
			throw fail("cut_pauses needs a transcript with at least two words");
		}
		Double min = AgentOpReader.optionalNumber(raw, "min");
		Double keep = AgentOpReader.optionalNumber(raw, "keep");
		double minSeconds = min == null ? 1 : min;
		double keepSeconds = keep == null ? 0.25 : keep;
		if (minSeconds <= 0) {
			throw fail("\"min\" must be greater than 0");
		}
		if (keepSeconds < 0 || keepSeconds >= minSeconds) {
			throw fail("\"keep\" must be >= 0 and smaller than \"min\"");
		}
		long minMs = Math.round(minSeconds * 1000);
		long keepMs = Math.round(keepSeconds * 1000);
		List<TranscriptCut.SourceRange> ranges = new ArrayList<>();
		for (int index = 1; index < draft.words.size(); index++) {
			AgentTranscript.Word previous = draft.words.get(index - 1);
			AgentTranscript.Word next = draft.words.get(index);
			if (next.startMs() - previous.endMs() < minMs) {
				continue;
			}
			ranges.add(new TranscriptCut.SourceRange(previous.endMs() + keepMs,
					next.startMs()));
		}
		List<ClipRegion> before = draft.state.clipRegions();
		List<ClipRegion> next = ranges.isEmpty() ? before
				: TranscriptCut.removeSourceRangesFromClips(before, ranges,
						() -> draft.ctx.createId().apply(IdKind.CLIP));
		if (next == before) {
			draft.summary.add("no pauses longer than "
					+ (minSeconds == Math.rint(minSeconds) ? String.valueOf((long) minSeconds)
							: String.valueOf(minSeconds))
					+ "s left to cut");
			return;
		}
		long removedMs = Math.max(0, clipsEndMs(before) - clipsEndMs(
				ClipSequence.packClipSequence(next)));
		commitClipSequence(draft, next);
		draft.summary.add("cut " + ranges.size() + " "
				+ (ranges.size() == 1 ? "pause" : "pauses") + " (−"
				+ AgentIds.formatSeconds(removedMs) + "s)");
	}

	private static void cutRangeSource(Draft draft, ObjectNode raw) {
		double[] range = AgentOpReader.readRange(raw, "from", "to");
		List<ClipRegion> before = draft.state.clipRegions();
		List<TranscriptCut.SourceRange> ranges = List
				.of(new TranscriptCut.SourceRange(toMs(range[0]), toMs(range[1])));
		List<ClipRegion> next = TranscriptCut.removeSourceRangesFromClips(before, ranges,
				() -> draft.ctx.createId().apply(IdKind.CLIP));
		if (next == before) {
			throw fail("nothing to cut at " + AgentIds.formatSeconds(toMs(range[0])) + "–"
					+ AgentIds.formatSeconds(toMs(range[1])) + "s of the recording");
		}
		long removedMs = Math.max(0, clipsEndMs(before) - clipsEndMs(
				ClipSequence.packClipSequence(next)));
		commitClipSequence(draft, next);
		draft.summary.add("cut " + AgentIds.formatSeconds(removedMs) + "s of source");
	}

	// Zoom/speed/annotation/audio/caption/settings ops ---------------------------------------

	private static void addZoom(Draft draft, ObjectNode raw) {
		double[] range = AgentOpReader.readRange(raw, "from", "to");
		Span span = clampTimelineSpan(draft, toMs(range[0]), toMs(range[1]));
		assertSpanFree(draft.state.zoomRegions(), span, draft.ids.zoomShortById(), "zoom", null);
		ZoomDepth depth = AgentOpReader.readDepth(raw, false);
		ZoomFocus focus = AgentOpReader.readFocus(raw);
		String id = draft.ctx.createId().apply(IdKind.ZOOM);
		ZoomRegion zoom = new ZoomRegion(id, span.startMs(), span.endMs(),
				depth == null ? ZoomDepth.DEFAULT : depth,
				focus == null ? new ZoomFocus(0.5, 0.5) : focus, ZoomMode.MANUAL);
		List<ZoomRegion> zooms = new ArrayList<>(draft.state.zoomRegions());
		zooms.add(zoom);
		draft.state = draft.state.toBuilder().zoomRegions(zooms).build();
		draft.summary.add("zoom " + shortRef("z", id) + " "
				+ AgentIds.formatSeconds(span.startMs()) + "–"
				+ AgentIds.formatSeconds(span.endMs()) + "s depth " + zoom.depth().wire());
		push(draft, Highlight.Kind.ADDED, span.startMs(), span.endMs(), "zoom");
	}

	private static void updateZoom(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		String id = draft.ids.zoomIdByShort().get(shortId.trim());
		ZoomRegion zoom = id == null ? null
				: draft.state.zoomRegions().stream()
						.filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
		if (zoom == null) {
			throw fail(shortId + " not found");
		}
		Double from = AgentOpReader.optionalNumber(raw, "from");
		Double to = AgentOpReader.optionalNumber(raw, "to");
		ZoomDepth depth = AgentOpReader.readDepth(raw, false);
		ZoomFocus focus = AgentOpReader.readFocus(raw);
		ZoomRegion next = new ZoomRegion(zoom.id(),
				from == null ? zoom.startMs() : toMs(from),
				to == null ? zoom.endMs() : toMs(to),
				depth == null ? zoom.depth() : depth, focus == null ? zoom.focus() : focus,
				zoom.mode());
		if (next.endMs() <= next.startMs()) {
			throw fail("\"to\" must be after \"from\"");
		}
		assertSpanFree(draft.state.zoomRegions(),
				new Span(next.startMs(), next.endMs()), draft.ids.zoomShortById(), "zoom",
				id);
		draft.state = draft.state.toBuilder().zoomRegions(draft.state.zoomRegions().stream()
						.map(candidate -> candidate.id().equals(id) ? next : candidate).toList()).build();
		draft.summary.add("updated " + shortId);
		push(draft, Highlight.Kind.CHANGED, next.startMs(), next.endMs(), "zoom");
	}

	private static void deleteZoom(Draft draft, ObjectNode raw) {
		Boolean all = AgentOpReader.optionalBoolean(raw, "all");
		if (Boolean.TRUE.equals(all)) {
			int count = draft.state.zoomRegions().size();
			draft.state = draft.state.toBuilder().zoomRegions(List.of()).build();
			draft.summary.add("deleted " + count + " zooms");
			return;
		}
		String shortId = AgentOpReader.optionalString(raw, "id");
		if (shortId == null || shortId.isBlank()) {
			throw fail("delete_zoom needs \"id\" or \"all\": true");
		}
		String id = draft.ids.zoomIdByShort().get(shortId.trim());
		if (id == null) {
			throw fail(shortId + " not found");
		}
		draft.state = draft.state.toBuilder().zoomRegions(draft.state.zoomRegions().stream()
						.filter(candidate -> !candidate.id().equals(id)).toList()).build();
		draft.summary.add("deleted " + shortId);
	}

	private static void autoZoom(Draft draft, ObjectNode raw) {
		Double from = AgentOpReader.optionalNumber(raw, "from");
		Double to = AgentOpReader.optionalNumber(raw, "to");
		Boolean replace = AgentOpReader.optionalBoolean(raw, "replace");
		List<double[]> reserved = new ArrayList<>();
		if (!Boolean.TRUE.equals(replace)) {
			for (ZoomRegion zoom : draft.state.zoomRegions()) {
				reserved.add(new double[] {
						Clips.mapTimelineTimeToSourceTime(zoom.startMs(),
								draft.state.clipRegions()),
						Clips.mapTimelineTimeToSourceTime(zoom.endMs(),
								draft.state.clipRegions()) });
			}
		}
		AgentAutoZoom.Result result = AgentAutoZoom.build(draft.ctx.cursorTelemetry(),
				sourceDurationMs(draft), 1000, reserved);
		if (!"ok".equals(result.status())) {
			String message = switch (result.status()) {
				case "no-telemetry" -> "auto_zoom needs cursor telemetry (this recording has none)";
				case "all-reserved" -> !Boolean.TRUE.equals(replace)
						&& !draft.state.zoomRegions().isEmpty()
								? "auto_zoom: every click already has a zoom ("
										+ AgentIds.describeShorts(draft.ids.zoomIds(), "z")
										+ "); pass replace:true to rebuild them"
								: "auto_zoom found no click clusters";
				default -> "auto_zoom found no click clusters";
			};
			throw fail(message);
		}
		List<ClipRegion> clips = draft.state.clipRegions();
		long fromMs = from == null ? 0 : toMs(from);
		long toMsValue = to == null ? timelineDurationMs(draft) : toMs(to);
		List<ZoomRegion> zooms = new ArrayList<>();
		for (AgentAutoZoom.Suggestion suggestion : result.suggestions()) {
			boolean inClip = clips.stream()
					.anyMatch(clip -> suggestion.start() >= Clips.getClipSourceStartMs(clip)
							&& suggestion.start() < Clips.getClipSourceEndMs(clip));
			if (!inClip) {
				continue;
			}
			long startMs = Clips.mapSourceTimeToTimelineTime(suggestion.start(), clips);
			long endMs = Clips.mapSourceTimeToTimelineTime(suggestion.end(), clips);
			if (endMs <= startMs || startMs < fromMs || endMs > toMsValue) {
				continue;
			}
			zooms.add(new ZoomRegion(draft.ctx.createId().apply(IdKind.ZOOM), startMs, endMs,
					ZoomDepth.DEFAULT, new ZoomFocus(suggestion.cx(), suggestion.cy()),
					ZoomMode.AUTO));
		}
		if (zooms.isEmpty()) {
			if (Boolean.TRUE.equals(replace) && from == null && to == null) {
				throw fail("auto_zoom found no click clusters");
			}
			throw fail("auto_zoom found no clicks inside the given range");
		}
		List<ZoomRegion> next = Boolean.TRUE.equals(replace) ? zooms
				: new ArrayList<>(draft.state.zoomRegions()) {{
					addAll(zooms);
				}};
		draft.state = draft.state.toBuilder().zoomRegions(next).build();
		draft.summary.add("added " + zooms.size() + " click zooms");
		for (ZoomRegion zoom : zooms) {
			push(draft, Highlight.Kind.ADDED, zoom.startMs(), zoom.endMs(), "auto zoom");
		}
	}

	private static void addSpeed(Draft draft, ObjectNode raw) {
		double[] range = AgentOpReader.readRange(raw, "from", "to");
		double rate = AgentOpReader.readSpeedRate(raw);
		Span span = clampTimelineSpan(draft, toMs(range[0]), toMs(range[1]));
		assertSpanFree(draft.state.speedRegions(), span, draft.ids.speedShortById(), "speed",
				null);
		String id = draft.ctx.createId().apply(IdKind.SPEED);
		SpeedRegion region = new SpeedRegion(id, span.startMs(), span.endMs(), rate);
		List<SpeedRegion> speeds = new ArrayList<>(draft.state.speedRegions());
		speeds.add(region);
		draft.state = draft.state.toBuilder().speedRegions(speeds).build();
		draft.summary.add("speed " + shortRef("s", id) + " " + rate + "x at "
				+ AgentIds.formatSeconds(span.startMs()) + "–"
				+ AgentIds.formatSeconds(span.endMs()) + "s");
		push(draft, Highlight.Kind.ADDED, span.startMs(), span.endMs(), rate + "x");
	}

	private static void deleteSpeed(Draft draft, ObjectNode raw) {
		Boolean all = AgentOpReader.optionalBoolean(raw, "all");
		if (Boolean.TRUE.equals(all)) {
			draft.state = draft.state.toBuilder().speedRegions(List.of()).build();
			draft.summary.add("deleted all speed regions");
			return;
		}
		String shortId = AgentOpReader.optionalString(raw, "id");
		if (shortId == null || shortId.isBlank()) {
			throw fail("delete_speed needs \"id\" or \"all\": true");
		}
		String id = draft.ids.speedIdByShort().get(shortId.trim());
		if (id == null) {
			throw fail(shortId + " not found");
		}
		draft.state = draft.state.toBuilder().speedRegions(draft.state.speedRegions().stream()
						.filter(candidate -> !candidate.id().equals(id)).toList()).build();
		draft.summary.add("deleted " + shortId);
	}

	private static void addAnnotation(Draft draft, String op, ObjectNode raw) {
		double[] range = AgentOpReader.readRange(raw, "from", "to");
		Span span = clampTimelineSpan(draft, toMs(range[0]), toMs(range[1]));
		int zIndex = draft.state.annotationRegions().stream().mapToInt(
				AnnotationRegion::zIndex).max().orElse(0) + 1;
		Geometry geometry = AgentOpReader.geometry(raw);
		String id = draft.ctx.createId().apply(IdKind.ANNOTATION);
		AnnotationRegion annotation;
		if ("text".equals(op)) {
			String text = AgentOpReader.string(raw, "text");
			StyleInput style = AgentOpReader.readStyle(raw);
			AnnotationTextStyle resolved = AgentTextStyles.apply(style);
			annotation = new AnnotationRegion(id, span.startMs(), span.endMs(),
					AnnotationType.TEXT, text, text, null, annotationPosition(geometry),
					annotationSize(geometry), resolved, zIndex, 0, null, null, null);
		} else if ("image".equals(op)) {
			String path = AgentOpReader.string(raw, "path");
			String imageContent = raw.path("imageContent").isString()
					? raw.path("imageContent").asString()
					: null;
			if (imageContent == null) {
				throw fail("could not read image \"" + path
						+ "\" (the editor loads it before applying)");
			}
			annotation = new AnnotationRegion(id, span.startMs(), span.endMs(),
					AnnotationType.IMAGE, imageContent, null, imageContent,
					annotationPosition(geometry), annotationSize(geometry),
					AnnotationTextStyle.defaultStyle(), zIndex, 0, null, null, null);
		} else if ("figure".equals(op)) {
			ArrowDirection arrow = AgentOpReader.readArrow(raw);
			String color = AgentOpReader.optionalString(raw, "color");
			annotation = new AnnotationRegion(id, span.startMs(), span.endMs(),
					AnnotationType.FIGURE, "", null, null, annotationPosition(geometry),
					annotationSize(geometry), AnnotationTextStyle.defaultStyle(), zIndex, 0,
					new FigureData(arrow, color == null ? FigureData.DEFAULT.color() : color,
							FigureData.DEFAULT.strokeWidth()),
					null, null);
		} else {
			annotation = new AnnotationRegion(id, span.startMs(), span.endMs(),
					AnnotationType.BLUR, "", null, null, annotationPosition(geometry),
					annotationSize(geometry), AnnotationTextStyle.defaultStyle(), zIndex, 0,
					null, (double) AnnotationRegion.BLUR_ANNOTATION_STRENGTH, null);
		}
		List<AnnotationRegion> annotations = new ArrayList<>(draft.state.annotationRegions());
		annotations.add(annotation);
		draft.state = draft.state.toBuilder().annotationRegions(annotations).build();
		draft.summary.add("added " + op + " " + shortRef("a", id) + " at "
				+ AgentIds.formatSeconds(span.startMs()) + "–"
				+ AgentIds.formatSeconds(span.endMs()) + "s");
		push(draft, Highlight.Kind.ADDED, span.startMs(), span.endMs(), op);
	}

	private static void updateAnnotation(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		String id = draft.ids.annotationIdByShort().get(shortId.trim());
		AnnotationRegion annotation = id == null ? null
				: draft.state.annotationRegions().stream()
						.filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
		if (annotation == null) {
			throw fail(shortId + " not found");
		}
		Double from = AgentOpReader.optionalNumber(raw, "from");
		Double to = AgentOpReader.optionalNumber(raw, "to");
		long startMs = from == null ? annotation.startMs() : toMs(from);
		long endMs = to == null ? annotation.endMs() : toMs(to);
		if (endMs <= startMs) {
			throw fail("\"to\" must be after \"from\"");
		}
		Double x = AgentOpReader.readFraction(raw, "x");
		Double y = AgentOpReader.readFraction(raw, "y");
		Double w = AgentOpReader.readFraction(raw, "w");
		Double h = AgentOpReader.readFraction(raw, "h");
		AnnotationPosition position = annotation.position();
		AnnotationSize size = annotation.size();
		double centreX = x == null ? (position.x() + size.width() / 2) / 100 : x;
		double centreY = y == null ? (position.y() + size.height() / 2) / 100 : y;
		double width = w == null ? size.width() / 100 : w;
		double height = h == null ? size.height() / 100 : h;
		Geometry geometry = new Geometry(centreX, centreY, width, height);
		String text = AgentOpReader.optionalString(raw, "text");
		String color = AgentOpReader.optionalString(raw, "color");
		ArrowDirection arrow = AgentOpReader.readOptionalArrow(raw);
		AnnotationTextStyle style = annotation.style();
		if (color != null) {
			style = new AnnotationTextStyle(color, style.backgroundColor(), style.fontSize(),
					style.fontFamily(), style.fontWeight(), style.fontStyle(),
					style.textDecoration(), style.textAlign(), style.borderRadius());
		}
		FigureData figure = annotation.figureData();
		if (color != null && figure != null) {
			figure = new FigureData(figure.arrowDirection(), color, figure.strokeWidth());
		}
		if (arrow != null) {
			figure = new FigureData(arrow,
					figure == null ? FigureData.DEFAULT.color() : figure.color(),
					figure == null ? FigureData.DEFAULT.strokeWidth() : figure.strokeWidth());
		}
		AnnotationRegion next = new AnnotationRegion(annotation.id(), startMs, endMs,
				annotation.type(), text == null ? annotation.content() : text,
				text == null ? annotation.textContent() : text, annotation.imageContent(),
				annotationPosition(geometry), annotationSize(geometry), style,
				annotation.zIndex(), annotation.trackIndex(), figure,
				annotation.blurIntensity(), annotation.blurColor());
		draft.state = draft.state.toBuilder().annotationRegions(draft.state.annotationRegions().stream()
						.map(candidate -> candidate.id().equals(id) ? next : candidate).toList()).build();
		draft.summary.add("updated " + shortId);
		push(draft, Highlight.Kind.CHANGED, startMs, endMs, "annotation");
	}

	private static void deleteAnnotation(Draft draft, ObjectNode raw) {
		Boolean all = AgentOpReader.optionalBoolean(raw, "all");
		if (Boolean.TRUE.equals(all)) {
			draft.state = draft.state.toBuilder().annotationRegions(List.of()).build();
			draft.summary.add("deleted all annotations");
			return;
		}
		String shortId = AgentOpReader.optionalString(raw, "id");
		if (shortId == null || shortId.isBlank()) {
			throw fail("delete_annotation needs \"id\" or \"all\": true");
		}
		String id = draft.ids.annotationIdByShort().get(shortId.trim());
		if (id == null) {
			throw fail(shortId + " not found");
		}
		draft.state = draft.state.toBuilder().annotationRegions(draft.state.annotationRegions().stream()
						.filter(candidate -> !candidate.id().equals(id)).toList()).build();
		draft.summary.add("deleted " + shortId);
	}

	private static void addAudio(Draft draft, ObjectNode raw) {
		double at = AgentOpReader.number(raw, "at");
		double to = AgentOpReader.number(raw, "to");
		if (to <= at) {
			throw fail("\"to\" must be greater than \"at\"");
		}
		Double volume = AgentOpReader.optionalNumber(raw, "volume");
		if (volume != null && (volume < 0 || volume > 1)) {
			throw fail("\"volume\" must be between 0 and 1");
		}
		String path = AgentOpReader.string(raw, "path");
		String id = draft.ctx.createId().apply(IdKind.AUDIO);
		AudioRegion region = new AudioRegion(id, toMs(at), toMs(to), path,
				volume == null ? 1 : volume, false, 0);
		List<AudioRegion> regions = new ArrayList<>(draft.state.audioRegions());
		regions.add(region);
		draft.state = draft.state.toBuilder().audioRegions(regions).build();
		draft.summary.add("added audio " + shortRef("m", id) + " at "
				+ AgentIds.formatSeconds(toMs(at)) + "–" + AgentIds.formatSeconds(toMs(to)) + "s");
		push(draft, Highlight.Kind.ADDED, toMs(at), toMs(to), "audio");
	}

	private static void updateAudio(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		String id = draft.ids.audioIdByShort().get(shortId.trim());
		AudioRegion region = id == null ? null
				: draft.state.audioRegions().stream()
						.filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
		if (region == null) {
			throw fail(shortId + " not found");
		}
		Double at = AgentOpReader.optionalNumber(raw, "at");
		Double to = AgentOpReader.optionalNumber(raw, "to");
		Double volume = AgentOpReader.optionalNumber(raw, "volume");
		if (volume != null && (volume < 0 || volume > 1)) {
			throw fail("\"volume\" must be between 0 and 1");
		}
		long startMs = at == null ? region.startMs() : toMs(at);
		long endMs = to == null ? region.endMs() : toMs(to);
		if (endMs <= startMs) {
			throw fail("\"to\" must be greater than \"at\"");
		}
		AudioRegion next = new AudioRegion(region.id(), startMs, endMs, region.audioPath(),
				volume == null ? region.volume() : volume, region.normalize(),
				region.trackIndex());
		draft.state = draft.state.toBuilder().audioRegions(draft.state.audioRegions().stream()
						.map(candidate -> candidate.id().equals(id) ? next : candidate).toList()).build();
		draft.summary.add("updated " + shortId);
		push(draft, Highlight.Kind.CHANGED, startMs, endMs, "audio");
	}

	private static void deleteAudio(Draft draft, ObjectNode raw) {
		Boolean all = AgentOpReader.optionalBoolean(raw, "all");
		if (Boolean.TRUE.equals(all)) {
			draft.state = draft.state.toBuilder().audioRegions(List.of()).build();
			draft.summary.add("deleted all audio regions");
			return;
		}
		String shortId = AgentOpReader.optionalString(raw, "id");
		if (shortId == null || shortId.isBlank()) {
			throw fail("delete_audio needs \"id\" or \"all\": true");
		}
		String id = draft.ids.audioIdByShort().get(shortId.trim());
		if (id == null) {
			throw fail(shortId + " not found");
		}
		draft.state = draft.state.toBuilder().audioRegions(draft.state.audioRegions().stream()
						.filter(candidate -> !candidate.id().equals(id)).toList()).build();
		draft.summary.add("deleted " + shortId);
	}

	private static void sourceAudio(Draft draft, ObjectNode raw) {
		String clipShort = AgentOpReader.optionalString(raw, "clip");
		Boolean muted = AgentOpReader.optionalBoolean(raw, "muted");
		Double volume = AgentOpReader.optionalNumber(raw, "volume");
		if (muted == null && volume == null) {
			throw fail("source_audio needs \"muted\" and/or \"volume\"");
		}
		if (volume != null && (volume < 0 || volume > 2)) {
			throw fail("\"volume\" must be between 0 and 2");
		}
		List<String> clipIds = new ArrayList<>();
		if (clipShort != null) {
			String id = draft.ids.clipIdByShort().get(clipShort.trim());
			if (id != null) {
				clipIds.add(id);
			}
		} else {
			draft.state.clipRegions().forEach(clip -> clipIds.add(clip.id()));
		}
		if (clipIds.isEmpty()) {
			throw fail(clipShort + " not found");
		}
		if (muted != null) {
			draft.state = draft.state.toBuilder().clipRegions(draft.state.clipRegions().stream()
							.map(clip -> clipIds.contains(clip.id())
									? new ClipRegion(clip.id(), clip.startMs(), clip.endMs(),
											clip.sourceStartMs(), clip.sourceMinMs(),
											clip.sourceMaxMs(), clip.speed(), muted,
											clip.showSourceAudio(), clip.cameraLayout(),
											clip.transitionOut())
									: clip)
							.toList()).build();
		}
		if (volume != null) {
			ObjectNode byClip = draft.state.sourceAudioTrackSettingsByClip() == null
					? Json.nodeFactory().objectNode()
					: draft.state.sourceAudioTrackSettingsByClip().deepCopy();
			for (String clipId : clipIds) {
				ObjectNode trackSettings = byClip.path(clipId).isObject()
						? (ObjectNode) byClip.path(clipId).deepCopy()
						: Json.nodeFactory().objectNode();
				ObjectNode mixed = trackSettings.path("mixed").isObject()
						? (ObjectNode) trackSettings.path("mixed").deepCopy()
						: Json.nodeFactory().objectNode();
				mixed.put("volume", volume);
				mixed.put("normalize", mixed.path("normalize").isBoolean()
						&& mixed.path("normalize").asBoolean());
				trackSettings.set("mixed", mixed);
				byClip.set(clipId, trackSettings);
			}
			draft.state = draft.state.toBuilder().sourceAudioTrackSettingsByClip(byClip).build();
		}
		List<String> parts = new ArrayList<>();
		parts.add("source audio");
		if (muted != null) {
			parts.add(muted ? "muted" : "unmuted");
		}
		if (volume != null) {
			parts.add("volume " + volume);
		}
		draft.summary.add(String.join(" ", parts));
	}

	private static void addCaption(Draft draft, ObjectNode raw) {
		double[] range = AgentOpReader.readRange(raw, "from", "to");
		String text = AgentOpReader.string(raw, "text");
		long startMs = toMs(range[0]);
		long endMs = toMs(range[1]);
		CaptionCue cue = CaptionOps.createCaptionCue(startMs, endMs, text);
		draft.state = draft.state.toBuilder().autoCaptions(CaptionOps.addCue(draft.state.autoCaptions(), cue)).build();
		if (!draft.state.autoCaptionSettings().enabled()) {
			draft.state = draft.state.toBuilder().autoCaptionSettings(enabledCaptionSettings(draft.state.autoCaptionSettings())).build();
		}
		draft.summary.add("caption " + shortRef("q", cue.id()) + " "
				+ AgentIds.formatSeconds(startMs) + "–" + AgentIds.formatSeconds(endMs) + "s");
		push(draft, Highlight.Kind.ADDED,
				Clips.mapSourceTimeToTimelineTime(startMs, draft.state.clipRegions()),
				Clips.mapSourceTimeToTimelineTime(endMs, draft.state.clipRegions()), "caption");
	}

	private static AutoCaptionSettings enabledCaptionSettings(AutoCaptionSettings settings) {
		return new AutoCaptionSettings(true, settings.timelineQuickAdd(), settings.language(),
				settings.fontFamily(), settings.fontSize(), settings.bottomOffset(),
				settings.maxWidth(), settings.maxRows(), settings.animationStyle(),
				settings.boxRadius(), settings.textColor(), settings.inactiveTextColor(),
				settings.backgroundOpacity());
	}

	private static void updateCaption(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		String id = draft.ids.cueIdByShort().get(shortId.trim());
		CaptionCue existing = id == null ? null
				: draft.state.autoCaptions().stream()
						.filter(cue -> cue.id().equals(id)).findFirst().orElse(null);
		if (existing == null) {
			throw fail(shortId + " not found");
		}
		Double from = AgentOpReader.optionalNumber(raw, "from");
		Double to = AgentOpReader.optionalNumber(raw, "to");
		String text = AgentOpReader.optionalString(raw, "text");
		List<CaptionCue> cues = draft.state.autoCaptions();
		if (from != null || to != null) {
			cues = CaptionOps.retimeCue(cues, id, new CaptionOps.RetimeSpan(
					from == null ? existing.startMs() : toMs(from),
					to == null ? existing.endMs() : toMs(to)));
		}
		if (text != null) {
			String nextText = text;
			cues = cues.stream().map(cue -> cue.id().equals(id)
					? new CaptionCue(cue.id(), cue.startMs(), cue.endMs(), nextText, null)
					: cue).toList();
		}
		draft.state = draft.state.toBuilder().autoCaptions(cues).build();
		draft.summary.add("updated " + shortId);
		CaptionCue updated = cues.stream().filter(cue -> cue.id().equals(id)).findFirst()
				.orElse(existing);
		push(draft, Highlight.Kind.CHANGED,
				Clips.mapSourceTimeToTimelineTime(updated.startMs(), draft.state.clipRegions()),
				Clips.mapSourceTimeToTimelineTime(updated.endMs(), draft.state.clipRegions()),
				"caption");
	}

	private static void splitCaption(Draft draft, ObjectNode raw) {
		String shortId = AgentOpReader.string(raw, "id");
		String id = draft.ids.cueIdByShort().get(shortId.trim());
		if (id == null) {
			throw fail(shortId + " not found");
		}
		int before = draft.state.autoCaptions().size();
		List<CaptionCue> cues = CaptionOps.splitCue(draft.state.autoCaptions(), id,
				toMs(AgentOpReader.number(raw, "at")));
		if (cues.size() == before) {
			throw fail(shortId + " has too few words to split");
		}
		draft.state = draft.state.toBuilder().autoCaptions(cues).build();
		draft.summary.add("split " + shortId);
	}

	private static void mergeCaptions(Draft draft, ObjectNode raw) {
		String aShort = AgentOpReader.string(raw, "a");
		String bShort = AgentOpReader.string(raw, "b");
		String a = draft.ids.cueIdByShort().get(aShort.trim());
		String b = draft.ids.cueIdByShort().get(bShort.trim());
		if (a == null || b == null) {
			throw fail((a == null ? aShort : bShort) + " not found");
		}
		int before = draft.state.autoCaptions().size();
		List<CaptionCue> cues = CaptionOps.mergeCues(draft.state.autoCaptions(), a, b);
		if (cues.size() == before) {
			throw fail("merge_captions needs two adjacent cues");
		}
		draft.state = draft.state.toBuilder().autoCaptions(cues).build();
		draft.summary.add("merged " + aShort + " into " + bShort);
	}

	private static void deleteCaption(Draft draft, ObjectNode raw) {
		Boolean all = AgentOpReader.optionalBoolean(raw, "all");
		if (Boolean.TRUE.equals(all)) {
			draft.state = draft.state.toBuilder().autoCaptions(List.of()).build();
			draft.summary.add("deleted all captions");
			return;
		}
		String shortId = AgentOpReader.optionalString(raw, "id");
		if (shortId == null || shortId.isBlank()) {
			throw fail("delete_caption needs \"id\" or \"all\": true");
		}
		String id = draft.ids.cueIdByShort().get(shortId.trim());
		if (id == null) {
			throw fail(shortId + " not found");
		}
		draft.state = draft.state.toBuilder().autoCaptions(CaptionOps.deleteCue(draft.state.autoCaptions(), id)).build();
		draft.summary.add("deleted " + shortId);
	}

	private static void captionsStyle(Draft draft, ObjectNode raw) {
		Map<String, JsonNode> values = AgentOpReader.readValues(raw);
		draft.state = AgentSettings.applyCaptionStylePatch(draft.state, values);
		String keys = values.keySet().stream().filter(key -> !key.equals("op")
				&& !key.equals("values") && !key.equals("settings"))
				.collect(java.util.stream.Collectors.joining(", "));
		draft.summary.add("caption style " + keys);
	}

	private static void setSettings(Draft draft, ObjectNode raw) {
		Map<String, JsonNode> values = AgentOpReader.readValues(raw);
		draft.state = AgentSettings.applySettingPatch(draft.state, values);
		draft.summary.add("set " + String.join(", ", values.keySet()));
	}
}
