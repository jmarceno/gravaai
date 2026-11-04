package dev.gravaai.services.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.SceneElement;
import dev.gravaai.services.agent.AgentOpReader.OpError;
import dev.gravaai.services.elements.BuiltinComponents;
import dev.gravaai.services.elements.ComponentException;
import dev.gravaai.services.elements.ComponentInfo;
import dev.gravaai.services.elements.ComponentValidator;
import dev.gravaai.services.elements.PropsCheck;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The element-layer ops of the agent {@code edit} tool: write a component (validated before the
 * batch commits), place it on the elements lane, change and remove elements and components.
 * Positions follow the annotation convention: {@code x}/{@code y} are the centre and
 * {@code w}/{@code h} the size, as 0–1 fractions of the frame (default: the whole frame).
 */
final class AgentElementOps {

	/** What an op needs from the planner's draft. */
	interface Session {
		ProjectEditorState state();

		void state(ProjectEditorState next);

		void summary(String line);

		void highlight(boolean added, long startMs, long endMs, String label);

		String newElementId();

		long timelineDurationMs();

		Map<String, String> elementIdByShort();
	}

	private AgentElementOps() {}

	/** {@code component {name, source, description?}}: create or replace a project component. */
	static void component(Session session, JsonNode raw) {
		String name = AgentOpReader.string(raw, "name").trim();
		if (name.startsWith(ElementComponent.BUILTIN_PREFIX)) {
			throw new OpError("\"" + ElementComponent.BUILTIN_PREFIX + "\" is reserved for built-ins;"
					+ " fork one under another name (e.g. \"my-" + name.substring(
							ElementComponent.BUILTIN_PREFIX.length()) + "\")");
		}
		if (!ElementComponent.isValidName(name)) {
			throw new OpError("invalid component name \"" + name
					+ "\": letters, digits, '-', '_' and '/' (max 64)");
		}
		String source = AgentOpReader.string(raw, "source");
		if (source.length() > ProjectPersistence.MAX_COMPONENT_SOURCE_CHARS) {
			throw new OpError("component source is too large (" + source.length() + " characters; max "
					+ ProjectPersistence.MAX_COMPONENT_SOURCE_CHARS + ")");
		}
		String description = Optional.ofNullable(AgentOpReader.optionalString(raw, "description"))
				.orElse("");
		ElementComponent component = new ElementComponent(name, source, description);
		ComponentInfo info;
		try {
			info = ComponentValidator.validate(component);
		} catch (ComponentException error) {
			throw new OpError("component \"" + name + "\" failed validation: " + error.describe());
		}
		if (description.isEmpty() && !info.description().isEmpty()) {
			component = new ElementComponent(name, source, info.description());
		}
		List<ElementComponent> components = new ArrayList<>();
		boolean replaced = false;
		for (ElementComponent existing : session.state().elementComponents()) {
			if (existing.name().equals(name)) {
				components.add(component);
				replaced = true;
			} else {
				components.add(existing);
			}
		}
		if (!replaced) {
			components.add(component);
		}
		session.state(session.state().toBuilder().elementComponents(components).build());
		List<String> invalid = invalidElements(session.state(), name, info);
		session.summary((replaced ? "updated" : "added") + " component " + name + " ("
				+ fmt(info.fps()) + " fps, " + fmt(info.durationInFrames()) + " frames)"
				+ (invalid.isEmpty() ? "" : "; " + String.join("; ", invalid)));
	}

	/** {@code delete_component {name}}: refused while elements use it. */
	static void deleteComponent(Session session, JsonNode raw) {
		String name = AgentOpReader.string(raw, "name").trim();
		if (ElementComponent.find(session.state().elementComponents(), name).isEmpty()) {
			throw new OpError("no project component \"" + name + "\"" + (name.startsWith(
					ElementComponent.BUILTIN_PREFIX) ? " (built-ins cannot be deleted)" : ""));
		}
		List<String> users = new ArrayList<>();
		Map<String, String> shortById = AgentIds.elementShortById(session.state());
		for (SceneElement element : session.state().elements()) {
			if (element.component().equals(name)) {
				users.add(shortById.getOrDefault(element.id(), element.id()));
			}
		}
		if (!users.isEmpty()) {
			throw new OpError("component \"" + name + "\" is used by " + String.join(", ", users)
					+ "; delete or change those elements first");
		}
		session.state(session.state().toBuilder().elementComponents(
				session.state().elementComponents().stream()
						.filter(component -> !component.name().equals(name)).toList()).build());
		session.summary("deleted component " + name);
	}

