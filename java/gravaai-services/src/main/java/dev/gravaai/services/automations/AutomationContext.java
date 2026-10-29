package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * The resolved values an automation template can use (port of {@code AutomationContext}). The
 * optional parts are nullable; {@code at} is the instant the rule ran.
 *
 * @param kind the media kind, or null when the caller did not report it
 * @param format the export format, or null for recordings/unknown
 * @param notesPath the meeting notes sidecar, or null when there is none
 * @param durationSec null when the duration is unknown (probe failed or no probe)
 */
public record AutomationContext(
		AutomationTrigger trigger,
		String path,
		@Nullable AutomationMediaKind kind,
		@Nullable AutomationExportFormat format,
		@Nullable String notesPath,
		@Nullable Double durationSec,
		Instant at) {

	public AutomationContext {
		path = path == null ? "" : path;
	}

	/** The same context with a different current file (ports {@code { ...context, path }}). */
	public AutomationContext withPath(String nextPath) {
		return new AutomationContext(trigger, nextPath, kind, format, notesPath, durationSec, at);
	}
}
