package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

/**
 * What a caller reports to the engine (port of {@code AutomationEvent}).
 *
 * <p>The TS field {@code durationSec?: number | null} distinguishes "not provided" (undefined,
 * probe lazily) from "provided and unknown" (null). Java needs the explicit
 * {@code durationSecProvided} flag for the same tri-state.
 */
public record AutomationEvent(
		AutomationTrigger trigger,
		String path,
		@Nullable AutomationMediaKind kind,
		@Nullable AutomationExportFormat format,
		@Nullable String notesPath,
		@Nullable Double durationSec,
		boolean durationSecProvided) {

	public AutomationEvent {
		path = path == null ? "" : path;
	}

	public static AutomationEvent of(AutomationTrigger trigger, String path) {
		return new AutomationEvent(trigger, path, null, null, null, null, false);
	}

	public AutomationEvent withKind(AutomationMediaKind kind) {
		return new AutomationEvent(trigger, path, kind, format, notesPath, durationSec, durationSecProvided);
	}

	public AutomationEvent withFormat(AutomationExportFormat format) {
		return new AutomationEvent(trigger, path, kind, format, notesPath, durationSec, durationSecProvided);
	}

	public AutomationEvent withNotesPath(String notesPath) {
		return new AutomationEvent(trigger, path, kind, format, notesPath, durationSec, durationSecProvided);
	}

	/** Marks the duration as known (or explicitly unknown when {@code durationSec} is null). */
	public AutomationEvent withDuration(Double durationSec) {
		return new AutomationEvent(trigger, path, kind, format, notesPath, durationSec, true);
	}

	public AutomationEvent withTrigger(AutomationTrigger trigger) {
		return new AutomationEvent(trigger, path, kind, format, notesPath, durationSec, durationSecProvided);
	}
}
