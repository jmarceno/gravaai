package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** {@link Icon#setIconColor} must win over the theme's {@code -g-icon-color} token. */
@Timeout(60)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class IconColorTest {

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			Platform.runLater(() -> { });
		}
	}

	@Test
	@DisplayName("a code-set colour beats the button's themed icon colour (play glyph on white)")
	void setIconColorBeatsStylesheet() throws Exception {
		AtomicReference<List<Color>> fills = new AtomicReference<>();
		AtomicReference<List<Color>> themed = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
				Icon icon = Icon.of(Icons.Play, 14, "fill");
				button.setGraphic(icon);
				Scene scene = new Scene(new StackPane(button), 100, 100);
				Theme.install(scene);
				icon.setIconColor(Color.web("#0b0d10"));
				button.applyCss();
				fills.set(pathFills(icon));
				icon.setIconColor(null);
				button.applyCss();
				themed.set(pathFills(icon));
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(30, TimeUnit.SECONDS));
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
		assertFalse(fills.get().isEmpty());
		for (Color fill : fills.get()) {
			assertEquals(Color.web("#0b0d10"), fill);
		}
		// Cleared, the icon goes back to the ghost button's light themed colour.
		for (Color fill : themed.get()) {
			assertTrue(fill.getBrightness() > 0.5, "themed fill " + fill);
		}
	}

	private static List<Color> pathFills(Icon icon) {
		return icon.lookupAll("SVGPath").stream()
				.map(node -> ((SVGPath) node).getFill())
				.filter(paint -> paint instanceof Color)
				.map(paint -> (Color) paint)
				.toList();
	}
}
