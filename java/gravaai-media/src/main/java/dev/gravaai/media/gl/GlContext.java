package dev.gravaai.media.gl;

import static org.lwjgl.egl.EGL10.EGL_ALPHA_SIZE;
import static org.lwjgl.egl.EGL10.EGL_BLUE_SIZE;
import static org.lwjgl.egl.EGL10.EGL_GREEN_SIZE;
import static org.lwjgl.egl.EGL10.EGL_NO_CONTEXT;
import static org.lwjgl.egl.EGL10.EGL_NO_DISPLAY;
import static org.lwjgl.egl.EGL10.EGL_NO_SURFACE;
import static org.lwjgl.egl.EGL10.EGL_NONE;
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
import static org.lwjgl.opengl.GL33C.glGetString;
import static org.lwjgl.opengl.GL33C.GL_RENDERER;
import static org.lwjgl.opengl.GL33C.GL_VERSION;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGLCapabilities;
import org.lwjgl.egl.KHRNoConfigContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;

/**
 * An EGL surfaceless OpenGL 3.3 core context on the current thread (plan §5.8, spike
 * {@code GlPreviewRenderer}).
 *
 * <p>The context owns no window and no surface: it is only ever used to render into
 * framebuffer objects, which is what both the preview and the export compositor need. The
 * context is thread-affine — create it on the platform thread that will use it
 * ({@code GlPreviewThread} creates the {@code gl-preview} thread) and close it there.</p>
 *
 * <p>Gotcha from the spike (plan §3): LWJGL's argument checks reject the null native display
 * that {@code EGL_PLATFORM_SURFACELESS_MESA} requires, so that one entry point is called
 * through java.lang.foreign.</p>
 */
