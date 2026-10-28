package dev.gravaai.media.export;

/** Failure of an export encode (plan §5.10). */
public class ExportException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ExportException(String message) {
		super(message);
	}

	public ExportException(String message, Throwable cause) {
		super(message, cause);
	}
}
