package dev.gravaai.services.extensions.canvas;

import org.jspecify.annotations.Nullable;

import io.github.humbleui.skija.Shader;

/**
 * A non-colour {@code fillStyle}/{@code strokeStyle}: a gradient, a pattern or an SkSL shader.
 * Each call returns a fresh Skija shader the paint owns and closes; {@code null} paints nothing
 * special (the colour is used instead).
 */
public interface CanvasPaintSource {

	@Nullable Shader shader();
}
