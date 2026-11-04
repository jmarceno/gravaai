package dev.gravaai.media.compose.scene3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * A glTF 2.0 model flattened for rendering (plan §5.4 "3D"): every mesh primitive in the default
 * scene with its world transform baked into the vertices, plus its base colour and optional base
 * colour texture. Reads binary {@code .glb} and {@code .gltf} JSON with embedded
 * ({@code data:}) buffers; external files are rejected because assets must travel with the
 * project.
 *
 * <p>Supported: triangle primitives, indexed or not; POSITION, NORMAL (computed when missing),
 * TEXCOORD_0, COLOR_0; node TRS/matrix hierarchies; pbrMetallicRoughness baseColorFactor and
 * baseColorTexture. Skins, morph targets and animations are ignored.</p>
 */
public final class GltfModel {

	/** One drawable primitive in model space with the world transform applied. */
	public record Primitive(float[] positions, float[] normals, float[] uvs, float[] colors,
			int[] indices, float[] baseColor, Optional<byte[]> texture) {

		public int triangleCount() {
			return indices.length / 3;
		}
	}

	private final List<Primitive> primitives;
	private final float[] center;
	private final float radius;

	GltfModel(List<Primitive> primitives) {
		this.primitives = List.copyOf(primitives);
		float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (Primitive primitive : primitives) {
			float[] p = primitive.positions();
			for (int index = 0; index < p.length; index += 3) {
				for (int axis = 0; axis < 3; axis++) {
					min[axis] = Math.min(min[axis], p[index + axis]);
					max[axis] = Math.max(max[axis], p[index + axis]);
				}
			}
		}
		center = new float[] {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
		float r = 0;
		for (Primitive primitive : primitives) {
			float[] p = primitive.positions();
			for (int index = 0; index < p.length; index += 3) {
				float dx = p[index] - center[0];
				float dy = p[index + 1] - center[1];
				float dz = p[index + 2] - center[2];
				r = Math.max(r, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
		}
		radius = r > 0 ? r : 1;
	}

	public List<Primitive> primitives() {
		return primitives;
	}

	/** Bounding-sphere centre; the renderer normalises the model to a unit sphere around it. */
	public float[] center() {
		return center.clone();
	}

	public float radius() {
		return radius;
	}

	public int triangleCount() {
		return primitives.stream().mapToInt(Primitive::triangleCount).sum();
	}

	/** Parses {@code .glb} or embedded {@code .gltf} bytes. */
	public static GltfModel parse(byte[] bytes) {
		JsonNode root;
		Optional<ByteBuffer> binChunk = Optional.empty();
		if (bytes.length >= 12 && bytes[0] == 'g' && bytes[1] == 'l' && bytes[2] == 'T'
				&& bytes[3] == 'F') {
			ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			int length = Math.min(buffer.getInt(8), bytes.length);
			int at = 12;
			String json = "";
			while (at + 8 <= length) {
				int chunkLength = buffer.getInt(at);
				int chunkType = buffer.getInt(at + 4);
				if (chunkLength < 0 || at + 8 + chunkLength > length) {
					throw new IllegalArgumentException("truncated GLB chunk");
				}
				if (chunkType == 0x4E4F534A) {
					json = new String(bytes, at + 8, chunkLength, StandardCharsets.UTF_8);
				} else if (chunkType == 0x004E4942) {
					binChunk = Optional.of(ByteBuffer.wrap(bytes, at + 8, chunkLength).slice()
							.order(ByteOrder.LITTLE_ENDIAN));
				}
				at += 8 + chunkLength;
			}
			if (json.isEmpty()) {
				throw new IllegalArgumentException("GLB has no JSON chunk");
			}
			root = Json.MAPPER.readTree(json);
		} else {
			root = Json.MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
		}
		return new Reader(root, binChunk).read();
	}

	private static final class Reader {
		private final JsonNode root;
		private final List<ByteBuffer> buffers = new ArrayList<>();
		private final List<Primitive> out = new ArrayList<>();

		Reader(JsonNode root, Optional<ByteBuffer> bin) {
			this.root = root;
			JsonNode list = root.path("buffers");
			for (int index = 0; index < list.size(); index++) {
				JsonNode buffer = list.get(index);
				String uri = buffer.path("uri").asString("");
				if (uri.isEmpty()) {
					buffers.add(bin.orElseThrow(() -> new IllegalArgumentException(
							"buffer " + buffer + " has no data (GLB without BIN chunk)")));
				} else if (uri.startsWith("data:")) {
					int comma = uri.indexOf(',');
					buffers.add(ByteBuffer.wrap(Base64.getDecoder().decode(uri.substring(comma + 1)))
							.order(ByteOrder.LITTLE_ENDIAN));
				} else {
					throw new IllegalArgumentException("external glTF buffer \"" + uri
							+ "\" is not supported: export a .glb or embed the buffers");
				}
			}
		}

		GltfModel read() {
			JsonNode scenes = root.path("scenes");
			int sceneIndex = root.path("scene").asInt(0);
			if (scenes.size() > 0) {
				for (JsonNode node : scenes.get(Math.min(sceneIndex, scenes.size() - 1)).path("nodes")) {
					visit(node.asInt(), Mat4.identity(), 0);
				}
			} else {
				for (int index = 0; index < root.path("meshes").size(); index++) {
					mesh(index, Mat4.identity());
				}
			}
			if (out.isEmpty()) {
				throw new IllegalArgumentException("the model has no triangle meshes");
			}
			return new GltfModel(out);
		}

		private void visit(int nodeIndex, float[] parent, int depth) {
			if (depth > 64) {
				throw new IllegalArgumentException("glTF node hierarchy is too deep (cycle?)");
			}
			JsonNode node = root.path("nodes").get(nodeIndex);
			if (node == null) {
				return;
			}
			float[] local;
			JsonNode matrix = node.path("matrix");
			if (matrix.size() == 16) {
				local = new float[16];
				for (int index = 0; index < 16; index++) {
					local[index] = (float) matrix.get(index).asDouble();
				}
			} else {
				local = Mat4.trs(floats(node.path("translation"), 3, 0),
						floats(node.path("rotation"), 4, 0, 0, 0, 1), floats(node.path("scale"), 3, 1));
			}
			float[] world = Mat4.multiply(parent, local);
			if (node.has("mesh")) {
				mesh(node.path("mesh").asInt(), world);
			}
			for (JsonNode child : node.path("children")) {
				visit(child.asInt(), world, depth + 1);
			}
		}

		private void mesh(int meshIndex, float[] world) {
			JsonNode mesh = root.path("meshes").get(meshIndex);
			if (mesh == null) {
				return;
			}
			float[] normalMatrix = Mat4.normalMatrix(world);
			for (JsonNode primitive : mesh.path("primitives")) {
				int mode = primitive.path("mode").asInt(4);
				JsonNode attributes = primitive.path("attributes");
				if (mode != 4 || !attributes.has("POSITION")) {
					continue;
				}
				float[] positions = accessorFloats(attributes.path("POSITION").asInt(), 3);
				int vertexCount = positions.length / 3;
				int[] indices;
				if (primitive.has("indices")) {
					indices = accessorInts(primitive.path("indices").asInt());
				} else {
					indices = new int[vertexCount];
					for (int index = 0; index < vertexCount; index++) {
						indices[index] = index;
					}
				}
				for (int index : indices) {
					if (index < 0 || index >= vertexCount) {
						throw new IllegalArgumentException("index out of range in mesh " + meshIndex);
					}
				}
				float[] normals = attributes.has("NORMAL")
						? accessorFloats(attributes.path("NORMAL").asInt(), 3)
						: computeNormals(positions, indices);
				float[] uvs = attributes.has("TEXCOORD_0")
						? accessorFloats(attributes.path("TEXCOORD_0").asInt(), 2) : new float[vertexCount * 2];
				float[] colors = attributes.has("COLOR_0") ? colors(attributes.path("COLOR_0").asInt(),
						vertexCount) : filled(vertexCount * 4, 1);
				Mat4.transformPoints(world, positions);
				Mat4.transformNormals(normalMatrix, normals);
				float[] baseColor = {1, 1, 1, 1};
				Optional<byte[]> texture = Optional.empty();
				if (primitive.has("material")) {
					JsonNode pbr = root.path("materials").path(primitive.path("material").asInt())
							.path("pbrMetallicRoughness");
					baseColor = floats(pbr.path("baseColorFactor"), 4, 1, 1, 1, 1);
					if (pbr.has("baseColorTexture")) {
						texture = image(pbr.path("baseColorTexture").path("index").asInt());
					}
				}
				out.add(new Primitive(positions, normals, uvs, colors, indices, baseColor, texture));
			}
		}

		private Optional<byte[]> image(int textureIndex) {
			JsonNode texture = root.path("textures").path(textureIndex);
			if (!texture.has("source")) {
				return Optional.empty();
			}
			JsonNode image = root.path("images").path(texture.path("source").asInt());
			String uri = image.path("uri").asString("");
			if (uri.startsWith("data:")) {
				return Optional.of(Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1)));
			}
			if (image.has("bufferView")) {
				ByteBuffer view = bufferView(image.path("bufferView").asInt());
				byte[] bytes = new byte[view.remaining()];
				view.get(bytes);
				return Optional.of(bytes);
			}
			return Optional.empty();
		}

		private ByteBuffer bufferView(int index) {
			JsonNode view = root.path("bufferViews").get(index);
			if (view == null) {
				throw new IllegalArgumentException("missing bufferView " + index);
			}
			ByteBuffer buffer = buffers.get(view.path("buffer").asInt());
			int offset = view.path("byteOffset").asInt(0);
			int length = view.path("byteLength").asInt();
			if (offset < 0 || length < 0 || offset + length > buffer.capacity()) {
				throw new IllegalArgumentException("bufferView " + index + " is out of range");
			}
			return buffer.duplicate().position(offset).limit(offset + length).slice()
					.order(ByteOrder.LITTLE_ENDIAN);
		}

		/** Reads an accessor as floats (normalised integer types are scaled to 0..1 / -1..1). */
		private float[] accessorFloats(int accessorIndex, int expectedComponents) {
			JsonNode accessor = root.path("accessors").get(accessorIndex);
			if (accessor == null) {
				throw new IllegalArgumentException("missing accessor " + accessorIndex);
			}
			int count = accessor.path("count").asInt();
			int components = components(accessor.path("type").asString("SCALAR"));
			int componentType = accessor.path("componentType").asInt();
			boolean normalized = accessor.path("normalized").asBoolean(false);
			float[] values = new float[count * expectedComponents];
			if (!accessor.has("bufferView")) {
				return values;
			}
			ByteBuffer view = bufferView(accessor.path("bufferView").asInt());
			int size = componentSize(componentType);
			int stride = root.path("bufferViews").get(accessor.path("bufferView").asInt())
					.path("byteStride").asInt(0);
			if (stride == 0) {
				stride = size * components;
			}
			int base = accessor.path("byteOffset").asInt(0);
			for (int element = 0; element < count; element++) {
				for (int component = 0; component < Math.min(components, expectedComponents); component++) {
					int at = base + element * stride + component * size;
					values[element * expectedComponents + component] = read(view, at, componentType,
							normalized);
				}
			}
			return values;
		}

		private int[] accessorInts(int accessorIndex) {
			JsonNode accessor = root.path("accessors").get(accessorIndex);
			if (accessor == null || !accessor.has("bufferView")) {
				throw new IllegalArgumentException("missing index accessor " + accessorIndex);
			}
			int count = accessor.path("count").asInt();
			int componentType = accessor.path("componentType").asInt();
			ByteBuffer view = bufferView(accessor.path("bufferView").asInt());
			int size = componentSize(componentType);
			int base = accessor.path("byteOffset").asInt(0);
			int[] values = new int[count];
			for (int index = 0; index < count; index++) {
				int at = base + index * size;
				values[index] = switch (componentType) {
					case 5121 -> view.get(at) & 0xFF;
					case 5123 -> view.getShort(at) & 0xFFFF;
					case 5125 -> view.getInt(at);
					default -> throw new IllegalArgumentException("bad index type " + componentType);
				};
			}
			return values;
		}

		private float[] colors(int accessorIndex, int vertexCount) {
			JsonNode accessor = root.path("accessors").get(accessorIndex);
			int components = components(accessor == null ? "VEC4" : accessor.path("type").asString("VEC4"));
			float[] raw = accessorFloats(accessorIndex, components);
			float[] rgba = filled(vertexCount * 4, 1);
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				for (int channel = 0; channel < components && channel < 4; channel++) {
					rgba[vertex * 4 + channel] = raw[vertex * components + channel];
				}
			}
			return rgba;
		}

		private static float read(ByteBuffer view, int at, int type, boolean normalized) {
			return switch (type) {
				case 5126 -> view.getFloat(at);
				case 5121 -> normalized ? (view.get(at) & 0xFF) / 255f : view.get(at) & 0xFF;
				case 5120 -> normalized ? Math.max(-1, view.get(at) / 127f) : view.get(at);
				case 5123 -> normalized ? (view.getShort(at) & 0xFFFF) / 65535f : view.getShort(at) & 0xFFFF;
				case 5122 -> normalized ? Math.max(-1, view.getShort(at) / 32767f) : view.getShort(at);
				case 5125 -> view.getInt(at);
				default -> throw new IllegalArgumentException("unsupported component type " + type);
			};
		}

		private static int componentSize(int type) {
			return switch (type) {
				case 5120, 5121 -> 1;
				case 5122, 5123 -> 2;
				case 5125, 5126 -> 4;
				default -> throw new IllegalArgumentException("unsupported component type " + type);
			};
		}

		private static int components(String type) {
			return switch (type) {
				case "SCALAR" -> 1;
				case "VEC2" -> 2;
				case "VEC3" -> 3;
				case "VEC4", "MAT2" -> 4;
				case "MAT3" -> 9;
				case "MAT4" -> 16;
				default -> throw new IllegalArgumentException("unsupported accessor type " + type);
			};
		}
	}

