package dev.gravaai.services.elements;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;

/**
 * A component failed to compile, to describe itself or to render. The message is what the agent
 * and the error box show: the JavaScript message plus {@code line:column} when the engine knows
 * where it happened, so an AI author can go straight to the faulty line.
 */
public final class ComponentException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int line;
	private final int column;

	public ComponentException(String message, int line, int column) {
		super(message);
		this.line = line;
		this.column = column;
	}

	public ComponentException(String message) {
		this(message, 0, 0);
	}

	/** 1-based source line, or 0 when unknown. */
	public int line() {
		return line;
	}

	/** 1-based source column, or 0 when unknown. */
	public int column() {
		return column;
	}

	/** {@code message (line 12:5)} or just the message. */
	public String describe() {
		return line > 0 ? getMessage() + " (line " + line + ":" + column + ")" : getMessage();
	}

	/** Translates an engine failure, pointing at the first frame inside the component source. */
	static ComponentException from(PolyglotException error, String sourceName) {
		if (error.isResourceExhausted()) {
			return new ComponentException("render took too long (statement limit reached): "
					+ "an endless loop, or far too much work per frame");
		}
		if (error.isCancelled()) {
			return new ComponentException("render was cancelled (time limit reached)");
		}
		String message = error.isHostException() && error.asHostException().getMessage() != null
				? error.asHostException().getMessage() : String.valueOf(error.getMessage());
		SourceSection location = error.getSourceLocation();
		for (PolyglotException.StackFrame frame : error.getPolyglotStackTrace()) {
			SourceSection section = frame.getSourceLocation();
			if (section != null && section.getSource() != null
					&& sourceName.equals(section.getSource().getName())) {
				location = section;
				break;
			}
		}
		if (location != null && location.getSource() != null
				&& sourceName.equals(location.getSource().getName()) && location.hasLines()) {
			return new ComponentException(message, location.getStartLine(),
					location.hasColumns() ? location.getStartColumn() : 0);
		}
		return new ComponentException(message);
	}
}
