package dev.gravaai.services.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ImageInfo;

import dev.gravaai.core.project.ElementAsset;
import dev.gravaai.core.project.ElementComponent;
import dev.gravaai.core.project.ElementMotion;
import dev.gravaai.core.project.SceneElement;

/** Phase 2/3 runtime features: motion, blur, spaces, assets, shaders, Lottie, 3D, transforms. */
class ElementPhase2Test {

	private static final int W = 192;
	private static final int H = 108;

	private static final String SQUARE = """
			export function render(ctx, { width, height }) {
			  ctx.fillStyle = '#ffffff';
			  ctx.fillRect(width / 2 - 100, height / 2 - 100, 200, 200);
			}
			""";

	private static SceneElement element(String component) {
		return new SceneElement("element-1", component, 0, 4000, SceneElement.Box.FULL,
				SceneElement.noProps(), 0);
	}

	private static byte[] render(SceneElement element, List<ElementComponent> components,
			List<ElementAsset> assets, double timeMs, Optional<SceneElement.Space> space) {
		try (JsElementRuntime runtime = new JsElementRuntime(new ElementDiagnostics());
				Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(ImageInfo.makeN32(W, H, ColorAlphaType.PREMUL));
			try (Canvas canvas = new Canvas(bitmap)) {
				canvas.clear(0);
				runtime.render(canvas, W, H, timeMs, new JsElementRuntime.Request(List.of(element),
						components, assets, Optional.empty(), space));
			}
			assertTrue(runtime.failures().isEmpty(), () -> runtime.failures().toString());
			return bitmap.readPixels();
		}
	}

	private static long opaque(byte[] rgba) {
		long count = 0;
		for (int index = 3; index < rgba.length; index += 4) {
			if ((rgba[index] & 0xFF) > 8) {
				count++;
			}
		}
		return count;
	}

	/** Mean x of opaque pixels. */
	private static double centroidX(byte[] rgba) {
		double sum = 0;
		long count = 0;
		for (int index = 3; index < rgba.length; index += 4) {
			if ((rgba[index] & 0xFF) > 8) {
				sum += (index / 4) % W;
				count++;
			}
		}
		return count == 0 ? -1 : sum / count;
	}

	private static int alphaAt(byte[] rgba, int x, int y) {
		return rgba[(y * W + x) * 4 + 3] & 0xFF;
	}

	@Test
	void fadePresetHidesThenShows() {
		List<ElementComponent> components = List.of(new ElementComponent("square", SQUARE, ""));
		SceneElement faded = element("square").withMotion(ElementMotion.preset("fade", true, true, 400));
		assertEquals(0, opaque(render(faded, components, List.of(), 0, Optional.empty())));
		assertTrue(opaque(render(faded, components, List.of(), 2000, Optional.empty())) > 300);
		assertTrue(alphaAt(render(faded, components, List.of(), 200, Optional.empty()), W / 2, H / 2) < 250,
				"half-way through the fade is translucent");
	}

	@Test
	void slidePresetMovesTheBox() {
		List<ElementComponent> components = List.of(new ElementComponent("square", SQUARE, ""));
		SceneElement sliding = element("square").withMotion(ElementMotion.preset("slide-left", true,
				false, 1000));
		double early = centroidX(render(sliding, components, List.of(), 300, Optional.empty()));
		double settled = centroidX(render(sliding, components, List.of(), 2000, Optional.empty()));
		assertTrue(early < settled - 2, "enters from the left: " + early + " vs " + settled);
	}

	@Test
	void motionBlurDrawsTrails() {
		String moving = """
				export function render(ctx, { frame }) {
				  ctx.fillStyle = '#fff';
				  ctx.fillRect(frame * 40, 300, 60, 200);
				}
				""";
		List<ElementComponent> components = List.of(new ElementComponent("mover", moving, ""));
		long sharp = opaque(render(element("mover"), components, List.of(), 1000, Optional.empty()));
		long blurred = opaque(render(element("mover").withBlur(new ElementMotion.Blur(6, 2)),
				components, List.of(), 1000, Optional.empty()));
		assertTrue(blurred > sharp * 1.5, "trails cover more pixels: " + sharp + " -> " + blurred);
	}

