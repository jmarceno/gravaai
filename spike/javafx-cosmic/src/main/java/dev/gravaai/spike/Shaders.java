package dev.gravaai.spike;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Stand-in for the editor compositor: wallpaper, padded rounded frame with shadow, a procedural
 * "recording" with a moving cursor, zoom on the cursor and a radial zoom blur while zooming.
 * Rows come out top-first after glReadPixels, so uv.y grows downward with gl_FragCoord.y.
 */
final class Shaders {
	private Shaders() {}

	static final String VERTEX = """
			#version 330 core
			void main() {
				vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
				gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	static final String FRAGMENT = """
			#version 330 core
			out vec4 fragColor;
			uniform vec2 uRes;
			uniform float uTime;
			uniform float uZoom;
			uniform vec2 uFocus;
			uniform float uPad;
			uniform float uBlur;

			float box(vec2 p, vec2 halfSize, float r) {
				vec2 q = abs(p) - halfSize + r;
				return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
			}

			vec3 window(vec3 c, vec2 uv, vec2 origin, vec2 size, vec3 accent) {
				vec2 px = uv * uRes;
				vec2 o = origin * uRes;
				vec2 s = size * uRes;
				float d = box(px - (o + s * 0.5), s * 0.5, 10.0);
				if (d > 0.0) {
					return mix(c, vec3(0.0), 0.35 * exp(-d / 18.0));
				}
				vec2 local = (px - o) / s;
				vec3 body = vec3(0.13, 0.14, 0.17);
				if (local.y < 0.07) {
					body = vec3(0.18, 0.19, 0.23);
				} else {
					float row = fract(local.y * 22.0);
					float len = 0.35 + 0.5 * fract(sin(floor(local.y * 22.0) * 12.9898) * 43758.5453);
					if (row > 0.35 && row < 0.65 && local.x > 0.06 && local.x < len) {
						body = mix(body, accent, 0.55);
					}
				}
				return body;
			}

			vec3 recording(vec2 uv) {
				vec3 c = vec3(0.06, 0.07, 0.09) + 0.03 * uv.y;
				c = window(c, uv, vec2(0.06, 0.08), vec2(0.52, 0.62), vec3(0.18, 0.70, 0.64));
				c = window(c, uv, vec2(0.48 + 0.02 * sin(uTime * 0.3), 0.30), vec2(0.44, 0.58), vec3(0.90, 0.40, 0.43));
				vec2 cursor = vec2(0.5 + 0.3 * sin(uTime * 0.7), 0.5 + 0.25 * sin(uTime * 1.1));
				vec2 d = (uv - cursor) * uRes / uRes.y;
				float ring = smoothstep(0.016, 0.012, length(d)) - smoothstep(0.010, 0.007, length(d));
				c = mix(c, vec3(1.0), smoothstep(0.010, 0.007, length(d)));
				c = mix(c, vec3(0.0), ring * 0.8);
				return c;
			}

			vec3 zoomed(vec2 uv, float zoom) {
				return recording(uFocus + (uv - uFocus) / zoom);
			}

			void main() {
				vec2 uv = gl_FragCoord.xy / uRes;
				vec3 wall = mix(vec3(0.01, 0.30, 0.40), vec3(0.18, 0.70, 0.64), uv.x * 0.6 + uv.y * 0.4);

				vec2 halfSize = vec2(0.5 - uPad) * uRes;
				vec2 p = (uv - 0.5) * uRes;
				float d = box(p, halfSize, 18.0);
				if (d > 0.0) {
					float shadow = 0.45 * exp(-d / 40.0);
					fragColor = vec4(mix(wall, vec3(0.0), shadow), 1.0);
					return;
				}

				vec2 local = (p + halfSize) / (2.0 * halfSize);
				vec3 c;
				if (uBlur > 0.001) {
					c = vec3(0.0);
					for (int i = 0; i < 12; i++) {
						c += zoomed(local, uZoom * (1.0 - uBlur * float(i) / 12.0));
					}
					c /= 12.0;
				} else {
					c = zoomed(local, uZoom);
				}
				fragColor = vec4(c, 1.0);
			}
			""";

	static int link(String vertexSource, String fragmentSource) {
		int vertex = compile(GL_VERTEX_SHADER, vertexSource);
		int fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);
		int program = glCreateProgram();
		glAttachShader(program, vertex);
		glAttachShader(program, fragment);
		glLinkProgram(program);
		if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
			throw new IllegalStateException("link failed: " + glGetProgramInfoLog(program));
		}
		glDeleteShader(vertex);
		glDeleteShader(fragment);
		return program;
	}

	private static int compile(int type, String source) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
			throw new IllegalStateException("compile failed: " + glGetShaderInfoLog(shader));
		}
		return shader;
	}
}
