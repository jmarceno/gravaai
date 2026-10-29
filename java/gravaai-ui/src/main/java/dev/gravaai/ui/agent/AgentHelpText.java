package dev.gravaai.ui.agent;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The on-demand op reference for the agent tools (port of
 * {@code src/components/video-editor/agent/help.ts}, plan §5.16/P3.2): the MCP tool list stays
 * tiny and `inspect {view:"help", topic:"<name>"}` fetches one topic.
 *
 * <p>This lives in the UI module because the help text is part of the editor/agent surface, like the
 * TS renderer's help.ts; AppContext wires it into {@code AgentDispatcher.HelpPort} with
 * {@code topic -> AgentHelpText.help(topic)}.
 */
public final class AgentHelpText {

	/** {@code HELP_TOPICS}: the topic names in help order. */
	public static final List<String> TOPICS = List.of("clips", "zoom", "transcript", "speed",
			"annotations", "audio", "captions", "settings", "sources", "history", "inspect",
			"project", "export", "meeting", "frames", "jobs", "conventions");

	/** {@code HELP_INDEX}. */
	public static final String INDEX = """
			Gravaai — call inspect {view:"help", topic:"<name>"} for one topic.
			edit applies a batch of ops atomically (any failure → nothing changes).

			clips        cut keep split delete_clip move_clip clip trim_clip
			zoom         zoom update_zoom delete_zoom auto_zoom
			transcript   cut_words restore_words cut_fillers cut_pauses cut_range_src
			speed        speed delete_speed
			annotations  text image figure blur update_annotation delete_annotation
			audio        audio update_audio delete_audio source_audio
			captions     caption update_caption split_caption merge_captions delete_caption captions_style
			settings     set {key: value} — any key from inspect {view:"settings"}
			history      undo n | redo n | revert_ai (send alone)
			sources      audio/image paths, ids and where they come from
			inspect      overview timeline transcript settings library jobs
			project      open new save save_as close
			frames       look at the edit: single frames or a contact sheet
			jobs         transcribe / export / meeting: start status wait cancel
			conventions  seconds, short ids, rev/STALE""".stripTrailing();

	private static final String CONVENTIONS = """
			Times are seconds on the EDITED timeline (2 decimals) unless an op says src/transcript.
			Short ids are stable per read: c1 clip, z1 zoom, s1 speed, a1 annotation, m1 audio, q1 caption cue, w1 transcript word. Within one edit batch every id means what your last read showed, even after earlier ops renumber things; re-read after the batch.
			Every read returns rev. edit accepts rev; if the project changed since you read it, the edit fails with STALE + a fresh overview — re-read and retry.
			edit ops run in order on a copy; one failing op rolls the whole batch back and names its index.""".stripTrailing();

	private static final String CLIPS = """
			cut {from,to} — ripple-delete a timeline range.
			keep {ranges:[[a,b],…]} — delete everything outside the ranges.
			split {at} — split the clip containing that time.
			delete_clip {id}
			move_clip {id,toIndex} — 0-based sequence index.
			clip {id, speed?, muted?, cameraLayout?:default|hide-camera|camera-only, transition?:{kind:"fade-black",ms}|null}
			trim_clip {id, start?, end?} — timeline seconds; reveals/hides footage.
			Clips are always re-packed: you never create gaps. Zoom/speed/annotation/audio regions follow the footage they cover.""".stripTrailing();

	private static final String ZOOM = """
			zoom {from,to, depth? 1–6, focus?:{x,y}} — depth 1 is closest, 6 tightest; focus is 0–1 across/down the frame.
			update_zoom {id, from?,to?,depth?,focus?}
			delete_zoom {id} | {all:true}
			auto_zoom {from?,to?,replace?} — one zoom per click cluster from the recording's cursor telemetry.
			Zooms may not overlap; the error names the region to update or delete.""".stripTrailing();

