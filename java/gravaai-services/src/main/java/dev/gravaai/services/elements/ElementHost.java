package dev.gravaai.services.elements;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.skija.skottie.Animation;
import io.github.humbleui.types.Rect;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ElementAsset;
import dev.gravaai.media.compose.scene3d.GlSceneRenderer;
import dev.gravaai.media.compose.scene3d.GltfModel;
import dev.gravaai.media.compose.scene3d.ProceduralModels;
import dev.gravaai.media.compose.scene3d.Scene3D;
import dev.gravaai.media.compose.scene3d.SoftwareSceneRenderer;
import dev.gravaai.media.gl.SkiaGlSurface;
import dev.gravaai.services.extensions.canvas.Canvas2D;
import dev.gravaai.services.extensions.canvas.CanvasImage;
import dev.gravaai.services.extensions.canvas.CanvasShader;
import tools.jackson.databind.JsonNode;

/**
 * The host side of the component asset helpers (plan §5.2): {@code image(name)},
 * {@code lottie(name)}, {@code scene3d(ctx, …)} and {@code shader(src, uniforms)}. Asset bytes
 * come from the project ({@link ElementAsset}) and are decoded once per content hash; what the
 * current frame may use (the project's assets, the GL surface) is set per render call by
 * {@link JsElementRuntime} on the calling thread.
 */
public final class ElementHost {

	/** What the frame being rendered on this thread can use. */
	record Scope(List<ElementAsset> assets, Optional<SkiaGlSurface> gpu) {
		static final Scope EMPTY = new Scope(List.of(), Optional.empty());
	}

	private final ThreadLocal<Scope> scope = ThreadLocal.withInitial(() -> Scope.EMPTY);
	private final Map<String, CanvasImage> images = new ConcurrentHashMap<>();
	private final Map<String, Typeface> typefaces = new ConcurrentHashMap<>();
	private final Map<String, GltfModel> models = new ConcurrentHashMap<>();
	/** Skottie animations hold seek state, so each render thread decodes its own. */
	private final ThreadLocal<Map<String, Animation>> animations = ThreadLocal.withInitial(HashMap::new);
	private final ThreadLocal<Optional<GlSceneRenderer>> renderers = ThreadLocal.withInitial(Optional::empty);

	void enter(Scope next) {
		scope.set(next);
	}

	void exit() {
		scope.set(Scope.EMPTY);
	}

	/** Project fonts for {@code ctx.font}: a FONT asset matches by its name or family name. */
	Canvas2D.FontResolver fonts() {
		return (family, style) -> {
			for (ElementAsset asset : scope.get().assets()) {
				if (asset.kind() != ElementAsset.Kind.FONT) {
					continue;
				}
				Typeface typeface = typeface(asset);
				String wanted = family.replace("\"", "").replace("'", "").trim();
				if (wanted.equalsIgnoreCase(asset.name())
						|| wanted.equalsIgnoreCase(typeface.getFamilyName())) {
					return Optional.of(typeface);
				}
			}
			return Optional.empty();
		};
	}

	/** {@code image(name)}: an IMAGE asset for {@code ctx.drawImage} or {@code createPattern}. */
	@HostAccess.Export
	public CanvasImage image(String name) {
		ElementAsset asset = asset(name, ElementAsset.Kind.IMAGE);
		return images.computeIfAbsent(asset.hash(), ignored -> CanvasImage.fromEncoded(asset.bytes()));
	}

	/** {@code [duration seconds, fps, width, height]} of a LOTTIE asset. */
	@HostAccess.Export
	public double[] lottieInfo(String name) {
		Animation animation = animation(name);
		return new double[] {animation.getDuration(), animation.getFPS(), animation.getSize().getX(),
			animation.getSize().getY()};
	}

	/** Draws a LOTTIE asset at {@code seconds} into the rectangle (current ctx transform). */
	@HostAccess.Export
	public void drawLottie(Canvas2D ctx, String name, double seconds, double x, double y, double w,
			double h) {
		Animation animation = animation(name);
		double time = Double.isFinite(seconds) ? Math.max(0, Math.min(animation.getDuration(), seconds)) : 0;
		animation.seekFrameTime((float) time);
		Canvas canvas = ctx.skiaCanvas();
		int saved = layer(canvas, ctx.currentAlpha());
		try {
			animation.render(canvas, Rect.makeXYWH((float) x, (float) y, (float) w, (float) h));
		} finally {
			canvas.restoreToCount(saved);
		}
	}