	private static float[] floats(JsonNode array, int size, double... defaults) {
		float[] values = new float[size];
		for (int index = 0; index < size; index++) {
			double fallback = defaults.length == 1 ? defaults[0] : defaults[index];
			values[index] = (float) (array.size() > index ? array.get(index).asDouble(fallback) : fallback);
		}
		return values;
	}

	private static float[] filled(int size, float value) {
		float[] values = new float[size];
		java.util.Arrays.fill(values, value);
		return values;
	}

	/** Flat-area-weighted vertex normals for meshes that ship none. */
	static float[] computeNormals(float[] positions, int[] indices) {
		float[] normals = new float[positions.length];
		for (int index = 0; index + 2 < indices.length; index += 3) {
			int a = indices[index] * 3;
			int b = indices[index + 1] * 3;
			int c = indices[index + 2] * 3;
			float ux = positions[b] - positions[a];
			float uy = positions[b + 1] - positions[a + 1];
			float uz = positions[b + 2] - positions[a + 2];
			float vx = positions[c] - positions[a];
			float vy = positions[c + 1] - positions[a + 1];
			float vz = positions[c + 2] - positions[a + 2];
			float nx = uy * vz - uz * vy;
			float ny = uz * vx - ux * vz;
			float nz = ux * vy - uy * vx;
			for (int vertex : new int[] {a, b, c}) {
				normals[vertex] += nx;
				normals[vertex + 1] += ny;
				normals[vertex + 2] += nz;
			}
		}
		Mat4.normalizeEach(normals);
		return normals;
	}
}