	/**
	 * {@code element {component, from, to?, x?, y?, w?, h?, props?, layer?}}: place an instance;
	 * {@code to} defaults to the component's {@code durationInFrames}.
	 */
	static void addElement(Session session, JsonNode raw) {
		String name = AgentOpReader.string(raw, "component").trim();
		ElementComponent component = resolve(session.state(), name);
		ComponentInfo info = describe(component);
		long from = Math.round(AgentOpReader.number(raw, "from") * 1000);
		if (from < 0) {
			throw new OpError("\"from\" must be at least 0");
		}
		Double toSeconds = AgentOpReader.optionalNumber(raw, "to");
		long to = toSeconds != null ? Math.round(toSeconds * 1000) : from + info.defaultDurationMs();
		long[] span = clamp(session, from, to);
		ObjectNode props = props(raw).orElse(SceneElement.noProps());
		requireValidProps(info, props);
		Optional<Integer> layer = layer(raw);
		int zIndex = layer.isPresent() ? layer.get() : session.state().elements().stream()
				.mapToInt(SceneElement::zIndex).max().orElse(0) + 1;
		String id = session.newElementId();
		SceneElement element = new SceneElement(id, name, span[0], span[1],
				box(raw, SceneElement.Box.FULL), props, zIndex);
		List<SceneElement> elements = new ArrayList<>(session.state().elements());
		elements.add(element);
		session.state(session.state().toBuilder().elements(elements).build());
		session.summary("added element {{e:" + id + "}} " + name + " "
				+ AgentIds.formatSeconds(span[0]) + "–" + AgentIds.formatSeconds(span[1]) + "s");
		session.highlight(true, span[0], span[1], name);
	}

	/**
	 * {@code update_element {id, component?, from?, to?, x?, y?, w?, h?, props?, layer?}}: props
	 * merge shallowly over the element's props; a {@code null} prop removes that key.
	 */
	static void updateElement(Session session, JsonNode raw) {
		SceneElement element = find(session, AgentOpReader.string(raw, "id"));
		String componentName = Optional.ofNullable(AgentOpReader.optionalString(raw, "component"))
				.map(String::trim).orElse(element.component());
		ComponentInfo info = describe(resolve(session.state(), componentName));
		Double fromSeconds = AgentOpReader.optionalNumber(raw, "from");
		Double toSeconds = AgentOpReader.optionalNumber(raw, "to");
		long from = fromSeconds != null ? Math.round(fromSeconds * 1000) : element.startMs();
		long to = toSeconds != null ? Math.round(toSeconds * 1000)
				: fromSeconds != null ? from + element.durationMs() : element.endMs();
		long[] span = clamp(session, from, to);
		ObjectNode props = element.props().deepCopy();
		Optional<ObjectNode> patch = props(raw);
		patch.ifPresent(values -> values.properties().forEach(entry -> {
			if (entry.getValue().isNull()) {
				props.remove(entry.getKey());
			} else {
				props.set(entry.getKey(), entry.getValue());
			}
		}));
		requireValidProps(info, props);
		Optional<Integer> layer = layer(raw);
		SceneElement updated = new SceneElement(element.id(), componentName, span[0], span[1],
				box(raw, element.box()), props, layer.orElse(element.zIndex()));
		session.state(session.state().toBuilder().elements(session.state().elements().stream()
				.map(candidate -> candidate.id().equals(element.id()) ? updated : candidate)
				.toList()).build());
		session.summary("updated element {{e:" + element.id() + "}}");
		session.highlight(false, span[0], span[1], componentName);
	}

	/** {@code delete_element {id} | {all: true}}. */
	static void deleteElement(Session session, JsonNode raw) {
		if (Boolean.TRUE.equals(AgentOpReader.optionalBoolean(raw, "all"))) {
			int count = session.state().elements().size();
			session.state(session.state().toBuilder().elements(List.of()).build());
			session.summary("deleted " + count + " elements");
			return;
		}
		String shortId = AgentOpReader.string(raw, "id").trim();
		SceneElement element = find(session, shortId);
		session.state(session.state().toBuilder().elements(session.state().elements().stream()
				.filter(candidate -> !candidate.id().equals(element.id())).toList()).build());
		session.summary("deleted element " + shortId);
	}

	private static SceneElement find(Session session, String shortId) {
		String id = session.elementIdByShort().get(shortId.trim());
		Optional<SceneElement> element = id == null ? Optional.empty()
				: session.state().elements().stream()
						.filter(candidate -> candidate.id().equals(id)).findFirst();
		return element.orElseThrow(() -> new OpError(shortId + " not found; elements: "
				+ describeShorts(session.elementIdByShort().size())));
	}

