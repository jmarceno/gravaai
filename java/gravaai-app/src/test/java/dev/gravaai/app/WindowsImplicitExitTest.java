package dev.gravaai.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javafx.application.Platform;

/**
 * The app lives in the tray. Before the fix {@code Windows} only turned implicit exit off when it
 * was loaded off the FX thread; it is loaded from {@code Application.start} (the FX thread), so
 * the first time no window was showing (the countdown closing while the HUD is hidden) JavaFX
 * quit the app before the recording started.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class WindowsImplicitExitTest {

	@Test
	@DisplayName("Windows loaded on the FX thread turns JavaFX implicit exit off")
	void windowsLoadedOnTheFxThreadDisablesImplicitExit() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			// another test in this JVM started the toolkit
		}
		boolean before = Platform.isImplicitExit();
		Platform.setImplicitExit(true);
		try {
			CountDownLatch done = new CountDownLatch(1);
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Platform.runLater(() -> {
				try {
					// A fresh copy of Windows, so its static initialiser runs here on the FX thread
					// even when another test already loaded the shared one.
					Class.forName(Windows.class.getName(), true, new FreshWindowsLoader());
				} catch (Throwable error) {
					failure.set(error);
				} finally {
					done.countDown();
				}
			});
			assertTrue(done.await(30, TimeUnit.SECONDS), "the FX action did not run");
			assertNull(failure.get(), () -> "loading Windows failed: " + failure.get());
			assertFalse(Platform.isImplicitExit(), "closing the last window must not quit the app");
		} finally {
			Platform.setImplicitExit(before);
		}
	}

	/** Defines {@code Windows} itself and delegates every other class to the test class loader. */
	private static final class FreshWindowsLoader extends ClassLoader {

		FreshWindowsLoader() {
			super(WindowsImplicitExitTest.class.getClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.equals(Windows.class.getName())) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					String resource = name.replace('.', '/') + ".class";
					try (InputStream in = getParent().getResourceAsStream(resource)) {
						if (in == null) {
							throw new ClassNotFoundException(name);
						}
						byte[] bytes = in.readAllBytes();
						loaded = defineClass(name, bytes, 0, bytes.length);
					} catch (java.io.IOException error) {
						throw new ClassNotFoundException(name, error);
					}
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}
	}
}
