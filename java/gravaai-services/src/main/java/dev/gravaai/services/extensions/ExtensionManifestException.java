package dev.gravaai.services.extensions;

/**
 * A {@code gravaai-extension.json} that failed validation. The message names the field and the
 * reason so the extension manager can show it verbatim.
 */
public final class ExtensionManifestException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ExtensionManifestException(String message) {
		super(message);
	}

	public ExtensionManifestException(String message, Throwable cause) {
		super(message, cause);
	}
}
