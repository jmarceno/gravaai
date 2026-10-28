package dev.gravaai.services.automations;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Trigger hooks used by the rest of the services (port of
 * {@code electron/automations/triggers.ts}).
 *
 * <p>Everything is fire-and-forget: callers never await automations, so recording/export/meeting
 * completion is never delayed or failed by a rule. The dedupe map and clock are instance state
 * (injected), not process globals.
 */
public final class AutomationTriggers {

	/** The same export can be reported by more than one success path. */
	public static final long EXPORT_FINISHED_DEDUPE_MS = 10_000;

	private static final Pattern WEBCAM_SUFFIX = Pattern.compile("-webcam\\.[^.]+$", Pattern.CASE_INSENSITIVE);

	private final Consumer<AutomationEvent> dispatch;
	private final LongSupplier clock;
	private final Map<String, Long> recentExports = new LinkedHashMap<>();

	/**
	 * @param dispatch the fire-and-forget sink (usually the automation service, which runs the
	 *     engine on a virtual thread)
	 */
	public AutomationTriggers(Consumer<AutomationEvent> dispatch, LongSupplier clock) {
		this.dispatch = dispatch;
		this.clock = clock;
	}

	public AutomationTriggers(Consumer<AutomationEvent> dispatch) {
		this(dispatch, System::currentTimeMillis);
	}

	/** Port of {@code formatForExportPath}: only mp4 and gif exports fire the trigger. */
	public static AutomationExportFormat formatForExportPath(String filePath) {
		String extension = filePath.substring(filePath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		if (extension.equals("gif")) {
			return AutomationExportFormat.GIF;
		}
		if (extension.equals("mp4")) {
			return AutomationExportFormat.MP4;
		}
		return null;
	}

	/** Returns false when the same path was already reported inside the dedupe window. */
	public synchronized boolean shouldTriggerExportFinished(String filePath) {
		return shouldTriggerExportFinished(filePath, clock.getAsLong());
	}

	public synchronized boolean shouldTriggerExportFinished(String filePath, long now) {
		Long previous = recentExports.get(filePath);
		if (previous != null && now - previous < EXPORT_FINISHED_DEDUPE_MS) {
			return false;
		}
		recentExports.put(filePath, now);
		if (recentExports.size() > 64) {
			Iterator<Map.Entry<String, Long>> iterator = recentExports.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<String, Long> entry = iterator.next();
				if (now - entry.getValue() >= EXPORT_FINISHED_DEDUPE_MS) {
					iterator.remove();
				}
			}
		}
		return true;
	}

	public synchronized void resetAutomationTriggerState() {
		recentExports.clear();
	}

	/** Port of {@code fireAutomations}: the dispatcher must never let a rule break the caller. */
	public void fireAutomations(AutomationEvent event) {
		try {
			dispatch.accept(event);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.ERROR, "[automations] Automation run failed: " + error);
		}
	}

	/** Port of {@code RecordingSavedResult}: a stop result with an optional meeting marker. */
	public record RecordingSavedResult(boolean success, String path, boolean meeting) {}

	/**
	 * The webcam track of a browser recording is stored next to it as
	 * {@code recording-<ts>-webcam.<ext>} (port of {@code isWebcamRecordingPath}).
	 */
	public static boolean isWebcamRecordingPath(String filePath) {
		int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
		String base = filePath.substring(lastSlash + 1);
		return WEBCAM_SUFFIX.matcher(base).find();
	}

	/** Port of {@code fireRecordingSaved}. */
	public void fireRecordingSaved(RecordingSavedResult result) {
		if (result == null || !result.success() || result.path() == null || result.path().isEmpty()) {
			return;
		}
		boolean meeting = result.meeting();
		fireAutomations(new AutomationEvent(
				meeting ? AutomationTrigger.MEETING_SAVED : AutomationTrigger.RECORDING_SAVED,
				result.path(),
				meeting ? AutomationMediaKind.MEETING : AutomationMediaKind.SCREEN,
				null,
				null,
				null,
				false));
	}

	/** Port of {@code fireMeetingNotesReady}. */
	public void fireMeetingNotesReady(String mediaPath, String notesPath) {
		if (mediaPath == null || mediaPath.isEmpty()) {
			return;
		}
		AutomationEvent event = new AutomationEvent(
				AutomationTrigger.MEETING_NOTES_READY,
				mediaPath,
				AutomationMediaKind.MEETING,
				null,
				notesPath != null && !notesPath.isEmpty() ? notesPath : null,
				null,
				false);
		fireAutomations(event);
	}

	/** Port of {@code fireExportFinished}. */
	public void fireExportFinished(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return;
		}
		AutomationExportFormat format = formatForExportPath(filePath);
		if (format == null) {
			return;
		}
		if (!shouldTriggerExportFinished(filePath)) {
			return;
		}
		fireAutomations(new AutomationEvent(
				AutomationTrigger.EXPORT_FINISHED, filePath, null, format, null, null, false));
	}

	private static final System.Logger LOG = System.getLogger("gravaai.automations");
}
