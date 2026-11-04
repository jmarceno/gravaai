package dev.gravaai.services.extensions.canvas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectUniformInfo;
import io.github.humbleui.skija.Shader;

/**
 * {@code shader(sksl, uniforms)}: an SkSL runtime shader used as a fill or stroke style (plan
 * §5.2). The source is compiled once per text and cached; uniforms are plain numbers or number
 * arrays computed from the frame, so the result stays a pure function of time. Coordinates are
 * the canvas' local coordinates ({@code fragCoord} in the element's design pixels).
 */
public final class CanvasShader implements CanvasPaintSource {

	private static final Map<String, RuntimeEffect> EFFECTS = new ConcurrentHashMap<>();
	private static final int MAX_EFFECTS = 64;

	private final RuntimeEffect effect;
	private final byte[] uniforms;

	private CanvasShader(RuntimeEffect effect, byte[] uniforms) {
		this.effect = effect;
		this.uniforms = uniforms;
	}

	/**
	 * Compiles {@code sksl} (cached) and binds {@code uniforms} ({@code {name: number|number[]}}).
	 * Throws {@link IllegalArgumentException} with Skia's message (it names the line) when the
	 * source does not compile, or when a uniform is missing or the wrong size.
	 */
	public static CanvasShader create(String sksl, @Nullable Value uniforms) {
		RuntimeEffect effect = EFFECTS.get(sksl);
		if (effect == null) {
			try {
				effect = RuntimeEffect.makeForShader(sksl);
			} catch (RuntimeException error) {
				throw new IllegalArgumentException("SkSL: " + error.getMessage());
			}
			if (EFFECTS.size() >= MAX_EFFECTS) {
				EFFECTS.clear();
			}
			EFFECTS.put(sksl, effect);
		}
		ByteBuffer buffer = ByteBuffer.allocate(effect.getUniformSize()).order(ByteOrder.nativeOrder());
		for (RuntimeEffectUniformInfo info : effect.getUniforms()) {
			int slots = info.getSizeInBytes() / 4;
			Value value = uniforms == null ? null : uniforms.getMember(info.getName());
			if (value == null || value.isNull()) {
				throw new IllegalArgumentException("SkSL uniform \"" + info.getName()
						+ "\" has no value (pass shader(src, {" + info.getName() + ": …}))");
			}
			double[] numbers = numbers(value, info.getName());
			if (numbers.length != slots) {
				throw new IllegalArgumentException("SkSL uniform \"" + info.getName() + "\" needs "
						+ slots + " number" + (slots == 1 ? "" : "s") + ", got " + numbers.length);
			}
			boolean integer = info.getType().name().contains("INT");
			for (int index = 0; index < slots; index++) {
				int at = info.getOffset() + index * 4;
				if (integer) {
					buffer.putInt(at, (int) Math.round(numbers[index]));
				} else {
					buffer.putFloat(at, (float) numbers[index]);
				}
			}
		}
		return new CanvasShader(effect, buffer.array());
	}

	private static double[] numbers(Value value, String name) {
		if (value.isNumber()) {
			return new double[] {value.asDouble()};
		}
		if (value.hasArrayElements()) {
			int size = (int) Math.min(64, value.getArraySize());
			double[] out = new double[size];
			for (int index = 0; index < size; index++) {
				Value element = value.getArrayElement(index);
				if (!element.isNumber()) {
					throw new IllegalArgumentException("SkSL uniform \"" + name + "\" must hold numbers");
				}
				out[index] = element.asDouble();
			}
			return out;
		}
		throw new IllegalArgumentException("SkSL uniform \"" + name + "\" must be a number or an array");
	}

	@Override
	public @Nullable Shader shader() {
		try (Data data = Data.makeFromBytes(uniforms)) {
			return effect.makeShader(data, null, null);
		}
	}
}