	private static final String TRANSCRIPT = """
			cut_words {ids:[w…]} | {text:"exact phrase"} | {all:true} — cuts source footage, ripples the timeline.
			restore_words {ids:[w…]} — puts cut words back (only footage a cut borders can be restored).
			cut_fillers {lang?} — um/uh/hmm (en) or hã/hum (pt).
			cut_pauses {min=1.0, keep=0.25} — remove silence between words, keeping keep seconds.
			cut_range_src {from,to} — source recording seconds.
			Words live in source time: read them with inspect {view:"transcript"}; cut words show as ~w12.""".stripTrailing();

	private static final String SPEED = """
			speed {from,to,rate} — rate is one of 0.25,0.5,0.75,1.25,1.5,1.75,2.
			delete_speed {id} | {all:true}
			Speed regions are timeline overlays; to actually shorten footage use clip {speed} instead.""".stripTrailing();

	private static final String ANNOTATIONS = """
			text {from,to,text, x?,y?,w?,h?, style?:{fontSize?,bold?,italic?,color?,backgroundColor?,align?}}
			image {from,to,path, x?,y?,w?,h?} — path must exist locally.
			figure {from,to,arrow:up|down|left|right|up-right|up-left|down-right|down-left, x?,y?,w?,h?, color?}
			blur {from,to, x?,y?,w?,h?}
			update_annotation {id, from?,to?,text?,color?,arrow?,x?,y?,w?,h?}
			delete_annotation {id} | {all:true}
			x,y is the centre and w,h the size, all as 0–1 fractions of the frame (defaults .5,.5,.3,.2).""".stripTrailing();

	private static final String AUDIO = """
			audio {path, at, to, volume?} — timeline seconds; imported audio keeps its length through ripple edits.
			update_audio {id, at?,to?,volume?}
			delete_audio {id} | {all:true}
			source_audio {clip?, muted?, volume?} — the recording's own audio track (clip omitted = all clips).""".stripTrailing();

	private static final String CAPTIONS = """
			caption {from,to,text} — times are SOURCE seconds (captions ride the recording, not the timeline).
			update_caption {id, text?,from?,to?}
			split_caption {id, at}
			merge_captions {a,b} — must be adjacent.
			delete_caption {id} | {all:true}
			captions_style {…any autoCaptionSettings key}, e.g. {fontSize:40, textColor:"#FFFFFF"}""".stripTrailing();

	private static final String SETTINGS = """
			set {key: value, …} — dotted paths allowed (padding.top, webcam.positionPreset, cropRegion.x).
			Keys are exactly what inspect {view:"settings"} prints. Values are validated through the project normalizer; out-of-range or derived values fail instead of being silently dropped.
			Timeline arrays (clips, zooms, captions…) have dedicated ops and cannot be replaced with set.""".stripTrailing();

	private static final String SOURCES = """
			Local paths come from inspect {view:"library"} (projects, recordings, meetings).
			image/audio ops take absolute paths; the editor reads them and fails if the file is missing.
			Projects live in the app's Projects folder; recordings and meetings are listed by the library view.""".stripTrailing();

	private static final String HISTORY = """
			undo {n?} | redo {n?} — same as the editor's Ctrl+Z/Ctrl+Shift+Z.
			revert_ai — restore the full project (timeline AND settings) from before the last AI batch.
			These three must be the only op in their batch, and they are applied by the editor, not by the op core.""".stripTrailing();

	private static final String INSPECT = """
			inspect {view?, from?, to?, topic?}
			overview (default) project, source/edited duration, aspect, counts, webcam, rev
			timeline  one line per clip/zoom/speed/annotation/audio in from–to (seconds)
			transcript words grouped by cue, cut words marked ~, pause markers, paged by from–to (source seconds)
			settings  flat key=value list; add topic to narrow and see ranges
			library   projects + unprojected recordings and meetings, newest first
			jobs      running/finished jobs with progress
			help      this reference; topic narrows it""".stripTrailing();

