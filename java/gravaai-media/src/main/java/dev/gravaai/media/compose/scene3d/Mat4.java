package dev.gravaai.media.compose.scene3d;

/** Column-major 4x4 matrices (glTF and OpenGL order) as {@code float[16]}. */
final class Mat4 {

	private Mat4() {}

	static float[] identity() {
		return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	static float[] multiply(float[] a, float[] b) {
		float[] out = new float[16];
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				float sum = 0;
				for (int k = 0; k < 4; k++) {
					sum += a[k * 4 + row] * b[column * 4 + k];
				}
				out[column * 4 + row] = sum;
			}
		}
		return out;
	}

	/** Translation, rotation quaternion (x, y, z, w), scale. */
	static float[] trs(float[] t, float[] q, float[] s) {
		float x = q[0], y = q[1], z = q[2], w = q[3];
		float[] m = {
			(1 - 2 * (y * y + z * z)) * s[0], 2 * (x * y + z * w) * s[0], 2 * (x * z - y * w) * s[0], 0,
			2 * (x * y - z * w) * s[1], (1 - 2 * (x * x + z * z)) * s[1], 2 * (y * z + x * w) * s[1], 0,
			2 * (x * z + y * w) * s[2], 2 * (y * z - x * w) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
			t[0], t[1], t[2], 1};
		return m;
	}

	static float[] translation(float x, float y, float z) {
		float[] m = identity();
		m[12] = x;
		m[13] = y;
		m[14] = z;
		return m;
	}

	static float[] scale(float s) {
		float[] m = identity();
		m[0] = s;
		m[5] = s;
		m[10] = s;
		return m;
	}

	/** Rotation from Euler angles in degrees, applied X then Y then Z. */
	static float[] rotationXyz(double xDeg, double yDeg, double zDeg) {
		return multiply(rotationZ(zDeg), multiply(rotationY(yDeg), rotationX(xDeg)));
	}

	static float[] rotationX(double degrees) {
		float c = (float) Math.cos(Math.toRadians(degrees));
		float s = (float) Math.sin(Math.toRadians(degrees));
		return new float[] {1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1};
	}

	static float[] rotationY(double degrees) {
		float c = (float) Math.cos(Math.toRadians(degrees));
		float s = (float) Math.sin(Math.toRadians(degrees));
		return new float[] {c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1};
	}

	static float[] rotationZ(double degrees) {
		float c = (float) Math.cos(Math.toRadians(degrees));
		float s = (float) Math.sin(Math.toRadians(degrees));
		return new float[] {c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	static float[] perspective(double fovDegrees, double aspect, double near, double far) {
		float f = (float) (1.0 / Math.tan(Math.toRadians(fovDegrees) / 2));
		float[] m = new float[16];
		m[0] = (float) (f / aspect);
		m[5] = f;
		m[10] = (float) ((far + near) / (near - far));
		m[11] = -1;
		m[14] = (float) (2 * far * near / (near - far));
		return m;
	}

	/** Inverse-transpose of the upper 3x3 (as a 4x4 without translation), for normals. */
	static float[] normalMatrix(float[] m) {
		float a = m[0], b = m[4], c = m[8];
		float d = m[1], e = m[5], f = m[9];
		float g = m[2], h = m[6], i = m[10];
		float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
		if (Math.abs(det) < 1e-12f) {
			return identity();
		}
		float inv = 1 / det;
		// inverse (row-major cofactors), then transposed back into column-major storage.
		float[] out = identity();
		out[0] = (e * i - f * h) * inv;
		out[1] = (c * h - b * i) * inv;
		out[2] = (b * f - c * e) * inv;
		out[4] = (f * g - d * i) * inv;
		out[5] = (a * i - c * g) * inv;
		out[6] = (c * d - a * f) * inv;
		out[8] = (d * h - e * g) * inv;
		out[9] = (b * g - a * h) * inv;
		out[10] = (a * e - b * d) * inv;
		return out;
	}

	static void transformPoints(float[] m, float[] points) {
		for (int index = 0; index + 2 < points.length; index += 3) {
			float x = points[index], y = points[index + 1], z = points[index + 2];
			points[index] = m[0] * x + m[4] * y + m[8] * z + m[12];
			points[index + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
			points[index + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
		}
	}

	static void transformNormals(float[] m, float[] normals) {
		for (int index = 0; index + 2 < normals.length; index += 3) {
			float x = normals[index], y = normals[index + 1], z = normals[index + 2];
			normals[index] = m[0] * x + m[4] * y + m[8] * z;
			normals[index + 1] = m[1] * x + m[5] * y + m[9] * z;
			normals[index + 2] = m[2] * x + m[6] * y + m[10] * z;
		}
		normalizeEach(normals);
	}

	static void normalizeEach(float[] vectors) {
		for (int index = 0; index + 2 < vectors.length; index += 3) {
			float length = (float) Math.sqrt(vectors[index] * vectors[index]
					+ vectors[index + 1] * vectors[index + 1] + vectors[index + 2] * vectors[index + 2]);
			if (length > 1e-12f) {
				vectors[index] /= length;
				vectors[index + 1] /= length;
				vectors[index + 2] /= length;
			} else {
				vectors[index + 1] = 1;
			}
		}
	}
}