	@Test
	void spacesFilterWhatDraws() {
		List<ElementComponent> components = List.of(new ElementComponent("square", SQUARE, ""));
		SceneElement scene = element("square").withSpace(SceneElement.Space.SCENE);
		assertEquals(0, opaque(render(scene, components, List.of(), 1000,
				Optional.of(SceneElement.Space.SCREEN))));
		assertTrue(opaque(render(scene, components, List.of(), 1000,
				Optional.of(SceneElement.Space.SCENE))) > 300);
	}

	@Test
	void setTransformIsRelativeToTheElementBox() {
		String source = """
				export function render(ctx) {
				  ctx.setTransform(1, 0, 0, 1, 0, 0);
				  ctx.fillStyle = '#fff';
				  ctx.fillRect(0, 0, 100, 100);
				}
				""";
		SceneElement boxed = new SceneElement("element-1", "abs", 0, 4000,
				new SceneElement.Box(50, 50, 50, 50), SceneElement.noProps(), 0);
		byte[] pixels = render(boxed, List.of(new ElementComponent("abs", source, "")), List.of(), 500,
				Optional.empty());
		assertEquals(0, alphaAt(pixels, 2, 2), "the frame's top-left stays empty");
		assertTrue(alphaAt(pixels, W / 2 + 2, H / 2 + 2) > 200, "drawn at the box origin");
	}

	@Test
	void shaderFillsWithSksl() {
		String source = """
				export function render(ctx, { width, height }) {
				  ctx.fillStyle = shader('uniform float3 uC; half4 main(float2 p) { return half4(half3(uC), 1); }',
				    { uC: [0, 1, 0] });
				  ctx.fillRect(0, 0, width, height);
				}
				""";
		byte[] pixels = render(element("sh"), List.of(new ElementComponent("sh", source, "")), List.of(),
				500, Optional.empty());
		int at = (H / 2 * W + W / 2) * 4;
		assertTrue((pixels[at + 1] & 0xFF) > 240 && (pixels[at] & 0xFF) < 10 && (pixels[at + 2] & 0xFF) < 10,
				"green from the shader");
	}

	@Test
	void imageAssetDrawsAndPatterns() {
		byte[] png = redPng();
		ElementAsset asset = ElementAsset.of("logo", ElementAsset.Kind.IMAGE, png);
		String source = """
				export function render(ctx, { width, height }) {
				  ctx.drawImage(image('logo'), 0, 0, width / 2, height);
				  ctx.fillStyle = ctx.createPattern(image('logo'), 'repeat');
				  ctx.fillRect(width / 2, 0, width / 2, height);
				}
				""";
		byte[] pixels = render(element("img"), List.of(new ElementComponent("img", source, "")),
				List.of(asset), 500, Optional.empty());
		assertTrue(opaque(pixels) > W * H * 0.9, "image and pattern cover the frame");
	}

	@Test
	void lottieAssetPlays() {
		ElementAsset asset = ElementAsset.of("dot", ElementAsset.Kind.LOTTIE,
				LOTTIE.getBytes(StandardCharsets.UTF_8));
		SceneElement element = element("gravaai/lottie").withProps(props("{\"asset\":\"dot\"}"));
		byte[] early = render(element, List.of(), List.of(asset), 300, Optional.empty());
		byte[] later = render(element, List.of(), List.of(asset), 900, Optional.empty());
		assertTrue(opaque(early) > 50, "Lottie draws");
		assertTrue(Math.abs(centroidX(early) - centroidX(later)) > 3, "and animates");
	}

	@Test
	void model3dRendersBuiltinAndGlbModels() {
		SceneElement knot = element("gravaai/model-3d");
		assertTrue(opaque(render(knot, List.of(), List.of(), 2000, Optional.empty())) > 300,
				"built-in torus knot");
		ElementAsset glb = ElementAsset.of("tri", ElementAsset.Kind.MODEL, triangleGlb());
		SceneElement triangle = element("gravaai/model-3d").withProps(props(
				"{\"model\":\"tri\",\"spin\":0,\"tilt\":0}"));
		assertTrue(opaque(render(triangle, List.of(), List.of(glb), 2000, Optional.empty())) > 200,
				"a GLB asset");
	}

