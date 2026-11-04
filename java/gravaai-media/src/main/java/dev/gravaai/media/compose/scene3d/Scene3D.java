package dev.gravaai.media.compose.scene3d;

/**
 * Everything one {@code scene3d(...)} call draws: a model normalised to a unit sphere, posed by
 * Euler rotation (degrees), scale and offset, seen by a perspective camera on +Z looking at the
 * origin, lit by one directional light plus ambient. Every value is plain data computed from the
 * frame by the component, so 3D animates exactly like text.
 *
 * @param tint ARGB multiplied over the model's own colours
 * @param lightColor RGB of the key light
 */
public record Scene3D(double rotateX, double rotateY, double rotateZ, double scale,
		double offsetX, double offsetY, double offsetZ, double cameraDistance, double fov,
		double lightX, double lightY, double lightZ, int lightColor, double ambient,
		double specular, int tint) {

	public static final Scene3D DEFAULT = new Scene3D(0, 0, 0, 1, 0, 0, 0, 3, 35, -0.4, 0.7, 0.6,
			0xFFFFFF, 0.35, 0.3, 0xFFFFFFFF);

	public Scene3D {
		rotateX = finite(rotateX, 0);
		rotateY = finite(rotateY, 0);
		rotateZ = finite(rotateZ, 0);
		scale = Math.max(0, Math.min(100, finite(scale, 1)));
		offsetX = finite(offsetX, 0);
		offsetY = finite(offsetY, 0);
		offsetZ = finite(offsetZ, 0);
		cameraDistance = Math.max(0.2, Math.min(1000, finite(cameraDistance, 3)));
		fov = Math.max(5, Math.min(150, finite(fov, 35)));
		ambient = Math.max(0, Math.min(1, finite(ambient, 0.35)));
		specular = Math.max(0, Math.min(1, finite(specular, 0.3)));
		double length = Math.sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ);
		if (!(length > 1e-9) || !Double.isFinite(length)) {
			lightX = -0.4;
			lightY = 0.7;
			lightZ = 0.6;
			length = Math.sqrt(0.16 + 0.49 + 0.36);
		}
		lightX /= length;
		lightY /= length;
		lightZ /= length;
	}

	private static double finite(double value, double fallback) {
		return Double.isFinite(value) ? value : fallback;
	}

	/** Model matrix: offset * rotation * scale / radius * translate(-centre). */
	float[] model(GltfModel model) {
		float[] center = model.center();
		float[] normalize = Mat4.multiply(Mat4.scale((float) (scale / model.radius())),
				Mat4.translation(-center[0], -center[1], -center[2]));
		return Mat4.multiply(Mat4.translation((float) offsetX, (float) offsetY, (float) offsetZ),
				Mat4.multiply(Mat4.rotationXyz(rotateX, rotateY, rotateZ), normalize));
	}

	float[] view() {
		return Mat4.translation(0, 0, (float) -cameraDistance);
	}

	float[] projection(double aspect) {
		return Mat4.perspective(fov, aspect, Math.max(0.01, cameraDistance / 100),
				cameraDistance * 10 + 10);
	}

	/** Shading shared by both renderers: ambient + Lambert + Blinn specular (view along -Z). */
	static float[] shade(float r, float g, float b, float nx, float ny, float nz, Scene3D scene) {
		double diffuse = Math.max(0, nx * scene.lightX + ny * scene.lightY + nz * scene.lightZ);
		double hx = scene.lightX;
		double hy = scene.lightY;
		double hz = scene.lightZ + 1;
		double hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
		double spec = Math.pow(Math.max(0, (nx * hx + ny * hy + nz * hz) / hl), 32) * scene.specular;
		double lr = ((scene.lightColor >> 16) & 0xFF) / 255.0;
		double lg = ((scene.lightColor >> 8) & 0xFF) / 255.0;
		double lb = (scene.lightColor & 0xFF) / 255.0;
		double light = scene.ambient + (1 - scene.ambient) * diffuse;
		return new float[] {
			(float) Math.min(1, r * light * lr + spec * lr),
			(float) Math.min(1, g * light * lg + spec * lg),
			(float) Math.min(1, b * light * lb + spec * lb)};
	}
}
