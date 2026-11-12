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
			"color", "annotations", "elements", "audio", "captions", "markers", "settings",
			"sources", "history", "inspect", "project", "export", "meeting", "frames", "jobs",
			"conventions");

	/** {@code HELP_INDEX}. */
	public static final String INDEX = """
			Gravaai — call inspect {view:"help", topic:"<name>"} for one topic.
			edit applies a batch of ops atomically (any failure → nothing changes).

			clips        cut keep split delete_clip move_clip clip trim_clip
			zoom         zoom update_zoom delete_zoom auto_zoom
			transcript   cut_words restore_words cut_fillers cut_pauses cut_range_src
			speed        speed delete_speed
			color        grade update_grade delete_grade — brightness/contrast/saturation/temperature/gamma over a span
			annotations  text image figure blur spotlight update_annotation delete_annotation
			elements     component delete_component element update_element delete_element asset delete_asset — animated JS components (title cards, lower thirds, Lottie, shaders, 3D, anything you can draw)
			audio        audio update_audio delete_audio source_audio
			captions     caption update_caption split_caption merge_captions delete_caption captions_style
			markers      marker update_marker delete_marker — named points on the recording; chapters export to MP4 metadata + chapters.txt
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
			Short ids are stable per read: c1 clip, z1 zoom, s1 speed, a1 annotation, e1 element, m1 audio, q1 caption cue, w1 transcript word, g1 color grade. Within one edit batch every id means what your last read showed, even after earlier ops renumber things; re-read after the batch.
			Every read returns rev. edit accepts rev; if the project changed since you read it, the edit fails with STALE + a fresh overview — re-read and retry.
			edit ops run in order on a copy; one failing op rolls the whole batch back and names its index.""".stripTrailing();

	private static final String CLIPS = """
			cut {from,to} — ripple-delete a timeline range.
			keep {ranges:[[a,b],…]} — delete everything outside the ranges.
			split {at} — split the clip containing that time.
			delete_clip {id}
			move_clip {id,toIndex} — 0-based sequence index.
			clip {id, speed?, muted?, transition?:{kind:"fade-black",ms}|null}
			camera_layout {from, to?, layout, transition?:cut|smooth, transitionOut?:cut|smooth, size?, width?, height?, roundness?, shadow?, margin?, eyeContact?} — the camera uses layout from "from" until "to" (default: the next camera switch or the end). transition is how the camera arrives, transitionOut how it leaves into the gap after "to" (default both: cut). layout: split-right|split-left|split-top|split-bottom|top-left|top-center|top-right|center-left|center|center-right|bottom-left|bottom-center|bottom-right|camera-only|hide-camera. size/width/height 10–100 (width/height are the split share), roundness 0–100, shadow 0–1, margin 0–96 px, eyeContact 0–25 degrees of gaze lift (0 switches eye contact off there, a positive angle on); omitted ones use the webcam settings.
			delete_camera_layout {at} | {all:true} — that stretch goes back to the project webcam layout.
			trim_clip {id, start?, end?} — timeline seconds; reveals/hides footage.
			Clips are always re-packed: you never create gaps. Zoom/speed/annotation/audio regions follow the footage they cover.""".stripTrailing();

	private static final String ZOOM = """
			zoom {from,to, depth? 1–6, focus?:{x,y}} — depth sets the zoom factor: 1=1.25x, 2=1.5x, 3=1.8x (default), 4=2.2x, 5=3.5x, 6=5x. focus is the frame point (0–1 across/down) that stays centred in the output while zoomed.
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

	private static final String COLOR = """
			grade {from,to, brightness?, contrast?, saturation?, temperature?, gamma?, targets?} — a color/luminosity grade over that timeline span; omitted sliders stay neutral.
			  brightness -100..100 (additive light), contrast -100..100, saturation -100..100 (0 = grayscale), temperature -100..100 (positive = warm, negative = cool), gamma 0.2..2.4 (1 = neutral, >1 lifts midtones).
			  targets "main"(default)|"camera"|"both" — which picture the grade shades.
			update_grade {id, from?,to?, brightness?, contrast?, saturation?, temperature?, gamma?, targets?}
			delete_grade {id} | {all:true}
			Grades shade the recorded screen by default (webcam, captions and overlays keep their look); targets camera or both shades the camera too. Grades shading the same picture may not overlap; over one span, add two grades with different targets so screen and camera each get their own calibration.""".stripTrailing();

	private static final String ANNOTATIONS = """
			text {from,to,text, x?,y?,w?,h?, style?:{fontSize?,bold?,italic?,color?,backgroundColor?,align?}}
			image {from,to,path, x?,y?,w?,h?} — path must exist locally.
			figure {from,to,arrow:up|down|left|right|up-right|up-left|down-right|down-left, x?,y?,w?,h?, color?}
			blur {from,to, x?,y?,w?,h?}
			spotlight {from,to, x?,y?,w?,h?, dim?, feather?} — dims the frame outside the rectangle while the region is active; dim 0–100 (default 60), feather 0–100 (default 25).
			loupe {from,to, magnification?} — circular magnifying glass that follows the cursor (no x/y); magnification 1.2–4, default 2.
			update_annotation {id, from?,to?,text?,color?,arrow?,x?,y?,w?,h?,magnification?,dim?,feather?}
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

	private static final String MARKERS = """
			marker {at, name?, chapter?} — place a marker at a SOURCE time (the recording's own clock, like captions). name defaults to "Marker N"; chapter:true promotes it.
			update_marker {id, at?, name?, chapter?} — retime (source seconds), rename, promote to a chapter or demote it.
			delete_marker {id} | {all:true}
			Markers ride the footage: trims, cuts and reorders move them with it, and a marker whose footage was cut away disappears from the timeline (it is not deleted).
			Chapters are exported as MP4 chapter metadata plus a YouTube-ready chapters.txt sidecar; when the first chapter starts after 00:00 an "Intro" chapter at 00:00 is added, because YouTube ignores lists that do not start there.
			inspect {view:"timeline"} lists markers as k1, k2 … with their edited-timeline time and their source time.""".stripTrailing();

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

	private static final String ELEMENTS = """
			Elements are animated graphics drawn by code (Remotion-style): a COMPONENT is a JS module you write; an ELEMENT places it on the timeline with props.
			component {name, source, description?} — create/replace a project component. It is compiled and test-rendered before the batch commits (0%, 50%, last frame, plus a determinism check); errors come back with line:column.
			delete_component {name} — refused while elements use it.
			element {component, from, to?, x?, y?, w?, h?, props?, layer?, space?, motion?, motionBlur?} — place one; returns its e id. to defaults to the component's durationInFrames. x,y = centre, w,h = size, as 0–1 fractions (default: whole frame). layer = z order (default: on top).
			update_element {id, component?, from?, to?, x?, y?, w?, h?, props?, layer?, space?, motion?, motionBlur?} — props merge into the old ones; a null prop removes it; motion replaces (motion:[] clears).
			delete_element {id} | {all:true}
			space: "screen" (default; fixed over the frame) or "scene" (inside the camera: follows zoom and pan, for callouts on screen content; gravaai/callout defaults to scene).
			Composite order: scene-space elements draw in the scene phase (after the cursor) — UNDER the webcam picture and UNDER screen-space elements, which draw last over the finished frame. A full-frame opaque screen-space element (e.g. gravaai/shader-backdrop at full frame) therefore silently hides every scene-space element: inspect shows it rendering with no errors while frames show nothing.
			Zooms transform only the scene (recording, wallpaper, scene-space elements); screen-space elements never move under a zoom, so a punch-in shows nothing when the content is drawn by screen-space elements — switch those to space:"scene" (pixel-identical at rest).
			motion (no-code animation of the whole box, on top of what the component draws): a list of presets and tracks.
			  presets: fade rise slide-left slide-right slide-up slide-down spring-pop zoom spin — "fade" = in+out over 0.4s, or {preset:"rise", in?:true, out?:true, duration?:0.4}
			  tracks: {property: opacity|translateX|translateY|scale|rotate, keyframes:[{at, value, easing?}]} — at in seconds from the element start, negative = before its end; translate in % of the frame, rotate in degrees; easing names: linear ease ease-in ease-out ease-in-out ease-{in|out|in-out}-{quad|cubic|quart|quint|sine|circ|expo|back|elastic|bounce} bounce spring cubic-bezier(a,b,c,d)
			  e.g. motion:["fade", {preset:"slide-up", out:false, duration:0.6}, {property:"rotate", keyframes:[{at:0, value:-8}, {at:1, value:0, easing:"spring"}]}]
			motionBlur {samples:2–16, shutter:0–4 frames} (or true = 6 samples, 1 frame; false = off): draws trails of fast motion.
			asset {name, path, kind?} — embed a local file in the project (absolute path; kind from the extension: image .png/.jpg/.webp, lottie .json, font .ttf/.otf, model .glb/.gltf with embedded buffers); max 24 MB. delete_asset {name}.
			Built-ins (read any with inspect {view:"components", topic:"gravaai/title-card"} and fork it): gravaai/title-card, gravaai/lower-third, gravaai/counter, gravaai/callout, gravaai/lottie {asset}, gravaai/shader-backdrop {style: aurora|waves|plasma}, gravaai/model-3d {model}.

			Module contract (plain JS ES module, no JSX, no imports):
			  export const fps = 30;               // nominal; frame = ms on the element * fps / 1000 (fractional between frames)
			  export const durationInFrames = 90;  // default element length
			  export const defaultProps = {title: "Hi"};
			  export const propsSchema = {title: "string", size: {type: "number", min: 10, max: 200}, mode: {type: "enum", values: ["a","b"]}};  // types: string number boolean color enum object array any
			  export const description = "one line";
			  export function render(ctx, {frame, fps, durationInFrames, timeMs, width, height, props, random}) { … }
			render must be a pure function of its arguments: it is called for frames in any order, on several threads, maybe twice. No module-level state that changes, no timers/promises/fetch/imports. Math.random, Date.now and performance.now are frozen per frame; prefer random("seed").
			width/height = the element box in design pixels (a 1920-wide frame), so sizes look the same at every export resolution. ctx is translated and clipped to the box.
			Globals (same semantics as Remotion): interpolate(input, inRange, outRange, {easing?, extrapolateLeft?, extrapolateRight?: "extend"|"clamp"|"identity"|"wrap"}), spring({frame, fps, config?:{mass, damping, stiffness, overshootClamping}, from?, to?, delay?, durationInFrames?, reverse?}), measureSpring({fps, config}), Easing.{linear, ease, quad, cubic, sin, circle, exp, bounce, poly(n), back(s), elastic(b), bezier(x1,y1,x2,y2), steps(n), in(f), out(f), inOut(f)}, random(seed), sequence(frame, from, durationInFrames, fn(localFrame)), series(frame, [{durationInFrames, render(localFrame)}…]).
			Asset globals: image(name) → for ctx.drawImage(img, x, y, w?, h?) and ctx.createPattern(img, "repeat"); lottie(name) → {duration, fps, width, height, draw(ctx, seconds, x?, y?, w?, h?)}; shader(sksl, {uniform: number|[numbers]}) → a fillStyle/strokeStyle (SkSL: half4 main(float2 coord), coord in design pixels); scene3d(ctx, {model, x?, y?, width?, height?, rotation?:[x,y,z] degrees, position?:[x,y,z], scale?, cameraDistance?:3, fov?:35, ambient?, specular?, tint?, light?:{color, direction:[x,y,z]}}) draws a glTF model asset or a built-in shape gravaai:cube|sphere|torus|torus-knot (normalised to a unit sphere) into the rect x/y/width/height — x,y is the TOP-LEFT of the viewport rect, not a centre. model only accepts a .glb/.gltf asset (embedded buffers) or a gravaai: shape; an image asset fails — to wrap a flat image on a 3D plane, generate a minimal glTF with the image as an embedded texture and embed it with asset {kind:"model"}. assets(kind) lists the project's asset names. ctx.font may name a font asset (by asset name or family).
			ctx is HTML Canvas 2D: fillStyle strokeStyle (CSS colours or gradients) lineWidth lineCap lineJoin miterLimit globalAlpha globalCompositeOperation shadowColor shadowBlur shadowOffsetX/Y font ("bold 48px sans-serif") textAlign textBaseline filter ("blur(4px)", "drop-shadow(4px 6px 8px rgba(0,0,0,.5))") lineDashOffset; save restore translate rotate scale transform setTransform resetTransform; beginPath closePath moveTo lineTo quadraticCurveTo bezierCurveTo arc ellipse rect roundRect(x,y,w,h,r|[r…]) fill stroke clip setLineDash getLineDash; fillRect strokeRect clearRect fillText strokeText measureText (width, actualBoundingBox*); createLinearGradient createRadialGradient (addColorStop) createPattern drawImage.
			Not available: DOM, images from URLs, putImageData, getImageData.
			Budget: keep render under ~4 ms per frame (inspect overview lists the slowest elements). Draw calls are batched, so loops of fillRect/arc are cheap; reading ctx state (measureText, ctx.fillStyle) inside a hot loop is not.
			A component that throws draws a red error box in preview and frames, is listed under "element errors" in inspect overview, and fails exports. Fix it with component (same name) and look again.
			Workflow: write component → element → frames {from,to,count:6} over its span → adjust → export.
			Example:
			  component {name:"badge", source:"export const defaultProps={text:'NEW'};\\nexport function render(ctx,{frame,fps,width,height,props}){const s=spring({frame,fps,config:{damping:12}});ctx.translate(width/2,height/2);ctx.scale(s,s);ctx.fillStyle='#ff3b6b';ctx.beginPath();ctx.arc(0,0,Math.min(width,height)*0.4,0,Math.PI*2);ctx.fill();ctx.fillStyle='#fff';ctx.font='bold 64px sans-serif';ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillText(props.text,0,0);}"}
			  element {component:"badge", from:2, to:4, x:0.85, y:0.2, w:0.2, h:0.3, props:{text:"HOT"}}""".stripTrailing();

	private static final String INSPECT = """
			inspect {view?, from?, to?, topic?}
			overview (default) project, source/edited duration, aspect, counts, webcam, rev
			timeline  one line per clip/zoom/speed/annotation/element/audio in from–to (seconds)
			components element components (built-in + project): fps, length, props; project assets; topic:<name> returns its source
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
			It shows the preview as the user sees it: wallpaper, padding, zoom, cursor, webcam, annotations, elements, captions.
			To review an animation, use from/to/count over the element span (a contact sheet shows the curve).
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
			case "color" -> COLOR;
			case "annotations" -> ANNOTATIONS;
			case "elements" -> ELEMENTS;
			case "audio" -> AUDIO;
			case "captions" -> CAPTIONS;
			case "markers" -> MARKERS;
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