	private static final String PROJECT = """
			project {action}
			open {path, force?} — a .gravaai project, a recording, or a meeting media path.
			new {path, name?, force?} — new project from a recording/media path.
			Both refuse while the open project has unsaved changes; force:true saves it first.
			save — save in place; a never-saved project is created in the Projects folder.
			save_as {name} — save a copy under that name in the Projects folder.
			close {force?} — closes the editor window; refuses with unsaved changes unless force:true (saves first).""".stripTrailing();

	private static final String EXPORT = """
			job {action:"start", kind:"export", options} renders with the editor's own exporter.
			options.format   "mp4" (default) or "gif"
			options.quality  medium | good | high | source (mp4): 60% | 75% | 90% | 100% of the recording's size; a 1080p recording needs "source" for a 1080p file
			options.fps      24 | 30 | 60 (mp4)
			options.path     absolute file path; without it the export lands in the normal export folder
			options.captionsSidecar  true/false: also write .srt/.vtt next to the video
			start returns the job id; job {action:"wait", id} blocks up to 300s and reports progress; cancel stops the render.
			The preview jumps while it renders; the export-finished automation fires as usual.""".stripTrailing();

	private static final String MEETING = """
			job {action:"start", kind:"meeting", options} transcribes and summarizes a meeting in main.
			options.path  meeting media path from inspect {view:"library"}; omit it for the newest meeting
			options.mode  "full" (transcript + notes, default), "transcribe" or "summarize"
			The result carries transcriptPath and notesPath: read them with your own tools.
			One meeting job runs at a time (Whisper and the local model never share the machine).""".stripTrailing();

	private static final String FRAMES = """
			frames {at?, from?, to?, count?, width?} returns rendered preview images (JPEG) of the open project.
			at:[12.4,30]   one image per time
			from,to,count  count evenly spaced frames (1-9, default 4) tiled into one contact sheet
			width          output width per frame, default 640
			It shows the preview as the user sees it: wallpaper, padding, zoom, cursor, webcam, annotations, captions.
			The playhead is restored afterwards; the preview shows "AI is looking" while it captures.
			Use it after an edit batch, or before deciding where to cut.""".stripTrailing();

	private static final String JOBS = """
			job {action, kind?, id?, options?, timeout?}
			start   kind transcribe: Whisper captions for the open project
			          options.language  e.g. "en", "pt"; default: the editor's caption language
			          options.model     absolute path to a ggml .bin model; default: the model picked in the editor
			        kind export: render mp4/gif (topic export)
			        kind meeting: transcribe/summarize a meeting (topic meeting)
			        Returns the job id at once; progress arrives as MCP progress notifications.
			status  id, or the newest job of that kind: state, percent, message, result
			wait    same, blocks until it finishes or timeout seconds (default 60, max 300)
			cancel  stops the work
			Only one job of a kind runs at a time.""".stripTrailing();

	private AgentHelpText() {
	}

	/** {@code getHelp}: the index for a blank topic, the prefixed topic text, or an unknown-topic line. */
	public static String help(@Nullable String topic) {
		if (topic == null || topic.trim().isEmpty()) {
			return INDEX;
		}
		String normalized = topic.trim().toLowerCase(Locale.ROOT);
		String body = topicText(normalized);
		if (body == null) {
			return "unknown help topic \"" + topic + "\"; topics: " + String.join(", ", TOPICS);
		}
		return normalized + "\n" + body;
	}

	private static @Nullable String topicText(String topic) {
		return switch (topic) {
			case "clips" -> CLIPS;
			case "zoom" -> ZOOM;
			case "transcript" -> TRANSCRIPT;
			case "speed" -> SPEED;
			case "annotations" -> ANNOTATIONS;
			case "audio" -> AUDIO;
			case "captions" -> CAPTIONS;
			case "settings" -> SETTINGS;
			case "sources" -> SOURCES;
			case "history" -> HISTORY;
			case "inspect" -> INSPECT;
			case "project" -> PROJECT;
			case "export" -> EXPORT;
			case "meeting" -> MEETING;
			case "frames" -> FRAMES;
			case "jobs" -> JOBS;
			case "conventions" -> CONVENTIONS;
			default -> null;
		};
	}
}
