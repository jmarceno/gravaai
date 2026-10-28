package dev.gravaai.services.automations;

/**
 * Unchecked wrapper for {@link AutomationActions}: the engine's action runner cannot declare
 * checked exceptions, and every failure is reported per action anyway.
 */
public final class AutomationActionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public AutomationActionException(String message, Throwable cause) {
		super(message, cause);
	}
}
