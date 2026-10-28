package dev.gravaai.spike;

import static org.lwjgl.egl.EGL10.EGL_ALPHA_SIZE;
import static org.lwjgl.egl.EGL10.EGL_BLUE_SIZE;
import static org.lwjgl.egl.EGL10.EGL_GREEN_SIZE;
import static org.lwjgl.egl.EGL10.EGL_NONE;
import static org.lwjgl.egl.EGL10.EGL_NO_CONTEXT;
import static org.lwjgl.egl.EGL10.EGL_NO_DISPLAY;
import static org.lwjgl.egl.EGL10.EGL_NO_SURFACE;
import static org.lwjgl.egl.EGL10.EGL_RED_SIZE;
import static org.lwjgl.egl.EGL10.EGL_SURFACE_TYPE;
import static org.lwjgl.egl.EGL10.eglChooseConfig;
import static org.lwjgl.egl.EGL10.eglCreateContext;
import static org.lwjgl.egl.EGL10.eglDestroyContext;
import static org.lwjgl.egl.EGL10.eglGetDisplay;
import static org.lwjgl.egl.EGL10.eglGetError;
import static org.lwjgl.egl.EGL10.eglInitialize;
import static org.lwjgl.egl.EGL10.eglMakeCurrent;
import static org.lwjgl.egl.EGL10.eglTerminate;
import static org.lwjgl.egl.EGL12.eglBindAPI;
import static org.lwjgl.egl.EGL14.EGL_OPENGL_API;
import static org.lwjgl.egl.EGL14.EGL_OPENGL_BIT;
import static org.lwjgl.egl.EGL14.EGL_RENDERABLE_TYPE;
import static org.lwjgl.egl.EGL15.EGL_CONTEXT_MAJOR_VERSION;
import static org.lwjgl.egl.EGL15.EGL_CONTEXT_MINOR_VERSION;
import static org.lwjgl.egl.EGL15.EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT;
import static org.lwjgl.egl.EGL15.EGL_CONTEXT_OPENGL_PROFILE_MASK;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memCopy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.lwjgl.egl.EGLCapabilities;
import org.lwjgl.egl.KHRNoConfigContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;

/**
 * Renders the preview on its own thread into an offscreen OpenGL framebuffer (EGL, no window),
 * reads it back through two pixel-pack buffers and hands it to JavaFX through two PixelBuffers.
 *
 * <p>Hand-off: the render thread only writes the PixelBuffer that is not on screen, and it waits
 * on {@code presented} until JavaFX has swapped the previous one in.
 */
final class GlPreviewRenderer {
	private static final int EGL_PLATFORM_SURFACELESS_MESA = 0x31DD;
	private static final long FRAME_NANOS = 1_000_000_000L / 60;

	private final int width;
	private final int height;
	private final PixelBuffer<IntBuffer>[] buffers;
	private final WritableImage[] images;
	private final Semaphore presented = new Semaphore(1);
	private final CountDownLatch attached = new CountDownLatch(1);
	private volatile ImageView view;
	private Thread thread;
	private volatile boolean running;

	// Controls, written by the editor on the FX thread.
	volatile float manualZoom = 1.0f;
	volatile float padding = 0.06f;
	volatile boolean autoZoom = true;
	volatile boolean motionBlur = true;

	// Stats, read by the editor.
	final AtomicLong framesPresented = new AtomicLong();
	volatile double renderMillis;
	volatile double readbackMillis;
	volatile double timeSeconds;
	volatile String glRenderer = "starting";
	volatile String failure;

	private long eglDisplay;
	private long eglContext;
	private int program;
	private int vao;
	private int fbo;
	private int colorTexture;
	private final int[] pbos = new int[2];
	private int uRes;
	private int uTime;
	private int uZoom;
	private int uFocus;
	private int uPad;
	private int uBlur;

	@SuppressWarnings("unchecked")
	GlPreviewRenderer(int width, int height) {
		this.width = width;
		this.height = height;
		buffers = new PixelBuffer[2];
		images = new WritableImage[2];
	}

	/** FX thread. The GL thread may already be initialising; it waits here before its first frame. */
	void attach(ImageView view) {
		for (int i = 0; i < 2; i++) {
			IntBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
			buffers[i] = new PixelBuffer<>(width, height, pixels, PixelFormat.getIntArgbPreInstance());
			images[i] = new WritableImage(buffers[i]);
		}
		this.view = view;
		attached.countDown();
	}

