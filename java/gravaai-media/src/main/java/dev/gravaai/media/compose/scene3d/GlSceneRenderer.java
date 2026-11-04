package dev.gravaai.media.compose.scene3d;

import static org.lwjgl.opengl.GL33C.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.system.MemoryUtil;

import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.SurfaceOrigin;

import dev.gravaai.media.gl.SkiaGlSurface;

/**
 * The GPU path of {@code scene3d} (plan §5.4): draws the model into a 4x MSAA framebuffer on the
 * compositor's own GL context, resolves it into a texture and hands that texture to Skia as a
 * borrowed image, so the 3D layer composites without any {@code glReadPixels}. One instance per
 * render thread and GL context; it must only be called on that thread.
 *
 * <p>Borrowed textures are only read when Skia flushes the frame, so each call takes the next
 * texture of a ring instead of overwriting the one an earlier call this frame returned.</p>
 */
public final class GlSceneRenderer {

	private static final int RING = 48;
	private static final int MAX_MODELS = 12;
	private static final int SAMPLES = 4;

	private static final String VERTEX = """
			#version 330 core
			layout(location = 0) in vec3 aPos;
			layout(location = 1) in vec3 aNormal;
			layout(location = 2) in vec2 aUv;
			layout(location = 3) in vec4 aColor;
			uniform mat4 uModel;
			uniform mat4 uViewProj;
			uniform mat4 uNormal;
			out vec3 vNormal;
			out vec2 vUv;
			out vec4 vColor;
			void main() {
			  vNormal = mat3(uNormal) * aNormal;
			  vUv = aUv;
			  vColor = aColor;
			  gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
			}
			""";

	private static final String FRAGMENT = """
			#version 330 core
			in vec3 vNormal;
			in vec2 vUv;
			in vec4 vColor;
			uniform vec4 uBase;
			uniform vec4 uTint;
			uniform int uHasTex;
			uniform sampler2D uTex;
			uniform vec3 uLight;
			uniform vec3 uLightColor;
			uniform float uAmbient;
			uniform float uSpecular;
			out vec4 fragColor;
			void main() {
			  vec4 c = uBase * vColor * uTint;
			  if (uHasTex == 1) {
			    c *= texture(uTex, vUv);
			  }
			  vec3 n = normalize(vNormal);
			  float d = max(dot(n, uLight), 0.0);
			  vec3 h = normalize(uLight + vec3(0.0, 0.0, 1.0));
			  float s = pow(max(dot(n, h), 0.0), 32.0) * uSpecular;
			  vec3 rgb = min(c.rgb * (uAmbient + (1.0 - uAmbient) * d) * uLightColor + s * uLightColor, 1.0);
			  float a = clamp(c.a, 0.0, 1.0);
			  fragColor = vec4(rgb * a, a);
			}
			""";

	private record GpuPrimitive(int vao, int vbo, int ebo, int count, int texture, float[] base) {}

	private final DirectContext owner;
	private final int program;
	private final Map<String, Integer> uniforms = new java.util.HashMap<>();
	private final Map<GltfModel, List<GpuPrimitive>> models = new LinkedHashMap<>(16, 0.75f, true);
	private int msaaFbo;
	private int msaaColor;
	private int msaaDepth;
	private int resolveFbo;
	private final int[] ring = new int[RING];
	private int ringNext;
	private int width;
	private int height;

