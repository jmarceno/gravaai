package dev.gravaai.media.compose;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.Shader;

/**
 * The SkSL {@code RuntimeEffect} shaders for the stage-5 motion blur (plan §5.8). They are 1:1
 * ports of the pixi-filters fragment shaders used by the TS renderer:
 * {@code node_modules/pixi-filters/lib/motion-blur/motion-blur.mjs} (directional, 13-tap) and
 * {@code node_modules/pixi-filters/lib/zoom-blur/zoom-blur.mjs} (radial, maxKernelSize 13 as
 * constructed in {@code modernFrameRenderer}). The only adaptation is SkSL syntax and the
 * pixel-space coordinate convention: the shader is drawn over the whole stage, so {@code coord}
 * is in stage pixels and the child image is sampled at the same pixel coordinates.
 */
public final class MotionBlurShaders {

	/** The zoom-blur kernel size from {@code new ZoomBlurFilter({ strength: 0, maxKernelSize: 13 })}. */
	public static final float ZOOM_KERNEL_SIZE = 13f;

	private static final String DIRECTIONAL_SKSL = """
			uniform shader uTexture;
			uniform float2 uVelocity;
			uniform float uKernelSize;
			uniform float uOffset;
			uniform float2 uInputSize;

			half4 main(float2 coord) {
			    half4 color = uTexture.eval(coord);
			    float velocityLength = length(uVelocity);
			    if (uKernelSize <= 0.0 || velocityLength <= 0.0) {
			        return color;
			    }
			    float2 velocity = uVelocity / uInputSize;
			    float offset = -uOffset / velocityLength - 0.5;
			    float k = uKernelSize - 1.0;
			    for (int i = 0; i < 32; i++) {
			        if (float(i) >= k) {
			            break;
			        }
			        float2 bias = velocity * (float(i) / k + offset);
			        color += uTexture.eval(coord + bias * uInputSize);
			    }
			    return color / uKernelSize;
			}
			""";

	private static final String ZOOM_SKSL = """
			uniform shader uTexture;
			uniform float uStrength;
			uniform float2 uCenter;
			uniform float2 uRadii;
			uniform float2 uInputSize;

			const float MAX_KERNEL_SIZE = 13.0;

			float rand(float2 co, float seed) {
			    float a = 12.9898;
			    float b = 78.233;
			    float c = 43758.5453;
			    float dt = dot(co + seed, float2(a, b));
			    float sn = mod(dt, 3.14159);
			    return fract(sin(sn) * c + seed);
			}

			half4 main(float2 coord) {
			    float minGradient = uRadii.x * 0.3;
			    float innerRadius = (uRadii.x + minGradient * 0.5) / uInputSize.x;

			    float gradient = uRadii.y * 0.3;
			    float radius = (uRadii.y - gradient * 0.5) / uInputSize.x;

			    float countLimit = MAX_KERNEL_SIZE;

			    float2 dir = float2(uCenter.x / uInputSize.x - coord.x / uInputSize.x,
			            uCenter.y / uInputSize.y - coord.y / uInputSize.y);
			    float dist = length(float2(dir.x, dir.y * uInputSize.y / uInputSize.x));

			    float strength = uStrength;
			    float delta = 0.0;
			    float gap = 0.0;
			    if (dist < innerRadius) {
			        delta = innerRadius - dist;
			        gap = minGradient;
			    } else if (radius >= 0.0 && dist > radius) {
			        delta = dist - radius;
			        gap = gradient;
			    }

			    if (delta > 0.0) {
			        float normalCount = gap / uInputSize.x;
			        delta = (normalCount - delta) / normalCount;
			        countLimit *= delta;
			        strength *= delta;
			        if (countLimit < 1.0) {
			            return uTexture.eval(coord);
			        }
			    }

			    float offset = rand(coord, 0.0);
			    float total = 0.0;
			    half4 color = half4(0.0);
			    dir *= strength;

			    for (int t = 0; t < 13; t++) {
			        float percent = (float(t) + offset) / MAX_KERNEL_SIZE;
			        float weight = 4.0 * (percent - percent * percent);
			        float2 p = coord / uInputSize + dir * percent;
			        half4 sample = uTexture.eval(p * uInputSize);
			        color += sample * weight;
			        total += weight;
			    }
			    return color / total;
			}
			""";

	private static final RuntimeEffect DIRECTIONAL_EFFECT =
			RuntimeEffect.makeForShader(DIRECTIONAL_SKSL);
	private static final RuntimeEffect ZOOM_EFFECT = RuntimeEffect.makeForShader(ZOOM_SKSL);

	private MotionBlurShaders() {}

	/**
	 * Builds the directional pan-blur shader for one captured video layer.
	 *
	 * @param layer the captured video layer image
	 * @param effect the analysis effect ({@link MotionBlur.Path#DIRECTIONAL})
	 * @param stageWidth the stage width in pixels
	 * @param stageHeight the stage height in pixels
	 */
	public static Shader directional(Image layer, MotionBlur.Effect effect, int stageWidth,
			int stageHeight) {
		// The effect shader holds its own reference to the child: close ours here. A leaked child
		// shader is freed by the Java cleaner on another thread, which races the GL thread when the
		// layer is a GPU image (export crash in GrGpuResource::release).
		Shader child = layer.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP);
		float[] uniforms = {
				(float) effect.velocityX(),
				(float) effect.velocityY(),
				effect.kernelSize(),
				(float) effect.offset(),
				stageWidth,
				stageHeight};
		try (Data data = uniformData(uniforms); child) {
			return DIRECTIONAL_EFFECT.makeShader(data, new Shader[] {child});
		}
	}

	/**
	 * Builds the radial zoom-blur shader for one captured video layer.
	 *
	 * @param layer the captured video layer image
	 * @param effect the analysis effect ({@link MotionBlur.Path#ZOOM})
	 * @param stageWidth the stage width in pixels
	 * @param stageHeight the stage height in pixels
	 */
	public static Shader zoom(Image layer, MotionBlur.Effect effect, int stageWidth,
			int stageHeight) {
		// The effect shader holds its own reference to the child: close ours here. A leaked child
		// shader is freed by the Java cleaner on another thread, which races the GL thread when the
		// layer is a GPU image (export crash in GrGpuResource::release).
		Shader child = layer.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP);
		float[] uniforms = {
				(float) effect.zoomStrength(),
				(float) effect.zoomCenterX(),
				(float) effect.zoomCenterY(),
				(float) effect.zoomInnerRadius(),
				(float) effect.zoomRadius(),
				stageWidth,
				stageHeight};
		try (Data data = uniformData(uniforms); child) {
			return ZOOM_EFFECT.makeShader(data, new Shader[] {child});
		}
	}

	private static Data uniformData(float[] values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES)
				.order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			buffer.putFloat(value);
		}
		return Data.makeFromBytes(buffer.array());
	}
}
