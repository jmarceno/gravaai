package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stylesheet is the single source of the visual tokens (plan §5.12). This test fails when a
 * token or a component class from the README mapping table disappears.
 */
class ThemeCssTest {

	private static final List<String> TOKENS = List.of(
			"-g-bg", "-g-fg", "-g-surface", "-g-surface-secondary", "-g-surface-tertiary",
			"-g-surface-hover", "-g-overlay", "-g-default", "-g-default-hover", "-g-segment",
			"-g-field", "-g-field-hover", "-g-field-placeholder", "-g-border", "-g-separator",
			"-g-separator-secondary", "-g-separator-tertiary", "-g-border-secondary",
			"-g-border-tertiary", "-g-accent", "-g-accent-hover", "-g-accent-foreground",
			"-g-accent-soft", "-g-accent-soft-hover", "-g-accent-soft-foreground", "-g-success",
			"-g-success-soft", "-g-warning", "-g-warning-soft", "-g-danger", "-g-danger-hover",
			"-g-danger-soft", "-g-muted", "-g-focus", "-g-backdrop", "-g-scrollbar-thumb",
			"-g-icon-color");

	private static final List<String> CLASSES = List.of(
			".button", ".button--primary", ".button--secondary", ".button--outline",
			".button--ghost", ".button--danger", ".button--danger-soft", ".button--link",
			".toggle-button", ".toggle-group", ".switch", ".switch__control", ".switch__thumb",
			".slider", ".slider__track", ".slider__fill", ".slider__thumb-pill", ".select",
			".select__trigger", ".select__value", ".select__indicator", ".menu", ".menu-item",
			".list-box", ".list-box-item", ".popover", ".popover__dialog", ".tooltip", ".dialog",
			".dialog__backdrop", ".dialog__title", ".tabs", ".tabs__list-container", ".tabs__tab",
			".tabs__indicator", ".tabs__panel", ".accordion", ".accordion__trigger",
			".accordion__indicator", ".accordion__panel", ".accordion__body", ".card",
			".card__title", ".card__description", ".card__content", ".card__footer", ".label",
			".g-label", ".text-field", ".input", ".textarea", ".separator", ".separator--vertical",
			".skeleton", ".skeleton__shimmer", ".toast", ".toast__title", ".toast__description",
			".tag", ".tag-group__list", ".meter", ".meter__track", ".meter__fill",
			".color-swatch", ".color-area", ".color-area__thumb", ".color-slider__track",
			".source-selector-marquee", ".source-selector-marquee-static",
			".showcase-section", ".kit-root");

	@DisplayName("every design token used by the kit is declared in gravaai.css")
	@Test
	void tokensDeclared() throws IOException {
		String css = stylesheet();
		for (String token : TOKENS) {
			assertTrue(css.contains(token + ":"), "missing token " + token);
		}
	}

	@DisplayName("every component class from the README mapping table exists")
	@Test
	void componentClassesDeclared() throws IOException {
		String css = stylesheet();
		for (String styleClass : CLASSES) {
			assertTrue(css.contains(styleClass), "missing style class " + styleClass);
		}
	}

	@DisplayName("the DM Sans family and the resolved dark palette are wired")
	@Test
	void baseRules() throws IOException {
		String css = stylesheet();
		assertTrue(css.contains("-fx-font-family: \"DM Sans\";"));
		assertTrue(css.contains("-g-bg: #121418;"));
		assertTrue(css.contains("-g-accent: #2fb3a3;"));
		assertTrue(css.contains("-g-danger: #e5656e;"));
	}

	private static String stylesheet() throws IOException {
		try (InputStream in = ThemeCssTest.class.getResourceAsStream("/kit/gravaai.css")) {
			assertNotNull(in, "gravaai.css");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