	/** Compiles the program on the current GL context (the compositor's render thread). */
	public GlSceneRenderer(SkiaGlSurface surface) {
		this.owner = surface.directContext();
		program = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, FRAGMENT));
		for (String name : List.of("uModel", "uViewProj", "uNormal", "uBase", "uTint", "uHasTex",
				"uTex", "uLight", "uLightColor", "uAmbient", "uSpecular")) {
			uniforms.put(name, glGetUniformLocation(program, name));
		}
		surface.resetGlState();
	}

	/** True when this renderer was created on {@code surface}'s GL context. */
	public boolean belongsTo(SkiaGlSurface surface) {
		return surface.directContext() == owner;
	}

	/** Renders {@code model} and returns a texture-backed image (valid until the frame flushes). */
	public Image render(SkiaGlSurface surface, GltfModel model, Scene3D scene, int requestedWidth,
			int requestedHeight) {
		int w = Math.max(1, Math.min(4096, requestedWidth));
		int h = Math.max(1, Math.min(4096, requestedHeight));
		try {
			ensureTargets(w, h);
			List<GpuPrimitive> gpu = upload(model);
			glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
			glViewport(0, 0, w, h);
			glDisable(GL_SCISSOR_TEST);
			glDisable(GL_STENCIL_TEST);
			glDisable(GL_BLEND);
			glDisable(GL_CULL_FACE);
			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_LESS);
			glDepthMask(true);
			glColorMask(true, true, true, true);
			glEnable(GL_MULTISAMPLE);
			glClearColor(0, 0, 0, 0);
			glClearDepth(1);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
			glUseProgram(program);
			float[] modelMatrix = scene.model(model);
			float[] viewProjection = Mat4.multiply(scene.projection(w / (double) h), scene.view());
			matrix("uModel", modelMatrix);
			matrix("uViewProj", viewProjection);
			matrix("uNormal", Mat4.normalMatrix(modelMatrix));
			int tint = scene.tint();
			glUniform4f(uniforms.get("uTint"), ((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f,
					(tint & 0xFF) / 255f, ((tint >>> 24) & 0xFF) / 255f);
			glUniform3f(uniforms.get("uLight"), (float) scene.lightX(), (float) scene.lightY(),
					(float) scene.lightZ());
			int light = scene.lightColor();
			glUniform3f(uniforms.get("uLightColor"), ((light >> 16) & 0xFF) / 255f,
					((light >> 8) & 0xFF) / 255f, (light & 0xFF) / 255f);
			glUniform1f(uniforms.get("uAmbient"), (float) scene.ambient());
			glUniform1f(uniforms.get("uSpecular"), (float) scene.specular());
			glUniform1i(uniforms.get("uTex"), 0);
			glActiveTexture(GL_TEXTURE0);
			glBindSampler(0, 0);
			for (GpuPrimitive primitive : gpu) {
				glUniform4f(uniforms.get("uBase"), primitive.base()[0], primitive.base()[1],
						primitive.base()[2], primitive.base()[3]);
				glUniform1i(uniforms.get("uHasTex"), primitive.texture() != 0 ? 1 : 0);
				glBindTexture(GL_TEXTURE_2D, primitive.texture());
				glBindVertexArray(primitive.vao());
				glDrawElements(GL_TRIANGLES, primitive.count(), GL_UNSIGNED_INT, 0);
			}
			glBindVertexArray(0);
			glBindTexture(GL_TEXTURE_2D, 0);
			glUseProgram(0);
			glDisable(GL_DEPTH_TEST);
			int texture = ring[ringNext];
			ringNext = (ringNext + 1) % RING;
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFbo);
			glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo);
			glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
			glBindFramebuffer(GL_FRAMEBUFFER, 0);
			BackendTexture backend = BackendTexture.makeGL(w, h, false,
					new GLTextureInfo(GL_TEXTURE_2D, texture, GL_RGBA8));
			Image image = Image.borrowTextureFrom(owner, backend, SurfaceOrigin.BOTTOM_LEFT,
					ColorType.RGBA_8888, ColorAlphaType.PREMUL, null, null);
			backend.close();
			if (image == null) {
				throw new IllegalStateException("Skia rejected the 3D texture");
			}
			return image;
		} finally {
			surface.resetGlState();
		}
	}

	private void ensureTargets(int w, int h) {
		if (w == width && h == height && msaaFbo != 0) {
			return;
		}
		if (msaaFbo != 0) {
			glDeleteFramebuffers(msaaFbo);
			glDeleteFramebuffers(resolveFbo);
			glDeleteRenderbuffers(msaaColor);
			glDeleteRenderbuffers(msaaDepth);
			glDeleteTextures(ring);
		}
		width = w;
		height = h;
		msaaColor = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, msaaColor);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, SAMPLES, GL_RGBA8, w, h);
		msaaDepth = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, msaaDepth);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, SAMPLES, GL_DEPTH_COMPONENT24, w, h);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
		msaaFbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaColor);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, msaaDepth);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			glBindFramebuffer(GL_FRAMEBUFFER, 0);
			throw new IllegalStateException("3D framebuffer incomplete: 0x" + Integer.toHexString(status));
		}
		glGenTextures(ring);
		for (int texture : ring) {
			glBindTexture(GL_TEXTURE_2D, texture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		glBindTexture(GL_TEXTURE_2D, 0);
		resolveFbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	private List<GpuPrimitive> upload(GltfModel model) {
		List<GpuPrimitive> cached = models.get(model);
		if (cached != null) {
			return cached;
		}
		if (models.size() >= MAX_MODELS) {
			GltfModel eldest = models.keySet().iterator().next();
			for (GpuPrimitive primitive : models.remove(eldest)) {
				delete(primitive);
			}
		}
		List<GpuPrimitive> uploaded = new ArrayList<>();
		for (GltfModel.Primitive primitive : model.primitives()) {
			int vertices = primitive.positions().length / 3;
			FloatBuffer interleaved = MemoryUtil.memAllocFloat(vertices * 12);
			IntBuffer indices = MemoryUtil.memAllocInt(primitive.indices().length);
			try {
				for (int v = 0; v < vertices; v++) {
					interleaved.put(primitive.positions(), v * 3, 3);
					interleaved.put(primitive.normals(), v * 3, 3);
					interleaved.put(primitive.uvs(), v * 2, 2);
					interleaved.put(primitive.colors(), v * 4, 4);
				}
				interleaved.flip();
				indices.put(primitive.indices()).flip();
				int vao = glGenVertexArrays();
				glBindVertexArray(vao);
				int vbo = glGenBuffers();
				glBindBuffer(GL_ARRAY_BUFFER, vbo);
				glBufferData(GL_ARRAY_BUFFER, interleaved, GL_STATIC_DRAW);
				int stride = 12 * Float.BYTES;
				glEnableVertexAttribArray(0);
				glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
				glEnableVertexAttribArray(1);
				glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
				glEnableVertexAttribArray(2);
				glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 6L * Float.BYTES);
				glEnableVertexAttribArray(3);
				glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 8L * Float.BYTES);
				int ebo = glGenBuffers();
				glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
				glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
				glBindVertexArray(0);
				glBindBuffer(GL_ARRAY_BUFFER, 0);
				int texture = primitive.texture().map(GlSceneRenderer::uploadTexture).orElse(0);
				uploaded.add(new GpuPrimitive(vao, vbo, ebo, primitive.indices().length, texture,
						primitive.baseColor().clone()));
			} finally {
				MemoryUtil.memFree(interleaved);
				MemoryUtil.memFree(indices);
			}
		}
		models.put(model, List.copyOf(uploaded));
		return models.get(model);
	}

	private static int uploadTexture(byte[] encoded) {
		try (Image image = Image.makeDeferredFromEncodedBytes(encoded); Bitmap bitmap = new Bitmap()) {
			int w = image.getWidth();
			int h = image.getHeight();
			bitmap.allocPixels(new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL));
			if (!image.readPixels(bitmap)) {
				return 0;
			}
			byte[] pixels = bitmap.readPixels();
			ByteBuffer buffer = MemoryUtil.memAlloc(pixels.length);
			try {
				buffer.put(pixels).flip();
				int texture = glGenTextures();
				glBindTexture(GL_TEXTURE_2D, texture);
				glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
				glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
				glGenerateMipmap(GL_TEXTURE_2D);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
				glBindTexture(GL_TEXTURE_2D, 0);
				return texture;
			} finally {
				MemoryUtil.memFree(buffer);
			}
		} catch (RuntimeException error) {
			return 0;
		}
	}

	private void matrix(String name, float[] values) {
		glUniformMatrix4fv(uniforms.get(name), false, values);
	}

	private static void delete(GpuPrimitive primitive) {
		glDeleteVertexArrays(primitive.vao());
		glDeleteBuffers(primitive.vbo());
		glDeleteBuffers(primitive.ebo());
		if (primitive.texture() != 0) {
			glDeleteTextures(primitive.texture());
		}
	}

	private static int compile(int type, String source) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
			String log = glGetShaderInfoLog(shader);
			glDeleteShader(shader);
			throw new IllegalStateException("3D shader compile failed: " + log);
		}
		return shader;
	}

	private static int link(int vertex, int fragment) {
		int program = glCreateProgram();
		glAttachShader(program, vertex);
		glAttachShader(program, fragment);
		glLinkProgram(program);
		glDeleteShader(vertex);
		glDeleteShader(fragment);
		if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
			String log = glGetProgramInfoLog(program);
			glDeleteProgram(program);
			throw new IllegalStateException("3D shader link failed: " + log);
		}
		return program;
	}
}