	void start() {
		running = true;
		thread = Thread.ofPlatform().name("gl-preview").daemon().start(this::run);
	}

	void stop() {
		running = false;
		presented.release();
		attached.countDown();
		if (thread != null) {
			try {
				thread.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void run() {
		try {
			initEgl();
			initGl();
			Log.info("startup: GL ready at " + Log.sinceProcessStart() + " ms");
			attached.await();
			loop();
		} catch (Throwable t) {
			failure = t.toString();
			Log.error("gl-preview failed", t);
		} finally {
			if (eglDisplay != 0) {
				eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
				if (eglContext != 0) {
					eglDestroyContext(eglDisplay, eglContext);
				}
				eglTerminate(eglDisplay);
			}
		}
	}

	private void initEgl() {
		EGLCapabilities client = EGL.getCapabilities();
		Log.info("EGL client caps: EGL15=" + client.EGL15 + " EXT_platform_base=" + client.EGL_EXT_platform_base
				+ " getPlatformDisplay=0x" + Long.toHexString(client.eglGetPlatformDisplay)
				+ " getPlatformDisplayEXT=0x" + Long.toHexString(client.eglGetPlatformDisplayEXT));
		// LWJGL's argument checks reject the null native display that the surfaceless platform requires,
		// so this one entry point is called through FFM.
		long getPlatformDisplay = client.eglGetPlatformDisplay != 0 ? client.eglGetPlatformDisplay : client.eglGetPlatformDisplayEXT;
		if (getPlatformDisplay != 0) {
			try {
				MethodHandle call = Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(getPlatformDisplay),
						FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
				eglDisplay = (long) call.invokeExact(EGL_PLATFORM_SURFACELESS_MESA, 0L, 0L);
			} catch (Throwable t) {
				throw new IllegalStateException("eglGetPlatformDisplay", t);
			}
		} else {
			// Default display plus a surfaceless context (EGL_KHR_surfaceless_context) needs no window either.
			eglDisplay = eglGetDisplay(0L);
			Log.info("EGL falling back to the default display");
		}
		if (eglDisplay == EGL_NO_DISPLAY) {
			throw new IllegalStateException("no surfaceless EGL display (0x" + Integer.toHexString(eglGetError()) + ")");
		}
		try (MemoryStack stack = stackPush()) {
			IntBuffer major = stack.mallocInt(1);
			IntBuffer minor = stack.mallocInt(1);
			if (!eglInitialize(eglDisplay, major, minor)) {
				throw new IllegalStateException("eglInitialize 0x" + Integer.toHexString(eglGetError()));
			}
			EGL.createDisplayCapabilities(eglDisplay, major.get(0), minor.get(0));
			Log.info("EGL " + major.get(0) + "." + minor.get(0) + " surfaceless display");
			if (!eglBindAPI(EGL_OPENGL_API)) {
				throw new IllegalStateException("eglBindAPI(OpenGL) 0x" + Integer.toHexString(eglGetError()));
			}

			// A surfaceless context needs no surface-capable config; fall back to "no config" if none match.
			IntBuffer configAttribs = stack.ints(
					EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
					EGL_SURFACE_TYPE, 0, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE);
			PointerBuffer configs = stack.mallocPointer(1);
			IntBuffer count = stack.mallocInt(1);
			long config = eglChooseConfig(eglDisplay, configAttribs, configs, count) && count.get(0) > 0
					? configs.get(0)
					: KHRNoConfigContext.EGL_NO_CONFIG_KHR;

			IntBuffer contextAttribs = stack.ints(
					EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
					EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE);
			eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
			if (eglContext == EGL_NO_CONTEXT) {
				throw new IllegalStateException("eglCreateContext 0x" + Integer.toHexString(eglGetError()));
			}
			if (!eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, eglContext)) {
				throw new IllegalStateException("eglMakeCurrent 0x" + Integer.toHexString(eglGetError()));
			}
		}
		Configuration.OPENGL_EXPLICIT_INIT.set(true);
		GL.create(EGL.getFunctionProvider());
		GL.createCapabilities();
		glRenderer = glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION);
		Log.info("GL " + glRenderer);
	}

	private void initGl() {
		program = Shaders.link(Shaders.VERTEX, Shaders.FRAGMENT);
		uRes = glGetUniformLocation(program, "uRes");
		uTime = glGetUniformLocation(program, "uTime");
		uZoom = glGetUniformLocation(program, "uZoom");
		uFocus = glGetUniformLocation(program, "uFocus");
		uPad = glGetUniformLocation(program, "uPad");
		uBlur = glGetUniformLocation(program, "uBlur");
		vao = glGenVertexArrays();

		colorTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, colorTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("framebuffer incomplete 0x" + Integer.toHexString(status));
		}

		for (int i = 0; i < 2; i++) {
			pbos[i] = glGenBuffers();
			glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[i]);
			glBufferData(GL_PIXEL_PACK_BUFFER, (long) width * height * 4, GL_STREAM_READ);
		}
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		glPixelStorei(GL_PACK_ALIGNMENT, 4);
	}

	private void loop() throws InterruptedException {
		ZoomAnimator zoom = new ZoomAnimator();
		long origin = System.nanoTime();
		long next = origin;
		int target = 0;
		long frame = 0;
		while (running) {
			long start = System.nanoTime();
			float t = (start - origin) / 1e9f;
			timeSeconds = t;
			zoom.update(t, autoZoom, manualZoom, motionBlur);

			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
			glViewport(0, 0, width, height);
			glUseProgram(program);
			glUniform2f(uRes, width, height);
			glUniform1f(uTime, t);
			glUniform1f(uZoom, zoom.zoom);
			glUniform2f(uFocus, zoom.focusX, zoom.focusY);
			glUniform1f(uPad, padding);
			glUniform1f(uBlur, zoom.blur);
			glBindVertexArray(vao);
			glDrawArrays(GL_TRIANGLES, 0, 3);

			// Start this frame's readback, then copy out the previous frame's (one frame of latency).
			glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[(int) (frame & 1)]);
			glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_BYTE, 0L);
			long renderDone = System.nanoTime();

			if (frame > 0) {
				glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[(int) ((frame + 1) & 1)]);
				ByteBuffer mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (long) width * height * 4, GL_MAP_READ_BIT);
				if (mapped != null && presented.tryAcquire(250, TimeUnit.MILLISECONDS) && running) {
					PixelBuffer<IntBuffer> buffer = buffers[target];
					memCopy(memAddress(mapped), memAddress(buffer.getBuffer()), (long) width * height * 4);
					int shown = target;
					Platform.runLater(() -> present(shown));
					target ^= 1;
				}
				glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
			}
			glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
			long end = System.nanoTime();
			renderMillis = (renderDone - start) / 1e6;
			readbackMillis = (end - renderDone) / 1e6;
			frame++;

			next += FRAME_NANOS;
			long wait = next - System.nanoTime();
			if (wait > 0) {
				LockSupport.parkNanos(wait);
			} else {
				next = System.nanoTime();
			}
		}
	}

	/** FX thread: mark the whole buffer dirty, put it on screen, and let the render thread reuse the other one. */
	private void present(int index) {
		buffers[index].updateBuffer(b -> null);
		if (view != null && view.getImage() != images[index]) {
			view.setImage(images[index]);
		}
		if (framesPresented.incrementAndGet() == 1) {
			Log.info("startup: first preview frame at " + Log.sinceProcessStart() + " ms");
		}
		presented.release();
	}

	/** Mimics the editor's auto-zoom: ease in on the cursor, hold, ease out; blur follows zoom speed. */
	private static final class ZoomAnimator {
		float zoom = 1f;
		float focusX = 0.5f;
		float focusY = 0.5f;
		float blur;
		private float lastZoom = 1f;
		private float lastTime;

		void update(float t, boolean auto, float manual, boolean motionBlur) {
			float target;
			if (auto) {
				float phase = t % 6f;
				float in = smooth(clamp(phase / 0.8f));
				float out = smooth(clamp((phase - 3.2f) / 0.8f));
				target = 1f + (manual > 1.01f ? manual - 1f : 1.2f) * (in - out);
			} else {
				target = manual;
			}
			zoom = target;
			// Same cursor path as the shader.
			focusX = 0.5f + 0.3f * (float) Math.sin(t * 0.7);
			focusY = 0.5f + 0.25f * (float) Math.sin(t * 1.1);
			float dt = Math.max(t - lastTime, 1e-3f);
			float speed = Math.abs(zoom - lastZoom) / dt;
			blur = motionBlur ? Math.min(speed * 0.08f, 0.25f) : 0f;
			lastZoom = zoom;
			lastTime = t;
		}

		private static float clamp(float v) {
			return Math.max(0f, Math.min(1f, v));
		}

		private static float smooth(float v) {
			return v * v * (3f - 2f * v);
		}
	}
}
