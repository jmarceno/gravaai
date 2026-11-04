package dev.gravaai.media.compose.scene3d;

import java.util.List;
import java.util.Optional;

/** Builds a {@link GltfModel} from in-memory arrays (procedural models). */
final class GltfModelFactory {

	private GltfModelFactory() {}

	static GltfModel single(float[] positions, float[] normals, float[] uvs, float[] colors,
			int[] indices) {
		return new GltfModel(List.of(new GltfModel.Primitive(positions, normals, uvs, colors, indices,
				new float[] {1, 1, 1, 1}, Optional.empty())));
	}
}
