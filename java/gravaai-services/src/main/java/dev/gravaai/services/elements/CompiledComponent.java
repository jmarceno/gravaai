package dev.gravaai.services.elements;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.extensions.canvas.Canvas2D;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One component module evaluated in its own sandboxed GraalJS context: the prelude globals, the
 * module's exports and the per-frame entry point.
 *
 * <p>The sandbox matches the extension host ({@code HostAccess.EXPLICIT}, no IO, no threads, no
 * host class lookup, no native access) plus a per-frame statement limit, so code an AI wrote with
 * an endless loop fails that frame instead of hanging preview or export. A context is used by one
 * thread at a time; the {@link JsElementRuntime} keeps one per render thread.
 */
final class CompiledComponent implements AutoCloseable {

	/** Statements one {@code render} call may execute (reset before every frame). */
	static final long STATEMENT_LIMIT = 20_000_000;

	private static final String PRELUDE = readResource("prelude.js");

	private static final HostAccess HOST_ACCESS = HostAccess.newBuilder(HostAccess.EXPLICIT)
			.allowArrayAccess(true)
			.build();

	private final Context context;
	private final Value module;
	private final Value entry;
	private final ComponentInfo info;
	private final String sourceName;
	private volatile boolean usable = true;

	private CompiledComponent(Context context, Value module, Value entry, ComponentInfo info,
			String sourceName) {
		this.context = context;
		this.module = module;
		this.entry = entry;
		this.info = info;
		this.sourceName = sourceName;
	}

	/** Shared engine for every element context; all of them use the same limits (a Graal rule). */
	static Engine newEngine() {
		return Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
	}

	/**
	 * Evaluates {@code source} as component {@code name}. Throws {@link ComponentException} with
	 * the JavaScript message and line when the module does not compile, has no {@code render}
	 * function or declares invalid metadata.
	 */
	static CompiledComponent compile(Engine engine, String name, String source) {
		String sourceName = sourceName(name);
		Context context = Context.newBuilder("js")
				.engine(engine)
				.allowHostAccess(HOST_ACCESS)
				.allowHostClassLookup(className -> false)
				.allowIO(IOAccess.NONE)
				.allowCreateThread(false)
				.allowNativeAccess(false)
				.allowCreateProcess(false)
				.resourceLimits(ResourceLimits.newBuilder()
						.statementLimit(STATEMENT_LIMIT, null)
						.build())
				.option("js.esm-eval-returns-exports", "true")
				.build();
		try {
			Value entry = context.eval(Source.newBuilder("js", PRELUDE, "gravaai-prelude.js")
					.buildLiteral()).execute(ElementKernel.INSTANCE);
			context.resetLimits();
			Value module = context.eval(Source.newBuilder("js", source, sourceName)
					.mimeType("application/javascript+module").buildLiteral());
			context.resetLimits();
			Value render = module.getMember("render");
			if (render == null || !render.canExecute()) {
				throw new ComponentException(
						"the module must export function render(ctx, {frame, fps, durationInFrames, width, height, props})");
			}
			Value stringify = context.eval("js",
					"(v) => JSON.stringify(v === undefined || v === null ? {} : v)");
			double fps = number(module, "fps", ComponentInfo.DEFAULT_FPS, 1, 240);
			double duration = number(module, "durationInFrames",
					ComponentInfo.DEFAULT_DURATION_IN_FRAMES, 1, fps * 3600);
			ObjectNode defaultProps = object(stringify, module, "defaultProps");
			ObjectNode propsSchema = object(stringify, module, "propsSchema");
			Value description = module.getMember("description");
			String text = description != null && description.isString() ? description.asString() : "";
			context.resetLimits();
			return new CompiledComponent(context, module, entry,
					new ComponentInfo(name, fps, duration, defaultProps, propsSchema, text), sourceName);
		} catch (PolyglotException error) {
			context.close(true);
			throw ComponentException.from(error, sourceName);
		} catch (RuntimeException error) {
			context.close(true);
			throw error;
		}
	}

	/** False once the context was cancelled (time or statement limit); it must be recompiled. */
	boolean usable() {
		return usable;
	}

	ComponentInfo info() {
		return info;
	}

	/**
	 * Draws one frame through the module's {@code render}. Throws {@link ComponentException} when
	 * the component throws or exceeds its budget; the caller decides how to show it.
	 */
	void render(Canvas2D ctx, double frame, double durationInFrames, double timeMs, double width,
			double height, ObjectNode props, String seed) {
		try {
			context.resetLimits();
			entry.execute(module, ctx, frame, info.fps(), durationInFrames, timeMs, width, height,
					props.toString(), seed);
		} catch (PolyglotException error) {
			if (error.isResourceExhausted() || error.isCancelled()) {
				usable = false;
			}
			throw ComponentException.from(error, sourceName);
		}
	}

	@Override
	public void close() {
		try {
			context.close(true);
		} catch (RuntimeException ignored) {
			// A cancelled context may already be closing; nothing else holds it.
		}
	}

	static String sourceName(String component) {
		return "component:" + component + ".js";
	}

	private static double number(Value module, String member, double fallback, double min,
			double max) {
		Value value = module.getMember(member);
		if (value == null || value.isNull()) {
			return fallback;
		}
		if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
			throw new ComponentException("export \"" + member + "\" must be a number");
		}
		double number = value.asDouble();
		if (number < min || number > max) {
			throw new ComponentException("export \"" + member + "\" must be between " + min
					+ " and " + max + ", got " + number);
		}
		return number;
	}

	private static ObjectNode object(Value stringify, Value module, String member) {
		Value value = module.getMember(member);
		if (value != null && !value.isNull() && !(value.hasMembers() && !value.canExecute())) {
			throw new ComponentException("export \"" + member + "\" must be a plain object");
		}
		String json = stringify.execute(value).asString();
		JsonNode parsed = Json.parseJsonWithByteOrderMark(json);
		if (!parsed.isObject()) {
			throw new ComponentException("export \"" + member + "\" must be a plain object");
		}
		return (ObjectNode) parsed;
	}

	private static String readResource(String name) {
		try (InputStream stream = CompiledComponent.class.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}
}
