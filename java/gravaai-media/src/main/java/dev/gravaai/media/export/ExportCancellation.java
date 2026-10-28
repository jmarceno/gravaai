package dev.gravaai.media.export;

/** Cooperative cancellation signal for a running export (plan §5.10 progress/cancel model). */
@FunctionalInterface
public interface ExportCancellation {

	ExportCancellation NONE = () -> false;

	boolean isCancelled();
}
