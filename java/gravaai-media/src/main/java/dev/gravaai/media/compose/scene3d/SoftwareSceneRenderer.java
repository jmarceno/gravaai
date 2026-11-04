package dev.gravaai.media.compose.scene3d;

import java.util.Map;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

/**
 * The CPU path of {@code scene3d}: a z-buffered, perspective-correct triangle rasteriser with
 * per-vertex lighting. Used when the frame has no GL context (component validation, unit tests,
 * raster canvases); the compositor's GPU frames use {@link GlSceneRenderer}.
 */
public final class SoftwareSceneRenderer {

	private static final Map<byte[], int[]> TEXTURES = java.util.Collections.synchronizedMap(
			new java.util.WeakHashMap<>());

	private SoftwareSceneRenderer() {}

	/** Renders {@code model} into a new premultiplied image of {@code width x height}. */
	public static Image render(GltfModel model, Scene3D scene, int width, int height) {
		int w = Math.max(1, Math.min(4096, width));
		int h = Math.max(1, Math.min(4096, height));
		int[] pixels = new int[w * h];
		float[] depth = new float[w * h];
		java.util.Arrays.fill(depth, Float.MAX_VALUE);
		float[] modelMatrix = scene.model(model);
		float[] normalMatrix = Mat4.normalMatrix(modelMatrix);
		float[] viewProjection = Mat4.multiply(scene.projection(w / (double) h), scene.view());
		float[] tint = argb(scene.tint());
		for (GltfModel.Primitive primitive : model.primitives()) {
			int vertices = primitive.positions().length / 3;
			float[] sx = new float[vertices];
			float[] sy = new float[vertices];
			float[] sz = new float[vertices];
			float[] invW = new float[vertices];
			float[] lit = new float[vertices * 3];
			float[] alpha = new float[vertices];
			float[] p = primitive.positions();
			float[] n = primitive.normals();
			float[] c = primitive.colors();
			float[] base = primitive.baseColor();
			for (int v = 0; v < vertices; v++) {
				float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
				float wx = modelMatrix[0] * x + modelMatrix[4] * y + modelMatrix[8] * z + modelMatrix[12];
				float wy = modelMatrix[1] * x + modelMatrix[5] * y + modelMatrix[9] * z + modelMatrix[13];
				float wz = modelMatrix[2] * x + modelMatrix[6] * y + modelMatrix[10] * z + modelMatrix[14];
				float cx = viewProjection[0] * wx + viewProjection[4] * wy + viewProjection[8] * wz + viewProjection[12];
				float cy = viewProjection[1] * wx + viewProjection[5] * wy + viewProjection[9] * wz + viewProjection[13];
				float cz = viewProjection[2] * wx + viewProjection[6] * wy + viewProjection[10] * wz + viewProjection[14];
				float cw = viewProjection[3] * wx + viewProjection[7] * wy + viewProjection[11] * wz + viewProjection[15];
				float iw = cw > 1e-6f ? 1 / cw : 0;
				invW[v] = iw;
				sx[v] = (cx * iw * 0.5f + 0.5f) * w;
				sy[v] = (1 - (cy * iw * 0.5f + 0.5f)) * h;
				sz[v] = cz * iw;
				float nx = normalMatrix[0] * n[v * 3] + normalMatrix[4] * n[v * 3 + 1] + normalMatrix[8] * n[v * 3 + 2];
				float ny = normalMatrix[1] * n[v * 3] + normalMatrix[5] * n[v * 3 + 1] + normalMatrix[9] * n[v * 3 + 2];
				float nz = normalMatrix[2] * n[v * 3] + normalMatrix[6] * n[v * 3 + 1] + normalMatrix[10] * n[v * 3 + 2];
				float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (length > 1e-9f) {
					nx /= length;
					ny /= length;
					nz /= length;
				}
				float[] shaded = Scene3D.shade(base[0] * c[v * 4] * tint[1], base[1] * c[v * 4 + 1] * tint[2],
						base[2] * c[v * 4 + 2] * tint[3], nx, ny, nz, scene);
				lit[v * 3] = shaded[0];
				lit[v * 3 + 1] = shaded[1];
				lit[v * 3 + 2] = shaded[2];
				alpha[v] = base[3] * c[v * 4 + 3] * tint[0];
			}
			int[] texture = primitive.texture().map(SoftwareSceneRenderer::texture).orElse(new int[0]);
			int textureSize = texture.length == 0 ? 0 : texture[0];
			int[] indices = primitive.indices();
			float[] uv = primitive.uvs();
			for (int t = 0; t + 2 < indices.length; t += 3) {
				int a = indices[t], b = indices[t + 1], d = indices[t + 2];
				if (invW[a] == 0 || invW[b] == 0 || invW[d] == 0) {
					continue;
				}
				float area = (sx[b] - sx[a]) * (sy[d] - sy[a]) - (sy[b] - sy[a]) * (sx[d] - sx[a]);
				if (Math.abs(area) < 1e-9f) {
					continue;
				}
				int minX = Math.max(0, (int) Math.floor(Math.min(sx[a], Math.min(sx[b], sx[d]))));
				int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(sx[a], Math.max(sx[b], sx[d]))));
				int minY = Math.max(0, (int) Math.floor(Math.min(sy[a], Math.min(sy[b], sy[d]))));
				int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(sy[a], Math.max(sy[b], sy[d]))));
				for (int y = minY; y <= maxY; y++) {
					float py = y + 0.5f;
					for (int x = minX; x <= maxX; x++) {
						float px = x + 0.5f;
						float w0 = ((sx[b] - px) * (sy[d] - py) - (sy[b] - py) * (sx[d] - px)) / area;
						float w1 = ((sx[d] - px) * (sy[a] - py) - (sy[d] - py) * (sx[a] - px)) / area;
						float w2 = 1 - w0 - w1;
						if (w0 < 0 || w1 < 0 || w2 < 0) {
							continue;
						}
						float z = w0 * sz[a] + w1 * sz[b] + w2 * sz[d];
						int at = y * w + x;
						if (z < -1 || z > 1 || z >= depth[at]) {
							continue;
						}
						depth[at] = z;
						float pw = w0 * invW[a] + w1 * invW[b] + w2 * invW[d];
						float k0 = w0 * invW[a] / pw, k1 = w1 * invW[b] / pw, k2 = w2 * invW[d] / pw;
						float r = k0 * lit[a * 3] + k1 * lit[b * 3] + k2 * lit[d * 3];
						float g = k0 * lit[a * 3 + 1] + k1 * lit[b * 3 + 1] + k2 * lit[d * 3 + 1];
						float bl = k0 * lit[a * 3 + 2] + k1 * lit[b * 3 + 2] + k2 * lit[d * 3 + 2];
						float al = k0 * alpha[a] + k1 * alpha[b] + k2 * alpha[d];
						if (textureSize > 0) {
							float u = k0 * uv[a * 2] + k1 * uv[b * 2] + k2 * uv[d * 2];
							float vv = k0 * uv[a * 2 + 1] + k1 * uv[b * 2 + 1] + k2 * uv[d * 2 + 1];
							int tw = texture[0];
							int th = texture[1];
							int tx = Math.floorMod((int) Math.floor(u * tw), tw);
							int ty = Math.floorMod((int) Math.floor(vv * th), th);
							int texel = texture[2 + ty * tw + tx];
							r *= ((texel >> 16) & 0xFF) / 255f;
							g *= ((texel >> 8) & 0xFF) / 255f;
							bl *= (texel & 0xFF) / 255f;
							al *= ((texel >>> 24) & 0xFF) / 255f;
						}
						al = Math.max(0, Math.min(1, al));
						int ia = Math.round(al * 255);
						pixels[at] = (ia << 24) | (Math.round(Math.min(1, r) * al * 255) << 16)
								| (Math.round(Math.min(1, g) * al * 255) << 8) | Math.round(Math.min(1, bl) * al * 255);
					}
				}
			}
		}
		byte[] bytes = new byte[w * h * 4];
		for (int index = 0; index < pixels.length; index++) {
			int pixel = pixels[index];
			bytes[index * 4] = (byte) (pixel >> 16);
			bytes[index * 4 + 1] = (byte) (pixel >> 8);
			bytes[index * 4 + 2] = (byte) pixel;
			bytes[index * 4 + 3] = (byte) (pixel >>> 24);
		}
		return Image.makeRasterFromBytes(new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
				bytes, w * 4L);
	}

	/** Decoded texture as [width, height, argb...], cached by array identity. */
	private static int[] texture(byte[] encoded) {
		return TEXTURES.computeIfAbsent(encoded, key -> {
			try (Image image = Image.makeDeferredFromEncodedBytes(encoded)) {
				int width = image.getWidth();
				int height = image.getHeight();
				try (Bitmap bitmap = new Bitmap()) {
					bitmap.allocPixels(new ImageInfo(width, height, ColorType.BGRA_8888,
							ColorAlphaType.UNPREMUL));
					if (!image.readPixels(bitmap)) {
						return new int[0];
					}
					byte[] raw = bitmap.readPixels();
					int[] out = new int[2 + width * height];
					out[0] = width;
					out[1] = height;
					for (int index = 0; index < width * height; index++) {
						out[2 + index] = ((raw[index * 4 + 3] & 0xFF) << 24) | ((raw[index * 4 + 2] & 0xFF) << 16)
								| ((raw[index * 4 + 1] & 0xFF) << 8) | (raw[index * 4] & 0xFF);
					}
					return out;
				}
			} catch (RuntimeException error) {
				return new int[0];
			}
		});
	}

	private static float[] argb(int color) {
		return new float[] {((color >>> 24) & 0xFF) / 255f, ((color >> 16) & 0xFF) / 255f,
			((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f};
	}
}
