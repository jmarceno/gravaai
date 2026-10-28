package dev.gravaai.media.gl;

import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL33C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL33C.GL_NEAREST;
import static org.lwjgl.opengl.GL33C.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL33C.GL_R8;
import static org.lwjgl.opengl.GL33C.GL_RED;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL33C.glActiveTexture;
import static org.lwjgl.opengl.GL33C.glAttachShader;
import static org.lwjgl.opengl.GL33C.glBindFramebuffer;
import static org.lwjgl.opengl.GL33C.glBindTexture;
import static org.lwjgl.opengl.GL33C.glBindVertexArray;
import static org.lwjgl.opengl.GL33C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL33C.glCompileShader;
import static org.lwjgl.opengl.GL33C.glCreateProgram;
import static org.lwjgl.opengl.GL33C.glCreateShader;
import static org.lwjgl.opengl.GL33C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL33C.glDeleteProgram;
import static org.lwjgl.opengl.GL33C.glDeleteShader;
import static org.lwjgl.opengl.GL33C.glDeleteTextures;
import static org.lwjgl.opengl.GL33C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL33C.glDisable;
import static org.lwjgl.opengl.GL33C.glDrawArrays;
import static org.lwjgl.opengl.GL33C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL33C.glGenFramebuffers;
import static org.lwjgl.opengl.GL33C.glGenTextures;
import static org.lwjgl.opengl.GL33C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL33C.glGetProgrami;
import static org.lwjgl.opengl.GL33C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL33C.glGetShaderi;
import static org.lwjgl.opengl.GL33C.glGetUniformLocation;
import static org.lwjgl.opengl.GL33C.glLinkProgram;
import static org.lwjgl.opengl.GL33C.glPixelStorei;
import static org.lwjgl.opengl.GL33C.glReadPixels;
import static org.lwjgl.opengl.GL33C.glShaderSource;
import static org.lwjgl.opengl.GL33C.glTexImage2D;
import static org.lwjgl.opengl.GL33C.glTexParameteri;
import static org.lwjgl.opengl.GL33C.glUniform1i;
import static org.lwjgl.opengl.GL33C.glUseProgram;
import static org.lwjgl.opengl.GL33C.glViewport;
import static org.lwjgl.opengl.GL33C.GL_BLEND;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL33C.GL_STENCIL_TEST;
import static org.lwjgl.system.MemoryUtil.memAddress;

import java.nio.ByteBuffer;

/**
 * Converts the compositor's RGBA framebuffer to planar YUV 4:2:0 (I420, BT.709 limited range) on
 * the GPU, so the export pipes 1.5 bytes per pixel to ffmpeg instead of 4 and ffmpeg skips the
 * software swscale conversion.
 *
 * <p>One fragment pass writes an {@code R8} target of {@code width x height*3/2}: the first
 * {@code height} rows are the Y plane, then the U plane and the V plane, each {@code width/2 x
 * height/2} packed two chroma rows per target row. Read back row by row from the top, the bytes
 * are exactly ffmpeg's {@code -pix_fmt yuv420p} frame. Chroma is the average of each 2x2 block
 * (centre-sited, like swscale's default).</p>
 *
 * <p>Requires an even width and a height divisible by four (see {@link #supports}). Must be
 * created, used and closed on the GL thread; call {@link SkiaGlSurface#resetGlState()} before
 * Skia draws again.</p>
 */
public final class GlYuv420Converter implements AutoCloseable {

	private static final String VERTEX = """
			#version 330 core
			const vec2 corners[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
			void main() {
				gl_Position = vec4(corners[gl_VertexID], 0.0, 1.0);
			}
			""";

	// Texel row 0 of the source is the top of the image (Skia TOP_LEFT origin) and target row 0
	// is the first row glReadPixels returns, so both index from the top.
	private static final String FRAGMENT = """
			#version 330 core
			uniform sampler2D source;
			uniform int frameWidth;
			uniform int frameHeight;
			out vec4 result;

			vec3 rgbAt(ivec2 point) {
				return texelFetch(source, point, 0).rgb;
			}

			void main() {
				int x = int(gl_FragCoord.x);
				int y = int(gl_FragCoord.y);
				if (y < frameHeight) {
					float luma = dot(rgbAt(ivec2(x, y)), vec3(0.2126, 0.7152, 0.0722));
					result = vec4((16.0 + 219.0 * luma) / 255.0);
					return;
				}
				int chromaWidth = frameWidth / 2;
				int planeRow = y - frameHeight;
				bool isV = planeRow >= frameHeight / 4;
				if (isV) {
					planeRow -= frameHeight / 4;
				}
				int index = planeRow * frameWidth + x;
				int chromaY = index / chromaWidth;
				int chromaX = index - chromaY * chromaWidth;
				ivec2 point = ivec2(chromaX * 2, chromaY * 2);
				vec3 colour = (rgbAt(point) + rgbAt(point + ivec2(1, 0)) + rgbAt(point + ivec2(0, 1))
						+ rgbAt(point + ivec2(1, 1))) * 0.25;
				float chroma = isV
						? dot(colour, vec3(0.5, -0.4542, -0.0458))
						: dot(colour, vec3(-0.1146, -0.3854, 0.5));
				result = vec4((128.0 + 224.0 * chroma) / 255.0);
			}
			""";

