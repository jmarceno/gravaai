package dev.gravaai.app.editor;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.privacy.DetectedRegion;
import dev.gravaai.core.privacy.RegionTracker;
import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationRegion;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.core.project.AnnotationTextStyle;
import dev.gravaai.core.project.AnnotationType;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.services.privacy.AutoMaskService;
import dev.gravaai.services.privacy.PrivacyAssetInstaller;
import dev.gravaai.services.privacy.PrivacyAssets;
import dev.gravaai.ui.editor.AutoMaskUiService;
import dev.gravaai.ui.editor.EditorMutations;
import dev.gravaai.ui.kit.Toast;
import javafx.application.Platform;
import javafx.scene.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The editor side of auto masking: the face/text toggles, Detect/Cancel, the model download, and
 * the one undoable write that turns detected regions into {@code BLUR} annotations.
 *
 * <p>Mirrors {@link AutoCaptionController}'s shape: the JavaFX-free {@link AutoMaskService} owns
 * the run, and this class observes it, so closing the editor cannot leave a worker writing into a
 * dead window.</p>
 *
 * <p>Generated regions carry {@code "autoMask": true}. The project format preserves unknown
 * fields, so this needs no schema change and buys two things: a re-run can delete exactly the
 * regions a previous run produced (leaving hand-made blur annotations alone), and a future "remove
 * auto masks" action is a filter over the same flag.</p>
 */
public final class AutoMaskController implements AutoMaskUiService, AutoCloseable {

	/** Reserved marker on every generated region (see the class comment). */
	public static final String AUTO_MASK_FIELD = "autoMask";

	private final EditorStore store;
	private final Supplier<String> videoPath;
	private final Supplier<Double> durationSeconds;
	private final Supplier<Node> toastOwner;
	private final PrivacyAssets assets;
	private final PrivacyAssetInstaller installer;
	private final AutoMaskService service;
	private final AutoCloseable subscription;
	private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
	private volatile State state;
	private volatile AutoMaskService.@Nullable Status appliedStatus;
	private volatile boolean detecting;
	/** Set while a first-use download is in flight, so Cancel can stop it before the run exists. */
	private volatile @Nullable AtomicBoolean cancelDownload;
	/** Source-frame size of the sampled recording, from the last completed run. */
	private volatile int frameWidthPx = 1920;
	private volatile int frameHeightPx = 1080;

	public AutoMaskController(EditorStore store, Supplier<String> videoPath,
			Supplier<Double> durationSeconds, Supplier<Node> toastOwner, PrivacyAssets assets,
			PrivacyAssetInstaller installer, AutoMaskService service) {
		this.store = store;
		this.videoPath = videoPath;
		this.durationSeconds = durationSeconds;
		this.toastOwner = toastOwner;
		this.assets = assets;
		this.installer = installer;
		this.service = service;
		this.state = new State(assets.isInstalled());
		this.subscription = service.addListener(this::onServiceStatus);
	}

	/** The last progress snapshot, or an idle one before the first run. */
	public AutoMaskService.Status status() {
		return service.status();
	}

	@Override
	public Progress progress() {
		AutoMaskService.Status current = service.status();
		return new Progress(current.running(), current.progress(), current.message(),
				current.state() == AutoMaskService.State.ERROR);
	}

	@Override
	public int lastRegionCount() {
		AutoMaskService.Status current = service.status();
		if (current.state() != AutoMaskService.State.DONE) {
			return 0;
		}
		return current.regions().size();
	}

	@Override
	public State state() {
		return state;
	}

	@Override
	public AutoCloseable addListener(Consumer<State> listener) {
		listeners.add(listener);
		AutoMaskService.Status current = service.status();
		if (current.running()) {
			detecting = true;
		}
		return () -> listeners.remove(listener);
	}

	/** True while a run is in flight (the inspector shows Cancel instead of Detect). */
	@Override
	public boolean detecting() {
		return detecting || service.status().running();
	}

