package dev.gravaai.services.extensions.canvas;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;

/** {@code ctx.createPattern(image, repetition)}: an image tiled as a fill or stroke. */
public final class CanvasPattern implements CanvasPaintSource {

	private final CanvasImage image;
	private final FilterTileMode tileX;
	private final FilterTileMode tileY;

	CanvasPattern(CanvasImage image, String repetition) {
		this.image = image;
		String value = repetition.trim().toLowerCase(Locale.ROOT);
		tileX = value.equals("repeat") || value.equals("repeat-x") || value.isEmpty()
				? FilterTileMode.REPEAT : FilterTileMode.DECAL;
		tileY = value.equals("repeat") || value.equals("repeat-y") || value.isEmpty()
				? FilterTileMode.REPEAT : FilterTileMode.DECAL;
	}

	@Override
	public @Nullable Shader shader() {
		return image.skiaImage().makeShader(tileX, tileY, SamplingMode.LINEAR, null);
	}
}
