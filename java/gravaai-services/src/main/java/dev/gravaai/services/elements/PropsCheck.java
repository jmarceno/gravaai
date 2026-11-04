package dev.gravaai.services.elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates element props against a component's {@code propsSchema} (a small JSON-schema
 * subset). Each entry of the schema is either a type name ({@code "string"}, {@code "number"},
 * {@code "boolean"}, {@code "color"}, {@code "object"}, {@code "array"}, {@code "any"}) or an
 * object {@code {type, min?, max?, values?}} where {@code type:"enum"} takes {@code values}.
 * A component without a schema accepts any JSON props.
 */
public final class PropsCheck {

	private PropsCheck() {}

	/** Per-field problems, empty when {@code props} fit {@code schema}. */
	public static List<String> problems(ObjectNode schema, ObjectNode props) {
		List<String> problems = new ArrayList<>();
		if (schema.isEmpty()) {
			return problems;
		}
		for (Map.Entry<String, JsonNode> entry : props.properties()) {
			JsonNode rule = schema.get(entry.getKey());
			if (rule == null) {
				problems.add("unknown prop \"" + entry.getKey() + "\"; this component takes "
						+ String.join(", ", schema.propertyNames()));
				continue;
			}
			check(entry.getKey(), rule, entry.getValue()).ifPresent(problems::add);
		}
		return problems;
	}

	/** One line per prop for {@code inspect components}: {@code title:string, size:number 12–300}. */
	public static String describe(ObjectNode schema) {
		if (schema.isEmpty()) {
			return "any JSON props";
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : schema.properties()) {
			JsonNode rule = entry.getValue();
			if (rule.isString()) {
				parts.add(entry.getKey() + ":" + rule.asString());
				continue;
			}
			String type = rule.path("type").asString("any");
			StringBuilder part = new StringBuilder(entry.getKey()).append(':').append(type);
			if ("enum".equals(type) && rule.path("values").isArray()) {
				List<String> values = new ArrayList<>();
				rule.path("values").forEach(value -> values.add(value.asString()));
				part.append('(').append(String.join("|", values)).append(')');
			}
			if (rule.path("min").isNumber() || rule.path("max").isNumber()) {
				part.append(' ').append(rule.path("min").isNumber() ? number(rule.path("min")) : "")
						.append('–').append(rule.path("max").isNumber() ? number(rule.path("max")) : "");
			}
			parts.add(part.toString());
		}
		return String.join(", ", parts);
	}

	private static String number(JsonNode value) {
		double number = value.asDouble();
		return number == Math.rint(number) ? Long.toString((long) number) : Double.toString(number);
	}

	private static java.util.Optional<String> check(String name, JsonNode rule, JsonNode value) {
		String type = rule.isString() ? rule.asString() : rule.path("type").asString("any");
		String problem = switch (type) {
			case "string" -> value.isString() ? "" : "must be a string";
			case "color" -> value.isString() && !value.asString().isBlank() ? ""
					: "must be a CSS colour string such as \"#ff8800\" or \"rgba(0,0,0,0.5)\"";
			case "boolean" -> value.isBoolean() ? "" : "must be true or false";
			case "object" -> value.isObject() ? "" : "must be an object";
			case "array" -> value.isArray() ? "" : "must be an array";
			case "number" -> numberProblem(rule, value);
			case "enum" -> enumProblem(rule, value);
			default -> "";
		};
		return problem.isEmpty() ? java.util.Optional.empty()
				: java.util.Optional.of("prop \"" + name + "\" " + problem);
	}

	private static String numberProblem(JsonNode rule, JsonNode value) {
		if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
			return "must be a number";
		}
		double number = value.asDouble();
		if (rule.path("min").isNumber() && number < rule.path("min").asDouble()) {
			return "must be at least " + number(rule.path("min"));
		}
		if (rule.path("max").isNumber() && number > rule.path("max").asDouble()) {
			return "must be at most " + number(rule.path("max"));
		}
		return "";
	}

	private static String enumProblem(JsonNode rule, JsonNode value) {
		JsonNode values = rule.path("values");
		if (!values.isArray()) {
			return "";
		}
		for (JsonNode allowed : values) {
			if (allowed.equals(value)) {
				return "";
			}
		}
		List<String> names = new ArrayList<>();
		values.forEach(allowed -> names.add(allowed.toString()));
		return "must be one of " + String.join(", ", names);
	}
}