	private static String describeShorts(int count) {
		return count == 0 ? "none" : count == 1 ? "e1" : "e1–e" + count;
	}

	private static ElementComponent resolve(ProjectEditorState state, String name) {
		return BuiltinComponents.resolve(name, state.elementComponents()).orElseThrow(() ->
				new OpError("unknown component \"" + name + "\"; available: " + String.join(", ",
						available(state)) + ". Write one with the component op first"));
	}

	private static List<String> available(ProjectEditorState state) {
		List<String> names = new ArrayList<>();
		BuiltinComponents.all().forEach(component -> names.add(component.name()));
		state.elementComponents().forEach(component -> names.add(component.name()));
		return names;
	}

	private static ComponentInfo describe(ElementComponent component) {
		try {
			return ComponentValidator.describe(component);
		} catch (ComponentException error) {
			throw new OpError("component \"" + component.name() + "\" does not compile: "
					+ error.describe());
		}
	}

	private static void requireValidProps(ComponentInfo info, ObjectNode props) {
		List<String> problems = PropsCheck.problems(info.propsSchema(), props);
		if (!problems.isEmpty()) {
			throw new OpError(String.join("; ", problems));
		}
	}

	/** Elements whose props no longer fit a replaced component's schema, for the summary. */
	private static List<String> invalidElements(ProjectEditorState state, String name,
			ComponentInfo info) {
		List<String> invalid = new ArrayList<>();
		Map<String, String> shortById = AgentIds.elementShortById(state);
		for (SceneElement element : state.elements()) {
			if (!element.component().equals(name)) {
				continue;
			}
			List<String> problems = PropsCheck.problems(info.propsSchema(), element.props());
			if (!problems.isEmpty()) {
				invalid.add("warning " + shortById.get(element.id()) + ": " + problems.getFirst());
			}
		}
		return invalid;
	}

	private static Optional<ObjectNode> props(JsonNode raw) {
		JsonNode value = AgentOpReader.optionalObject(raw, "props");
		return value == null ? Optional.empty() : Optional.of((ObjectNode) value.deepCopy());
	}

	private static Optional<Integer> layer(JsonNode raw) {
		Double layer = AgentOpReader.optionalNumber(raw, "layer");
		if (layer == null) {
			return Optional.empty();
		}
		if (layer != Math.rint(layer) || Math.abs(layer) > 1000) {
			throw new OpError("\"layer\" must be a whole number between -1000 and 1000");
		}
		return Optional.of(layer.intValue());
	}

	/** Centre/size fractions → a percent box; fields left out keep {@code base}. */
	private static SceneElement.Box box(JsonNode raw, SceneElement.Box base) {
		Optional<Double> x = fraction(raw, "x");
		Optional<Double> y = fraction(raw, "y");
		Optional<Double> w = fraction(raw, "w");
		Optional<Double> h = fraction(raw, "h");
		double width = w.map(value -> value * 100).orElse(base.width());
		double height = h.map(value -> value * 100).orElse(base.height());
		double centreX = x.map(value -> value * 100).orElse(base.x() + base.width() / 2);
		double centreY = y.map(value -> value * 100).orElse(base.y() + base.height() / 2);
		return new SceneElement.Box(centreX - width / 2, centreY - height / 2, width, height);
	}

	private static Optional<Double> fraction(JsonNode raw, String key) {
		Double value = AgentOpReader.optionalNumber(raw, key);
		if (value == null) {
			return Optional.empty();
		}
		boolean size = key.equals("w") || key.equals("h");
		if (size ? value <= 0 || value > 4 : value < -2 || value > 3) {
			throw new OpError("\"" + key + "\" is a fraction of the frame" + (size
					? " (0–4]" : " (centre, normally 0–1)") + ", got " + value);
		}
		return Optional.of(value);
	}

	private static long[] clamp(Session session, long from, long to) {
		long duration = session.timelineDurationMs();
		if (from >= duration) {
			throw new OpError(AgentIds.formatSeconds(from) + "s is past the end of the edited timeline ("
					+ AgentIds.formatSeconds(duration) + "s)");
		}
		if (to <= from) {
			throw new OpError("\"to\" must be after \"from\"");
		}
		long end = Math.min(to, duration);
		return new long[] {from, Math.max(from + SceneElement.MIN_DURATION_MS / 10, end)};
	}

	private static String fmt(double value) {
		return value == Math.rint(value) ? Long.toString((long) value)
				: String.format(java.util.Locale.ROOT, "%.2f", value);
	}
}