	@Test
	void shaderBackdropBuiltinFillsTheBox() {
		assertTrue(opaque(render(element("gravaai/shader-backdrop"), List.of(), List.of(), 2000,
				Optional.empty())) > W * H * 0.95);
	}

	@Test
	void batchedCallsKeepOrderAroundStateReads() {
		String source = """
				export function render(ctx) {
				  ctx.fillStyle = '#ff0000';
				  ctx.fillRect(0, 0, 400, 400);
				  ctx.font = 'bold 40px sans-serif';
				  const w = ctx.measureText('Hello').width;
				  if (!(w > 50)) throw new Error('measureText saw the font: ' + w);
				  if (ctx.fillStyle !== '#ff0000') throw new Error('fillStyle read back ' + ctx.fillStyle);
				  ctx.globalAlpha = 0.5;
				  if (Math.abs(ctx.globalAlpha - 0.5) > 1e-9) throw new Error('alpha ' + ctx.globalAlpha);
				}
				""";
		render(element("order"), List.of(new ElementComponent("order", source, "")), List.of(), 100,
				Optional.empty());
	}

	@Test
	void dropShadowFilterParses() {
		String source = """
				export function render(ctx) {
				  ctx.filter = 'drop-shadow(20px 20px 0px #ffffff)';
				  ctx.fillStyle = '#ffffff';
				  ctx.fillRect(100, 100, 200, 200);
				}
				""";
		byte[] pixels = render(element("shadow"), List.of(new ElementComponent("shadow", source, "")),
				List.of(), 100, Optional.empty());
		// Box ends at x=300 design px (30 output px); the shadow extends to 320 (32 px).
		assertTrue(alphaAt(pixels, 31, 31) > 100, "shadow beyond the box");
	}

	private static tools.jackson.databind.node.ObjectNode props(String json) {
		return (tools.jackson.databind.node.ObjectNode) dev.gravaai.core.json.Json
				.parseJsonWithByteOrderMark(json);
	}

	static byte[] redPng() {
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16,
				java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				image.setRGB(x, y, 0xFFFF2020);
			}
		}
		try {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (java.io.IOException error) {
			throw new java.io.UncheckedIOException(error);
		}
	}

	/** One triangle in a minimal GLB (positions only; normals are computed). */
	static byte[] triangleGlb() {
		ByteBuffer bin = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : new float[] {-1, -1, 0, 1, -1, 0, 0, 1, 0}) {
			bin.putFloat(value);
		}
		String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
				+ "\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
				+ "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
				+ "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],\"buffers\":[{\"byteLength\":36}]}";
		byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
		int jsonLength = (jsonBytes.length + 3) / 4 * 4;
		ByteBuffer glb = ByteBuffer.allocate(12 + 8 + jsonLength + 8 + 36).order(ByteOrder.LITTLE_ENDIAN);
		glb.putInt(0x46546C67).putInt(2).putInt(glb.capacity());
		glb.putInt(jsonLength).putInt(0x4E4F534A).put(jsonBytes);
		for (int pad = jsonBytes.length; pad < jsonLength; pad++) {
			glb.put((byte) ' ');
		}
		glb.putInt(36).putInt(0x004E4942).put(bin.array());
		return glb.array();
	}

	/** A 100x100, 1 s Lottie: a white ellipse moving left to right. */
	static final String LOTTIE = """
			{"v":"5.7.4","fr":30,"ip":0,"op":30,"w":100,"h":100,"layers":[{"ty":4,"ip":0,"op":30,"st":0,
			"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":1,"k":[{"t":0,"s":[20,50,0],"e":[80,50,0]},{"t":30}]},
			"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},
			"shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[30,30]}},
			{"ty":"fl","c":{"a":0,"k":[1,1,1,1]},"o":{"a":0,"k":100}}]}]}
			""";
}