	/**
	 * Draws a MODEL asset ({@code .glb}/{@code .gltf}) posed by {@code optionsJson} into the
	 * rectangle: on the GPU when the frame has a GL surface, otherwise with the CPU renderer.
	 */
	@HostAccess.Export
	public void drawScene(Canvas2D ctx, String name, String optionsJson, double x, double y,
			double w, double h) {
		if (!(w > 0) || !(h > 0)) {
			return;
		}
		GltfModel model = ProceduralModels.find(name).orElseGet(() -> {
			ElementAsset asset = asset(name, ElementAsset.Kind.MODEL);
			return models.computeIfAbsent(asset.hash(), ignored -> {
				try {
					return GltfModel.parse(asset.bytes());
				} catch (RuntimeException error) {
					throw new IllegalArgumentException("model \"" + name + "\": " + error.getMessage());
				}
			});
		});
		Scene3D scene = scene(Json.parseJsonWithByteOrderMark(optionsJson));
		Canvas canvas = ctx.skiaCanvas();
		double deviceScale = deviceScale(canvas.getLocalToDeviceAsMatrix33());
		int pixelWidth = (int) Math.max(1, Math.min(4096, Math.round(w * deviceScale)));
		int pixelHeight = (int) Math.max(1, Math.min(4096, Math.round(h * deviceScale)));
		Optional<SkiaGlSurface> gpu = scope.get().gpu();
		Image image;
		if (gpu.isPresent()) {
			GlSceneRenderer renderer = renderers.get().filter(existing -> existing.belongsTo(gpu.get()))
					.orElseGet(() -> {
						GlSceneRenderer created = new GlSceneRenderer(gpu.get());
						renderers.set(Optional.of(created));
						return created;
					});
			image = renderer.render(gpu.get(), model, scene, pixelWidth, pixelHeight);
		} else {
			image = SoftwareSceneRenderer.render(model, scene, pixelWidth, pixelHeight);
		}
		try (Paint paint = new Paint()) {
			double alpha = ctx.currentAlpha();
			if (alpha < 1) {
				paint.setAlphaf((float) Math.max(0, alpha));
			}
			canvas.drawImageRect(image, Rect.makeWH(image.getWidth(), image.getHeight()),
					Rect.makeXYWH((float) x, (float) y, (float) w, (float) h), SamplingMode.LINEAR, paint, true);
		} finally {
			image.close();
		}
	}

	/** {@code shader(sksl, uniforms)}: an SkSL fill/stroke style. */
	@HostAccess.Export
	public CanvasShader shader(String sksl, @Nullable Value uniforms) {
		return CanvasShader.create(sksl, uniforms);
	}

	/** Names of the project's assets of {@code kind} ("image", "lottie", "font", "model"). */
	@HostAccess.Export
	public String[] assetNames(String kind) {
		return scope.get().assets().stream().filter(asset -> asset.kind().wire().equals(kind))
				.map(ElementAsset::name).toArray(String[]::new);
	}

	private ElementAsset asset(String name, ElementAsset.Kind kind) {
		ElementAsset asset = ElementAsset.find(scope.get().assets(), String.valueOf(name))
				.orElseThrow(() -> new IllegalArgumentException("no asset named \"" + name
						+ "\" in this project (add it with the edit op {op:\"asset\", name, path})"));
		if (asset.kind() != kind) {
			throw new IllegalArgumentException("asset \"" + name + "\" is a " + asset.kind().wire()
					+ ", not a " + kind.wire());
		}
		return asset;
	}

	private Animation animation(String name) {
		ElementAsset asset = asset(name, ElementAsset.Kind.LOTTIE);
		return animations.get().computeIfAbsent(asset.hash(), ignored -> {
			Animation animation = Animation.makeFromString(new String(asset.bytes(),
					java.nio.charset.StandardCharsets.UTF_8));
			if (animation == null) {
				throw new IllegalArgumentException("lottie \"" + name + "\" is not a valid Lottie JSON");
			}
			return animation;
		});
	}

	private Typeface typeface(ElementAsset asset) {
		return typefaces.computeIfAbsent(asset.hash(), ignored -> {
			try (Data data = Data.makeFromBytes(asset.bytes())) {
				Typeface typeface = FontMgr.getDefault().makeFromData(data);
				if (typeface == null) {
					throw new IllegalArgumentException("font \"" + asset.name() + "\" could not be read");
				}
				return typeface;
			}
		});
	}

	private static int layer(Canvas canvas, double alpha) {
		if (alpha >= 1) {
			return canvas.save();
		}
		return canvas.saveLayerAlpha(null, (int) Math.round(Math.max(0, alpha) * 255));
	}

	private static double deviceScale(Matrix33 matrix) {
		float[] m = matrix.getMat();
		double determinant = Math.abs(m[0] * m[4] - m[1] * m[3]);
		return determinant > 0 ? Math.sqrt(determinant) : 1;
	}

	private static Scene3D scene(JsonNode options) {
		Scene3D d = Scene3D.DEFAULT;
		JsonNode rotation = options.path("rotation");
		JsonNode position = options.path("position");
		JsonNode light = options.path("light");
		JsonNode direction = light.path("direction");
		return new Scene3D(
				number(rotation, 0, d.rotateX()), number(rotation, 1, d.rotateY()), number(rotation, 2, d.rotateZ()),
				options.path("scale").asDouble(d.scale()),
				number(position, 0, d.offsetX()), number(position, 1, d.offsetY()), number(position, 2, d.offsetZ()),
				options.path("cameraDistance").asDouble(d.cameraDistance()),
				options.path("fov").asDouble(d.fov()),
				number(direction, 0, d.lightX()), number(direction, 1, d.lightY()), number(direction, 2, d.lightZ()),
				color(light.path("color"), 0xFFFFFFFF) & 0xFFFFFF,
				options.path("ambient").asDouble(d.ambient()),
				options.path("specular").asDouble(d.specular()),
				color(options.path("tint"), d.tint()));
	}

	private static double number(JsonNode array, int index, double fallback) {
		return array.size() > index ? array.get(index).asDouble(fallback) : fallback;
	}

	private static int color(JsonNode node, int fallback) {
		if (!node.isString()) {
			return fallback;
		}
		Integer parsed = dev.gravaai.services.extensions.canvas.CanvasColor.parse(node.asString());
		return parsed == null ? fallback : parsed;
	}
}
