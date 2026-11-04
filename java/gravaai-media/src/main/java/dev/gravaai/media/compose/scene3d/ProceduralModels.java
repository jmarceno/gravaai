package dev.gravaai.media.compose.scene3d;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Built-in models a component can use without importing a file: {@code gravaai:cube},
 * {@code gravaai:sphere}, {@code gravaai:torus} and {@code gravaai:torus-knot}. Generated once,
 * smooth-shaded, with a soft vertical colour gradient so lighting reads well.
 */
public final class ProceduralModels {

	public static final String PREFIX = "gravaai:";
	public static final List<String> NAMES = List.of("cube", "sphere", "torus", "torus-knot");

	private static final java.util.Map<String, GltfModel> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	private ProceduralModels() {}

	public static Optional<GltfModel> find(String name) {
		if (!name.startsWith(PREFIX)) {
			return Optional.empty();
		}
		String key = name.substring(PREFIX.length()).toLowerCase(Locale.ROOT);
		if (!NAMES.contains(key)) {
			return Optional.empty();
		}
		return Optional.of(CACHE.computeIfAbsent(key, ProceduralModels::build));
	}

	private static GltfModel build(String key) {
		return switch (key) {
			case "cube" -> cube();
			case "sphere" -> sweep(64, 32, (u, v) -> {
				double theta = u * 2 * Math.PI;
				double phi = v * Math.PI;
				return new double[] {Math.sin(phi) * Math.cos(theta), Math.cos(phi), Math.sin(phi) * Math.sin(theta)};
			});
			case "torus" -> sweep(96, 32, (u, v) -> torus(u * 2 * Math.PI, v * 2 * Math.PI, 1, 0.38));
			default -> knot();
		};
	}

	private interface Surface {
		double[] at(double u, double v);
	}

	private static double[] torus(double theta, double phi, double major, double minor) {
		double ring = major + minor * Math.cos(phi);
		return new double[] {ring * Math.cos(theta), minor * Math.sin(phi), ring * Math.sin(theta)};
	}

