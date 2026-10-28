package dev.gravaai.core.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Tests for {@link LocaleParityCheck}, the port of {@code scripts/i18n-check.mjs}. */
class LocaleParityCheckTest {

	@TempDir
	Path root;

	@Test
	@DisplayName("reports no errors for structurally consistent locales")
	void reportsConsistentLocales() throws IOException {
		write("en", "common.json", "{\"app\":{\"name\":\"Gravaai\",\"language\":\"Language\"}}");
		write("de", "common.json", "{\"app\":{\"name\":\"Gravaai\",\"language\":\"Sprache\"}}");

		assertEquals(List.of(), LocaleParityCheck.check(root));
	}

	@Test
	@DisplayName("reports a missing key")
	void reportsMissingKey() throws IOException {
		write("en", "common.json", "{\"app\":{\"name\":\"Gravaai\",\"language\":\"Language\"}}");
		write("de", "common.json", "{\"app\":{\"name\":\"Gravaai\"}}");

		assertEquals(List.of("i18n-check: missing key de/common.json:app.language"),
				LocaleParityCheck.check(root));
	}

	@Test
	@DisplayName("reports an extra key")
	void reportsExtraKey() throws IOException {
		write("en", "common.json", "{\"app\":{\"name\":\"Gravaai\"}}");
		write("de", "common.json", "{\"app\":{\"name\":\"Gravaai\",\"zukunft\":\"Neu\"}}");

		assertEquals(List.of("i18n-check: extra key de/common.json:app.zukunft"),
				LocaleParityCheck.check(root));
	}

	@Test
	@DisplayName("reports a missing namespace file")
	void reportsMissingNamespaceFile() throws IOException {
		write("en", "common.json", "{}");
		write("en", "settings.json", "{\"zoom\":\"Zoom\"}");
		write("de", "common.json", "{}");

		assertEquals(List.of("i18n-check: missing namespace file de/settings.json"),
				LocaleParityCheck.check(root));
	}

	@Test
	@DisplayName("reports a missing base locale")
	void reportsMissingBaseLocale() throws IOException {
		write("de", "common.json", "{}");

		assertEquals(List.of("i18n-check: expected base locale directory \"en\""),
				LocaleParityCheck.check(root));
	}

	@Test
	@DisplayName("requires the same plural suffix keys in every locale")
	void requiresSamePluralSuffixKeys() throws IOException {
		write("en", "common.json",
				"{\"items_one\":\"{{count}} item\",\"items_other\":\"{{count}} items\"}");
		write("de", "common.json", "{\"items_other\":\"{{count}} Eintraege\"}");

		assertEquals(List.of("i18n-check: missing key de/common.json:items_one"),
				LocaleParityCheck.check(root));

		write("de", "common.json",
				"{\"items_one\":\"{{count}} Eintrag\",\"items_other\":\"{{count}} Eintraege\","
						+ "\"items_zero\":\"keine\"}");
		assertEquals(List.of("i18n-check: extra key de/common.json:items_zero"),
				LocaleParityCheck.check(root));
	}

	@Test
	@DisplayName("reports an unparsable file")
	void reportsUnparsableFile() throws IOException {
		write("en", "common.json", "{ nope");
		write("de", "common.json", "{}");

		List<String> errors = LocaleParityCheck.check(root);
		assertEquals(1, errors.size());
		assertTrue(errors.get(0).startsWith("i18n-check: failed to load en/common.json:"), errors.get(0));
	}

	@Test
	@DisplayName("collects leaf key paths with arrays and empty objects as leaves")
	void collectsKeyPaths() {
		ObjectNode tree = Json.nodeFactory().objectNode();
		ArrayNode array = Json.nodeFactory().arrayNode();
		array.add(Json.nodeFactory().numberNode(1));
		tree.set("a", array);
		tree.set("b", Json.nodeFactory().objectNode());
		ObjectNode nested = Json.nodeFactory().objectNode();
		nested.put("d", "x");
		ObjectNode deeper = Json.nodeFactory().objectNode();
		deeper.put("f", "y");
		nested.set("e", deeper);
		tree.set("c", nested);

		assertEquals(List.of("a", "b", "c.d", "c.e.f"), LocaleParityCheck.collectKeyPaths(tree));
		assertEquals(List.of(), LocaleParityCheck.collectKeyPaths(Json.nodeFactory().objectNode()));
	}

	private void write(String locale, String namespace, String contents) throws IOException {
		Path directory = root.resolve(locale);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve(namespace), contents, StandardCharsets.UTF_8);
	}
}
