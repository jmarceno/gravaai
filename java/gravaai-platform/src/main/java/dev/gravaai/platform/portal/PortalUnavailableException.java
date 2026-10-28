package dev.gravaai.platform.portal;

import java.io.IOException;

/** Thrown when the XDG desktop portal is not on the session bus (callers fall back to JavaFX dialogs). */
public final class PortalUnavailableException extends IOException {

	private static final long serialVersionUID = 1L;

	public PortalUnavailableException(String message) {
		super(message);
	}

	public PortalUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