	/** A (u, v) grid over a parametric surface; normals are computed from the triangles. */
	private static GltfModel sweep(int segmentsU, int segmentsV, Surface surface) {
		int columns = segmentsU + 1;
		int rows = segmentsV + 1;
		float[] positions = new float[columns * rows * 3];
		float[] uvs = new float[columns * rows * 2];
		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				double u = column / (double) segmentsU;
				double v = row / (double) segmentsV;
				double[] p = surface.at(u, v);
				int at = row * columns + column;
				positions[at * 3] = (float) p[0];
				positions[at * 3 + 1] = (float) p[1];
				positions[at * 3 + 2] = (float) p[2];
				uvs[at * 2] = (float) u;
				uvs[at * 2 + 1] = (float) v;
			}
		}
		int[] indices = new int[segmentsU * segmentsV * 6];
		int next = 0;
		for (int row = 0; row < segmentsV; row++) {
			for (int column = 0; column < segmentsU; column++) {
				int a = row * columns + column;
				int b = a + 1;
				int c = a + columns;
				int d = c + 1;
				indices[next++] = a;
				indices[next++] = c;
				indices[next++] = b;
				indices[next++] = b;
				indices[next++] = c;
				indices[next++] = d;
			}
		}
		float[] normals = GltfModel.computeNormals(positions, indices);
		weldSeams(positions, normals);
		// Grid winding depends on the parametrisation: point the normals away from the centre.
		double outward = 0;
		for (int index = 0; index < normals.length; index += 3) {
			outward += normals[index] * positions[index] + normals[index + 1] * positions[index + 1]
					+ normals[index + 2] * positions[index + 2];
		}
		if (outward < 0) {
			for (int index = 0; index < normals.length; index++) {
				normals[index] = -normals[index];
			}
		}
		return GltfModelFactory.single(positions, normals, uvs, gradient(positions), indices);
	}

	/** Averages normals of vertices at the same position (closes the u/v seams). */
	private static void weldSeams(float[] positions, float[] normals) {
		java.util.Map<String, float[]> sums = new java.util.HashMap<>();
		int count = positions.length / 3;
		for (int index = 0; index < count; index++) {
			String key = Math.round(positions[index * 3] * 1e4) + ":" + Math.round(positions[index * 3 + 1] * 1e4)
					+ ":" + Math.round(positions[index * 3 + 2] * 1e4);
			float[] sum = sums.computeIfAbsent(key, ignored -> new float[3]);
			sum[0] += normals[index * 3];
			sum[1] += normals[index * 3 + 1];
			sum[2] += normals[index * 3 + 2];
		}
		for (int index = 0; index < count; index++) {
			String key = Math.round(positions[index * 3] * 1e4) + ":" + Math.round(positions[index * 3 + 1] * 1e4)
					+ ":" + Math.round(positions[index * 3 + 2] * 1e4);
			float[] sum = sums.get(key);
			normals[index * 3] = sum[0];
			normals[index * 3 + 1] = sum[1];
			normals[index * 3 + 2] = sum[2];
		}
		Mat4.normalizeEach(normals);
	}

	/** A (2,3) torus knot tube. */
	private static GltfModel knot() {
		int segments = 256;
		int sides = 24;
		double tube = 0.16;
		Surface surface = (u, v) -> {
			double t = u * 2 * Math.PI;
			double[] center = knotPoint(t);
			double[] ahead = knotPoint(t + 1e-3);
			double[] tangent = normalize(sub(ahead, center));
			double[] normal = normalize(cross(tangent, new double[] {0, 1, 0.3}));
			double[] binormal = cross(tangent, normal);
			double phi = v * 2 * Math.PI;
			return new double[] {
				center[0] + tube * (Math.cos(phi) * normal[0] + Math.sin(phi) * binormal[0]),
				center[1] + tube * (Math.cos(phi) * normal[1] + Math.sin(phi) * binormal[1]),
				center[2] + tube * (Math.cos(phi) * normal[2] + Math.sin(phi) * binormal[2])};
		};
		return sweep(segments, sides, surface);
	}

	private static double[] knotPoint(double t) {
		double p = 2;
		double q = 3;
		double r = 0.7 + 0.3 * Math.cos(q * t);
		return new double[] {r * Math.cos(p * t), 0.35 * Math.sin(q * t), r * Math.sin(p * t)};
	}

	private static GltfModel cube() {
		float[][] faces = {
			{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		float[] positions = new float[6 * 4 * 3];
		float[] normals = new float[6 * 4 * 3];
		float[] uvs = new float[6 * 4 * 2];
		int[] indices = new int[6 * 6];
		for (int face = 0; face < 6; face++) {
			float[] n = faces[face];
			float[] u = Math.abs(n[1]) > 0 ? new float[] {1, 0, 0} : new float[] {0, 1, 0};
			float[] v = {n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]};
			float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
			for (int corner = 0; corner < 4; corner++) {
				int at = face * 4 + corner;
				for (int axis = 0; axis < 3; axis++) {
					positions[at * 3 + axis] = n[axis] + corners[corner][0] * u[axis] + corners[corner][1] * v[axis];
					normals[at * 3 + axis] = n[axis];
				}
				uvs[at * 2] = (corners[corner][0] + 1) / 2;
				uvs[at * 2 + 1] = (corners[corner][1] + 1) / 2;
			}
			int base = face * 4;
			int[] quad = {base, base + 1, base + 2, base, base + 2, base + 3};
			System.arraycopy(quad, 0, indices, face * 6, 6);
		}
		return GltfModelFactory.single(positions, normals, uvs, gradient(positions), indices);
	}

	/** Teal at the bottom to violet at the top. */
	private static float[] gradient(float[] positions) {
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int index = 1; index < positions.length; index += 3) {
			minY = Math.min(minY, positions[index]);
			maxY = Math.max(maxY, positions[index]);
		}
		float range = Math.max(1e-6f, maxY - minY);
		float[] colors = new float[positions.length / 3 * 4];
		for (int vertex = 0; vertex < positions.length / 3; vertex++) {
			float t = (positions[vertex * 3 + 1] - minY) / range;
			colors[vertex * 4] = 0.18f + 0.55f * t;
			colors[vertex * 4 + 1] = 0.82f - 0.45f * t;
			colors[vertex * 4 + 2] = 0.78f + 0.2f * t;
			colors[vertex * 4 + 3] = 1;
		}
		return colors;
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double[] normalize(double[] a) {
		double length = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
		return length > 0 ? new double[] {a[0] / length, a[1] / length, a[2] / length} : new double[] {0, 1, 0};
	}
}