	/**
	 * Downloads the GLiNER model when missing, then starts an analysis pass. Downloading first
	 * keeps the failure mode simple: either the model is installed and detection runs, or the run
	 * reports why the download failed.
	 */
	@Override
	public void detect(boolean includeFaces, boolean includeText) {
		String video = videoPath.get();
		if (video.isBlank()) {
			toast("Open a recording before detecting masks", Toast.Variant.DANGER);
			return;
		}
		long durationMs = recordingDurationMs();
		if (durationMs <= 0) {
			toast("The recording has no readable duration", Toast.Variant.DANGER);
			return;
		}
		detecting = true;
		publishState();
		// Cancelling while the model is still downloading has to take effect before the run even
		// exists, so the flag is raised here and checked between download progress ticks and again
		// before start(). Without it Cancel would be a no-op for the whole multi-minute fetch.
		AtomicBoolean cancelled = new AtomicBoolean();
		cancelDownload = cancelled;
		// The download is slow (hundreds of MB) and the detector needs the files, so it runs off
		// the FX thread; start() itself only queues work on the service's daemon.
		Threads.startVirtual("gravaai-automask-download", () -> {
			try {
				// isInstalled() checks each file's pinned size, so a truncated download heals by
				// re-fetching instead of failing every run against a half-written model.
				if (includeText && !installer.isInstalled()) {
					installer.ensure(percent -> {
						if (cancelled.get()) {
							throw new IllegalStateException("Auto-mask model download cancelled");
						}
						publishDownloadProgress(percent);
					});
				}
				if (cancelled.get()) {
					detecting = false;
					publishState();
					return;
				}
				AutoMaskService.Options options = new AutoMaskService.Options(Path.of(video),
						durationMs, includeFaces, includeText);
				// Starting the service is thread-safe, and going through the FX queue would stall
				// every headless caller (tests, tooling) that has no toolkit. Publishing state is
				// the part that has to reach the FX thread, and publishState does that.
				service.start(options);
				detecting = true;
				publishState();
			} catch (RuntimeException error) {
				detecting = false;
				// A cancel is not a failure: the user asked for it, so no error toast.
				if (!cancelled.get()) {
					toast(error.getMessage() == null ? "Could not download the auto-mask model"
							: error.getMessage(), Toast.Variant.DANGER);
				}
				publishState();
			}
		});
	}

	/** Cancels the in-flight run, or the model download that precedes it. */
	@Override
	public void cancel() {
		AtomicBoolean download = cancelDownload;
		if (download != null) {
			download.set(true);
		}
		detecting = false;
		service.cancel();
		publishState();
	}

	/** Downloads the model without starting a detection run (the inspector's Download button). */
	@Override
	public void downloadModel() {
		Threads.startVirtual("gravaai-automask-download", () -> {
			try {
				installer.ensure(this::publishDownloadProgress);
				Platform.runLater(() -> {
					toast("Auto-mask model installed", Toast.Variant.SUCCESS);
					publishState();
				});
			} catch (RuntimeException error) {
				toast(error.getMessage() == null ? "Could not download the auto-mask model"
						: error.getMessage(), Toast.Variant.DANGER);
			}
		});
	}

	/**
	 * Surfaces download progress through the same status the inspector renders, so a 580 MB fetch
	 * is visible instead of showing a 0 % bar for minutes.
	 */
	private void publishDownloadProgress(int percent) {
		service.reportDownloadProgress(percent);
	}

	/** Removes every region a previous run generated, leaving hand-made blur annotations. */
	@Override
	public void removeAutoRegions() {
		runOnFx(() -> EditorMutations.update(store, json -> {
			if (!(json.get("annotationRegions") instanceof ArrayNode regions)) {
				return;
			}
			List<ObjectNode> kept = new ArrayList<>();
			for (var node : regions) {
				if (!(node.isObject() && node.get(AUTO_MASK_FIELD) != null
						&& node.get(AUTO_MASK_FIELD).asBoolean())) {
					kept.add((ObjectNode) node);
				}
			}
			ArrayNode next = json.putArray("annotationRegions");
			kept.forEach(next::add);
		}));
	}

	private void onServiceStatus(AutoMaskService.Status status) {
		if (status.running()) {
			detecting = true;
			publishState();
			return;
		}
		detecting = false;
		if (status == appliedStatus) {
			publishState();
			return;
		}
		switch (status.state()) {
			case DONE -> {
				appliedStatus = status;
				AutoMaskService.Result result = status.result();
				runOnFx(() -> {
					if (result != null) {
						applyRegions(result);
					}
					showToast(status.message(), Toast.Variant.SUCCESS);
				});
			}
			case ERROR -> {
				appliedStatus = status;
				toast(status.message(), Toast.Variant.DANGER);
			}
			case CANCELLED -> appliedStatus = status;
			default -> {
				// progress only
			}
		}
		publishState();
	}

	/**
	 * One undoable command: drop the regions a previous run produced, then add this run's. Lanes
	 * are allocated per region against the surviving annotations, so two overlapping masks never
	 * land on the same timeline lane.
	 */
	private void applyRegions(AutoMaskService.Result result) {
		List<RegionTracker.TimedRegion> regions = result.regions();
		if (result.frameWidth() > 0 && result.frameHeight() > 0) {
			frameWidthPx = result.frameWidth();
			frameHeightPx = result.frameHeight();
		}
		double frameDuration = durationSeconds.get();
		if (frameDuration <= 0) {
			toast("The recording has no readable duration", Toast.Variant.DANGER);
			return;
		}
		EditorMutations.update(store, json -> {
			ArrayNode existing = json.get("annotationRegions") instanceof ArrayNode array
					? array : json.putArray("annotationRegions");
			// Keep hand-made annotations; everything auto-generated is replaced by this run.
			var previous = store.state().annotationRegions().stream()
					.filter(region -> !isAutoMask(json, region.id())).toList();
			List<AnnotationRegion> next = new ArrayList<>(previous);
			int zIndex = store.nextIndex("annotation-zIndex");
			for (RegionTracker.TimedRegion timed : regions) {
				AnnotationRegion annotation = toAnnotation(timed, next, zIndex++, frameDuration);
				next.add(annotation);
			}
			ArrayNode written = ProjectDocument.writeAnnotationRegions(next);
			// Re-write the provenance flag: the record carries no field for it, so it rides on
			// the JSON node the writer produced.
			for (int i = 0; i < written.size(); i++) {
				boolean generated = i >= previous.size();
				if (generated) {
					((ObjectNode) written.get(i)).put(AUTO_MASK_FIELD, true);
				}
			}
			json.set("annotationRegions", written);
		});
	}