public final class GlContext implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(GlContext.class.getName());
	private static final int EGL_PLATFORM_SURFACELESS_MESA = 0x31DD;
	/**
	 * LWJGL exposes one GL function table per JVM ({@code GL.create} can only run once); the
	 * capabilities inside it are per context and are refreshed by {@code GL.createCapabilities}.
	 */
	private static boolean glFunctionTableCreated;

	private final long display;
	private final long context;
	private final Thread owner;
	private final String renderer;
	private final String version;
	private boolean closed;

	private GlContext(long display, long context, Thread owner, String renderer, String version) {
		this.display = display;
		this.context = context;
		this.owner = owner;
		this.renderer = renderer;
		this.version = version;
	}

	/**
	 * Creates the surfaceless context and makes it current on the calling thread.
	 *
	 * @throws IllegalStateException when EGL or OpenGL 3.3 core is unavailable (headless
	 *         machines without a working software or hardware EGL driver)
	 */
	public static GlContext create() {
		EGLCapabilities client = EGL.getCapabilities();
		if (client == null) {
			throw new IllegalStateException("EGL is not available (libEGL.so.1 missing?)");
		}
		long display = surfacelessDisplay(client);
		if (display == EGL_NO_DISPLAY) {
			throw new IllegalStateException(
					"no surfaceless EGL display (0x" + Integer.toHexString(eglGetError()) + ")");
		}
		try (MemoryStack stack = stackPush()) {
			IntBuffer major = stack.mallocInt(1);
			IntBuffer minor = stack.mallocInt(1);
			if (!eglInitialize(display, major, minor)) {
				throw new IllegalStateException(
						"eglInitialize failed (0x" + Integer.toHexString(eglGetError()) + ")");
			}
			EGL.createDisplayCapabilities(display, major.get(0), minor.get(0));
			if (!eglBindAPI(EGL_OPENGL_API)) {
				eglTerminate(display);
				throw new IllegalStateException(
						"eglBindAPI(EGL_OPENGL_API) failed (0x"
								+ Integer.toHexString(eglGetError()) + ")");
			}
			IntBuffer configAttributes = stack.ints(
					EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
					EGL_SURFACE_TYPE, 0, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE);
			PointerBuffer configs = stack.mallocPointer(1);
			IntBuffer configCount = stack.mallocInt(1);
			long config = eglChooseConfig(display, configAttributes, configs, configCount)
					&& configCount.get(0) > 0
							? configs.get(0)
							: KHRNoConfigContext.EGL_NO_CONFIG_KHR;
			IntBuffer contextAttributes = stack.ints(
					EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
					EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
					EGL_NONE);
			long context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
			if (context == EGL_NO_CONTEXT) {
				eglTerminate(display);
				throw new IllegalStateException(
						"eglCreateContext(3.3 core) failed (0x"
								+ Integer.toHexString(eglGetError()) + ")");
			}
			if (!eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) {
				eglDestroyContext(display, context);
				eglTerminate(display);
				throw new IllegalStateException(
						"eglMakeCurrent failed (0x" + Integer.toHexString(eglGetError()) + ")");
			}
			// LWJGL must not load its own GL library: EGL's swrast/radeonsi implementation is
			// already current and is the only correct function table for this display.
			ensureGlFunctionTable();
			GL.createCapabilities();
			String renderer = glGetString(GL_RENDERER);
			String version = glGetString(GL_VERSION);
			LOG.log(System.Logger.Level.DEBUG,
					() -> "GL context ready: " + renderer + " / " + version);
			return new GlContext(display, context, Thread.currentThread(),
					renderer == null ? "unknown" : renderer,
					version == null ? "unknown" : version);
		}
	}

	private static synchronized void ensureGlFunctionTable() {
		if (glFunctionTableCreated) {
			return;
		}
		Configuration.OPENGL_EXPLICIT_INIT.set(true);
		GL.create(EGL.getFunctionProvider());
		glFunctionTableCreated = true;
	}

	private static long surfacelessDisplay(EGLCapabilities client) {
		long getPlatformDisplay = client.eglGetPlatformDisplay != 0
				? client.eglGetPlatformDisplay
				: client.eglGetPlatformDisplayEXT;
		if (getPlatformDisplay != 0) {
			try {
				MethodHandle call = Linker.nativeLinker().downcallHandle(
						MemorySegment.ofAddress(getPlatformDisplay),
						FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
								ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
				return (long) call.invokeExact(EGL_PLATFORM_SURFACELESS_MESA, 0L, 0L);
			} catch (Throwable failure) {
				throw new IllegalStateException("eglGetPlatformDisplay failed", failure);
			}
		}
		// No platform-base extension: the default display plus EGL_KHR_surfaceless_context still
		// needs no window.
		LOG.log(System.Logger.Level.DEBUG,
				"EGL_platform_base missing, falling back to the default display");
		return eglGetDisplay(0L);
	}

	/** The GL renderer string, for logs and diagnostics. */
	public String renderer() {
		return renderer;
	}

	/** The GL version string, for logs and diagnostics. */
	public String version() {
		return version;
	}

	/** {@code renderer / version}, used in log lines. */
	public String description() {
		return renderer + " / " + version;
	}

	/** Re-binds the context to the owning thread (for example after a raw GL helper). */
	public void makeCurrent() {
		ensureOwner();
		if (!eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) {
			throw new IllegalStateException(
					"eglMakeCurrent failed (0x" + Integer.toHexString(eglGetError()) + ")");
		}
	}

	/** {@code true} when the calling thread is the thread that created the context. */
	public boolean isOwnerThread() {
		return Thread.currentThread() == owner;
	}

	private void ensureOwner() {
		if (!isOwnerThread()) {
			throw new IllegalStateException("GL context is owned by thread " + owner.getName()
					+ ", used from " + Thread.currentThread().getName());
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		ensureOwner();
		closed = true;
		try {
			eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
			eglDestroyContext(display, context);
		} finally {
			eglTerminate(display);
		}
		LOG.log(System.Logger.Level.DEBUG, "GL context closed");
	}
}
