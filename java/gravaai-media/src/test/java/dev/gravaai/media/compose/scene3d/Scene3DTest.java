package dev.gravaai.media.compose.scene3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

class Scene3DTest {

	private static long opaque(Image image) {
		try (Bitmap bitmap = new Bitmap()) {
			bitmap.allocPixels(ImageInfo.makeN32(image.getWidth(), image.getHeight(), ColorAlphaType.PREMUL));
			image.readPixels(bitmap);
			byte[] pixels = bitmap.readPixels();
			long count = 0;
			for (int index = 3; index < pixels.length; index += 4) {
				if ((pixels[index] & 0xFF) > 0) {
					count++;
				}
			}
			return count;
		}
	}

	@Test
	void embeddedGltfParsesWithTransforms() {
		byte[] bin = new byte[36];
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bin).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (float value : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}) {
			buffer.putFloat(value);
		}
		String json = "{\"asset\":{\"version\":\"2.0\"},\"scenes\":[{\"nodes\":[0]}],"
				+ "\"nodes\":[{\"mesh\":0,\"translation\":[10,0,0],\"scale\":[2,2,2]}],"
				+ "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
				+ "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
				+ "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],"
				+ "\"buffers\":[{\"byteLength\":36,\"uri\":\"data:application/octet-stream;base64,"
				+ Base64.getEncoder().encodeToString(bin) + "\"}]}";
		GltfModel model = GltfModel.parse(json.getBytes(StandardCharsets.UTF_8));
		assertEquals(1, model.triangleCount());
		float[] p = model.primitives().getFirst().positions();
		assertEquals(12, p[3], 1e-5, "translation + scale applied");
		assertEquals(1, model.primitives().getFirst().normals()[2], 1e-5, "normal computed (+Z)");
	}

	@Test
	void externalBuffersAreRejected() {
		String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"model.bin\",\"byteLength\":4}]}";
		assertThrows(IllegalArgumentException.class,
				() -> GltfModel.parse(json.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void softwareRendererDrawsEveryProceduralModel() {
		for (String name : ProceduralModels.NAMES) {
			GltfModel model = ProceduralModels.find(ProceduralModels.PREFIX + name).orElseThrow();
			try (Image image = SoftwareSceneRenderer.render(model, Scene3D.DEFAULT, 160, 120)) {
				assertTrue(opaque(image) > 1500, name + " covers the view");
			}
		}
	}

	@Test
	void rendersDeterministically() {
		GltfModel model = ProceduralModels.find("gravaai:torus-knot").orElseThrow();
		Scene3D pose = new Scene3D(20, 45, 0, 1, 0, 0, 0, 3, 35, -0.4, 0.7, 0.6, 0xFFFFFF, 0.3, 0.3, 0xFFFFFFFF);
		try (Image first = SoftwareSceneRenderer.render(model, pose, 120, 90);
				Image second = SoftwareSceneRenderer.render(model, pose, 120, 90)) {
			assertEquals(opaque(first), opaque(second));
		}
	}
}
