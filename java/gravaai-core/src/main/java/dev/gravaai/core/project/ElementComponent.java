package dev.gravaai.core.project;

import java.util.List;
import java.util.Optional;

/**
 * A reusable element component stored in the project: a JavaScript ES module whose
 * {@code render(ctx, info)} draws one frame as a pure function of the frame number and the
 * element's JSON props (the Remotion "composition" idea, run by the GraalJS element runtime).
 *
 * <p>The module's own exports ({@code fps}, {@code durationInFrames}, {@code defaultProps},
 * {@code propsSchema}) are read by the runtime from {@link #source}; storing only the source keeps
 * one place that says what the component is. Built-in components live in the app under the
 * reserved {@value #BUILTIN_PREFIX} namespace and are never stored in a project.
 *
 * @param name unique within the project, e.g. {@code confetti} or {@code acme/badge}
 * @param source the ES module source
 * @param description one line for {@code inspect components}; empty when none
 */
public record ElementComponent(String name, String source, String description) {

	/** Namespace of the components bundled with the app. */
	public static final String BUILTIN_PREFIX = "gravaai/";

	public ElementComponent {
		name = name.trim();
		if (!isValidName(name)) {
			throw new IllegalArgumentException("invalid component name \"" + name
					+ "\": use letters, digits, '-', '_' and '/' (max 64)");
		}
		description = description.strip();
	}

	public boolean builtin() {
		return name.startsWith(BUILTIN_PREFIX);
	}

	/** Letters, digits, {@code - _ /}, 1–64 characters, not starting or ending with '/'. */
	public static boolean isValidName(String name) {
		return name.matches("[A-Za-z0-9][A-Za-z0-9_\\-/]{0,62}[A-Za-z0-9_\\-]|[A-Za-z0-9]");
	}

	public static Optional<ElementComponent> find(List<ElementComponent> components, String name) {
		return components.stream().filter(component -> component.name().equals(name)).findFirst();
	}
}