	private final int width;
	private final int height;
	private final int targetHeight;
	private int program;
	private int vertexArray;
	private int targetTexture;
	private int targetFramebuffer;
	private final int sourceLocation;
	private final int widthLocation;
	private final int heightLocation;

	/** Whether a frame of this size can be converted (even width, height divisible by 4). */
	public static boolean supports(int width, int height) {
		return width > 1 && height > 3 && width % 2 == 0 && height % 4 == 0;
	}

	/** Bytes of one I420 frame. */
	public static int frameBytes(int width, int height) {
		return width * height * 3 / 2;
	}

	public GlYuv420Converter(int width, int height) {
		if (!supports(width, height)) {
			throw new IllegalArgumentException("YUV 4:2:0 conversion needs an even width and a height"
					+ " divisible by 4: " + width + "x" + height);
		}
		this.width = width;
		this.height = height;
		this.targetHeight = height * 3 / 2;
		program = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, FRAGMENT));
		sourceLocation = glGetUniformLocation(program, "source");
		widthLocation = glGetUniformLocation(program, "frameWidth");
		heightLocation = glGetUniformLocation(program, "frameHeight");
		vertexArray = glGenVertexArrays();

		targetTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, targetTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width, targetHeight, 0, GL_RED, GL_UNSIGNED_BYTE,
				(ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		targetFramebuffer = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, targetFramebuffer);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			close();
			throw new IllegalStateException("YUV framebuffer incomplete: 0x"
					+ Integer.toHexString(status));
		}
	}

	/** Bytes {@link #convert} writes. */
	public int frameBytes() {
		return frameBytes(width, height);
	}

	/**
	 * Converts {@code sourceTexture} (an RGBA texture of this converter's size) and reads the I420
	 * frame into {@code destination} (a direct buffer of at least {@link #frameBytes()} bytes).
	 */
	public void convert(int sourceTexture, ByteBuffer destination) {
		if (destination == null || !destination.isDirect() || destination.capacity() < frameBytes()) {
			throw new IllegalArgumentException("YUV destination must be a direct buffer of "
					+ frameBytes() + " bytes");
		}
		glBindFramebuffer(GL_FRAMEBUFFER, targetFramebuffer);
		glViewport(0, 0, width, targetHeight);
		glDisable(GL_BLEND);
		glDisable(GL_SCISSOR_TEST);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_STENCIL_TEST);
		glUseProgram(program);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, sourceTexture);
		glUniform1i(sourceLocation, 0);
		glUniform1i(widthLocation, width);
		glUniform1i(heightLocation, height);
		glBindVertexArray(vertexArray);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindVertexArray(0);
		glPixelStorei(GL_PACK_ALIGNMENT, 1);
		glReadPixels(0, 0, width, targetHeight, GL_RED, GL_UNSIGNED_BYTE, memAddress(destination));
		glPixelStorei(GL_PACK_ALIGNMENT, 4);
		glBindTexture(GL_TEXTURE_2D, 0);
		glUseProgram(0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	private static int compile(int type, String source) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
			String log = glGetShaderInfoLog(shader);
			glDeleteShader(shader);
			throw new IllegalStateException("YUV shader compile failed: " + log);
		}
		return shader;
	}

	private static int link(int vertex, int fragment) {
		int linked = glCreateProgram();
		glAttachShader(linked, vertex);
		glAttachShader(linked, fragment);
		glLinkProgram(linked);
		glDeleteShader(vertex);
		glDeleteShader(fragment);
		if (glGetProgrami(linked, GL_LINK_STATUS) == 0) {
			String log = glGetProgramInfoLog(linked);
			glDeleteProgram(linked);
			throw new IllegalStateException("YUV program link failed: " + log);
		}
		return linked;
	}

	@Override
	public void close() {
		if (targetFramebuffer != 0) {
			glDeleteFramebuffers(targetFramebuffer);
			targetFramebuffer = 0;
		}
		if (targetTexture != 0) {
			glDeleteTextures(targetTexture);
			targetTexture = 0;
		}
		if (vertexArray != 0) {
			glDeleteVertexArrays(vertexArray);
			vertexArray = 0;
		}
		if (program != 0) {
			glDeleteProgram(program);
			program = 0;
		}
	}
}