	/** Whether the stored annotation with {@code id} was produced by a previous auto-mask run. */
	private static boolean isAutoMask(JsonNode json, String id) {
		if (!(json.get("annotationRegions") instanceof ArrayNode array)) {
			return false;
		}
		for (var node : array) {
			if (node.isObject() && id.equals(node.path("id").asString(""))
					&& node.get(AUTO_MASK_FIELD) != null && node.get(AUTO_MASK_FIELD).asBoolean()) {
				return true;
			}
		}
		return false;
	}

	/** Converts one tracked pixel region into a percent-coordinate blur annotation. */
	private AnnotationRegion toAnnotation(RegionTracker.TimedRegion timed,
			List<AnnotationRegion> existing, int zIndex, double frameDurationSeconds) {
		DetectedRegion region = timed.region();
		// Annotations are percent of the video mask rect. Detections are in source pixels and an
		// uncropped recording (the default) has the source frame as its mask rect, so the frame
		// size the analysis sampled is the right denominator. A crop/zoom edit re-runs the
		// analysis, the same re-run model captions use.
		double frameWidth = Math.max(1, frameWidthPx);
		double frameHeight = Math.max(1, frameHeightPx);
		double x = region.left() / frameWidth * 100.0;
		double y = region.top() / frameHeight * 100.0;
		double width = region.width() / frameWidth * 100.0;
		double height = region.height() / frameHeight * 100.0;
		int lane = freeLane(existing, timed.startMs(), timed.endMs());
		List<String> ids = existing.stream().map(AnnotationRegion::id).toList();
		String id = store.nextId("annotation", ids);
		return new AnnotationRegion(id, timed.startMs(), timed.endMs(), AnnotationType.BLUR, "",
				null, null, new AnnotationPosition(x, y), new AnnotationSize(width, height),
				AnnotationTextStyle.defaultStyle(), zIndex, lane, null,
				(double) AnnotationRegion.BLUR_ANNOTATION_STRENGTH, null, null, null);
	}

	/** The lowest lane with no block overlapping {@code [start, end)} (the annotation precedent). */
	private static int freeLane(List<AnnotationRegion> regions, long start, long end) {
		int lane = 0;
		while (true) {
			int candidate = lane;
			boolean taken = regions.stream().anyMatch(region ->
					(region.trackIndex() == null ? 0 : region.trackIndex()) == candidate
							&& region.startMs() < end && start < region.endMs());
			if (!taken) {
				return lane;
			}
			lane++;
		}
	}

	private long recordingDurationMs() {
		double seconds = durationSeconds.get();
		return seconds <= 0 ? 0 : Math.round(seconds * 1000);
	}

	private void publishState() {
		boolean installed = assets.isInstalled();
		State next = new State(installed);
		if (next.equals(state)) {
			return;
		}
		state = next;
		// The controller publishes from a worker thread; listeners rebuild JavaFX nodes, so the
		// notification has to land on the FX thread.
		Runnable notify = () -> {
			for (Consumer<State> listener : listeners) {
				listener.accept(next);
			}
		};
		runOnFx(notify);
	}

	/**
	 * Runs {@code action} on the FX thread, or inline when there is no toolkit (tests, tools).
	 *
	 * <p>Only the {@code runLater} call is inside the {@code catch}: if the action itself throws an
	 * {@link IllegalStateException}, that is a real failure, and catching it here would run the
	 * action a second time on the caller's thread.</p>
	 */
	private static void runOnFx(Runnable action) {
		if (Platform.isFxApplicationThread()) {
			action.run();
			return;
		}
		try {
			Platform.runLater(action);
		} catch (IllegalStateException noToolkit) {
			action.run();
		}
	}

	private void toast(String text, Toast.Variant variant) {
		runOnFx(() -> showToast(text, variant));
	}

	private void showToast(String text, Toast.Variant variant) {
		Node owner = toastOwner.get();
		if (owner != null && owner.getScene() != null && text != null && !text.isBlank()) {
			Toast.show(owner, text).variant(variant);
		}
	}

	@Override
	public void close() {
		try {
			subscription.close();
		} catch (Exception ignored) {
			// best effort
		}
		service.close();
	}
}
