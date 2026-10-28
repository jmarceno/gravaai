package dev.gravaai.media.gl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL32C.GL_CONTEXT_CORE_PROFILE_BIT;
import static org.lwjgl.opengl.GL32C.GL_CONTEXT_PROFILE_MASK;
import static org.lwjgl.opengl.GL32C.glGetInteger;
import static org.lwjgl.opengl.GL33C.GL_MAJOR_VERSION;
import static org.lwjgl.opengl.GL33C.GL_MINOR_VERSION;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The surfaceless EGL/GL context (plan §5.8, spike gotcha 1). */
class GlContextTest {

	@Test
	@Timeout(60)
	@DisplayName("creates a surfaceless OpenGL 3.3 core context")
	void createsContext() {
		GlContext context = createOrSkip();
		try {
			assertTrue(context.isOwnerThread());
			assertFalse(context.renderer().isBlank(), "empty GL_RENDERER");
			int major = glGetInteger(GL_MAJOR_VERSION);
			int minor = glGetInteger(GL_MINOR_VERSION);
			assertTrue(major > 3 || (major == 3 && minor >= 3),
					"context is below GL 3.3: " + context.version());
			assertTrue((glGetInteger(GL_CONTEXT_PROFILE_MASK) & GL_CONTEXT_CORE_PROFILE_BIT) != 0,
					"context is not a core profile: " + context.version());
			context.makeCurrent();
			assertTrue(context.description().contains("/"));
		} finally {
			context.close();
			context.close();
		}
	}

	@Test
	@Timeout(60)
	@DisplayName("the context is thread-affine")
	void contextIsThreadAffine() throws Exception {
		GlContext context = createOrSkip();
		try {
			AtomicBoolean owner = new AtomicBoolean(true);
			AtomicReference<Throwable> wrongThreadFailure = new AtomicReference<>();
			Thread other = Thread.ofPlatform().start(() -> {
				owner.set(context.isOwnerThread());
				try {
					context.close();
				} catch (Throwable failure) {
					wrongThreadFailure.set(failure);
				}
			});
			other.join(10_000);
			assertFalse(owner.get(), "context claimed to be owned by the other thread");
			assertTrue(wrongThreadFailure.get() instanceof IllegalStateException,
					"closing from a foreign thread must fail: " + wrongThreadFailure.get());
		} finally {
			context.close();
		}
	}

	private static GlContext createOrSkip() {
		try {
			return GlContext.create();
		} catch (IllegalStateException | UnsatisfiedLinkError unavailable) {
			Assumptions.abort("GL unavailable on this machine: " + unavailable.getMessage());
			throw unavailable;
		}
	}
}
