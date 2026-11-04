package dev.gravaai.services.elements;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.gravaai.core.project.ElementComponent;

/**
 * The components bundled with the app under {@code gravaai/}. They are ordinary component
 * modules written against the same contract an agent uses, so an agent can read them
 * ({@code inspect components topic:"gravaai/title-card"}) and fork them.
 */
public final class BuiltinComponents {

	private static final List<String> NAMES = List.of("title-card", "lower-third", "counter", "callout",
			"lottie", "shader-backdrop", "model-3d");

	private static final Map<String, ElementComponent> COMPONENTS = load();

	private BuiltinComponents() {}

	/** Every built-in, in catalog order. */
	public static List<ElementComponent> all() {
		return List.copyOf(COMPONENTS.values());
	}

	public static Optional<ElementComponent> find(String name) {
		return Optional.ofNullable(COMPONENTS.get(name));
	}

	/**
	 * Resolves a component name against the built-ins and then the project components; empty
	 * when neither has it.
	 */
	public static Optional<ElementComponent> resolve(String name, List<ElementComponent> project) {
		if (name.startsWith(ElementComponent.BUILTIN_PREFIX)) {
			return find(name);
		}
		return ElementComponent.find(project, name);
	}

	private static Map<String, ElementComponent> load() {
		Map<String, ElementComponent> components = new LinkedHashMap<>();
		for (String name : NAMES) {
			String source = read("builtin/" + name + ".js");
			String description = source.lines()
					.filter(line -> line.startsWith("export const description = '"))
					.map(line -> line.substring(line.indexOf('\'') + 1, line.lastIndexOf('\'')))
					.findFirst().orElse("");
			String fullName = ElementComponent.BUILTIN_PREFIX + name;
			components.put(fullName, new ElementComponent(fullName, source, description));
		}
		return components;
	}

	private static String read(String resource) {
		try (InputStream stream = BuiltinComponents.class.getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException("missing built-in component " + resource);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}
}
