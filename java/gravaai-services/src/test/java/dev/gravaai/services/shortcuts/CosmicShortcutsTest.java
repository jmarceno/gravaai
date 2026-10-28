package dev.gravaai.services.shortcuts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of {@code electron/globalShortcuts/cosmicShortcuts.test.ts}, with the two fixture files
 * copied byte for byte from {@code electron/globalShortcuts/__fixtures__/}. The extra cases cover
 * what plan §5.17 makes non-negotiable: only Gravaai's own entries are touched and every other
 * byte survives (BOM, missing trailing newline, comments, a user edit).
 */
class CosmicShortcutsTest {

	private static final String CUSTOM_TEXT = fixture("custom.ron");
	private static final String DEFAULTS_TEXT = fixture("defaults.ron");

	private static String fixture(String name) {
		try (InputStream in = CosmicShortcutsTest.class.getResourceAsStream("/shortcuts/" + name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), UTF_8);
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}

	private static GlobalShortcutBinding binding(String key, boolean sup, boolean ctrl, boolean alt, boolean shift) {
		return new GlobalShortcutBinding(key, sup, ctrl, alt, shift);
	}

	private static GravaaiCosmicEntry ours(String key) {
		return ours(
				key,
				"'/home/u/.local/share/gravaai/bin/gravaai-ctl' toggle-recording '/opt/Gravaai'");
	}

	private static GravaaiCosmicEntry ours(String key, String command) {
		return new GravaaiCosmicEntry(
				binding(key, true, false, true, false), "Gravaai: " + key, command);
	}

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("parseCosmicShortcuts parses the pretty custom form")
	void parsesPrettyCustomForm() {
		List<CosmicShortcutEntry> entries = CosmicShortcuts.parseCosmicShortcuts(CUSTOM_TEXT).entries();
		assertEquals(3, entries.size());
		assertEquals(List.of("Super"), entries.get(0).modifiers());
		assertEquals("r", entries.get(0).key());
		assertEquals("Lepramim: Speak highlighted selection", entries.get(0).description());
		assertEquals(
				"busctl --user call org.lepramim.App /org/lepramim/App org.lepramim.App SpeakSelection",
				entries.get(0).spawnCommand());
		assertEquals("Return", entries.get(1).key());
		assertNull(entries.get(1).description());
		assertEquals("System(Terminal)", entries.get(1).action());
		assertNull(entries.get(1).spawnCommand());
		assertEquals(List.of(), entries.get(2).modifiers());
		assertEquals("Print", entries.get(2).key());
	}

	@Test
	@DisplayName("parseCosmicShortcuts parses the compact defaults form with comments and keycode entries")
	void parsesCompactDefaultsForm() {
		List<CosmicShortcutEntry> entries = CosmicShortcuts.parseCosmicShortcuts(DEFAULTS_TEXT).entries();
		CosmicShortcutEntry debug = entries.stream()
				.filter(entry -> entry.key() == null && entry.action().equals("Debug"))
				.findFirst()
				.orElseThrow();
		assertEquals(List.of("Super", "Ctrl"), debug.modifiers());
		assertEquals(
				"Close",
				entries.stream().filter(entry -> "q".equals(entry.key())).findFirst().orElseThrow().action());
		assertTrue(entries.stream()
				.anyMatch(entry -> entry.key() == null && entry.action().equals("System(Launcher)")));
	}

	@Test
	@DisplayName("parseCosmicShortcuts handles None descriptions, escapes and a missing trailing comma")
	void parsesNoneDescriptionsAndEscapes() {
		String text = "{\n"
				+ "    (modifiers: [Super], key: \"x\", description: None): Spawn(\"echo \\\"hi\\\" \\\\ \\u{e9}\")\n"
				+ "}";
		CosmicShortcutEntry entry = CosmicShortcuts.parseCosmicShortcuts(text).entries().get(0);
		assertNull(entry.description());
		assertEquals("echo \"hi\" \\ é", entry.spawnCommand());
		assertFalse(entry.hasTrailingComma());
	}

	@Test
	@DisplayName("parseCosmicShortcuts throws on broken files")
	void throwsOnBrokenFiles() {
		for (String text : List.of(
				"{ (modifiers: [Super], key: \"x\": Close, }",
				"{ (modifiers: [Super], key: \"x\"): Spawn(\"a\", }",
				"(modifiers: [], key: \"x\"): Close",
				"{ (modifiers: [], key: \"x\"): Close } trailing",
				"{ (modifiers: [], key: \"x): Close }")) {
			assertThrows(
					CosmicShortcutsParseException.class,
					() -> CosmicShortcuts.parseCosmicShortcuts(text),
					text);
		}
	}

	@Test
	@DisplayName("replaceGravaaiEntries returns identical text when there is nothing to change")
	void replaceNothing() {
		assertEquals(CUSTOM_TEXT, CosmicShortcuts.replaceGravaaiEntries(CUSTOM_TEXT, List.of()));
		assertEquals(DEFAULTS_TEXT, CosmicShortcuts.replaceGravaaiEntries(DEFAULTS_TEXT, List.of()));
	}

	@Test
	@DisplayName("replaceGravaaiEntries appends entries and removes them again byte-for-byte")
	void appendAndRemove() {
		String added = CosmicShortcuts.replaceGravaaiEntries(CUSTOM_TEXT, List.of(ours("r"), ours("p")));
		assertTrue(added.startsWith(CUSTOM_TEXT.substring(0, CUSTOM_TEXT.lastIndexOf('}'))));
		String expected = "    (\n"
				+ "        modifiers: [\n"
				+ "            Super,\n"
				+ "            Alt,\n"
				+ "        ],\n"
				+ "        key: \"r\",\n"
				+ "        description: Some(\"Gravaai: r\"),\n"
				+ "    ): Spawn(\"'/home/u/.local/share/gravaai/bin/gravaai-ctl' toggle-recording '/opt/Gravaai'\"),\n";
		assertTrue(added.contains(expected), added);
		assertEquals(5, CosmicShortcuts.parseCosmicShortcuts(added).entries().size());
		assertEquals(CUSTOM_TEXT, CosmicShortcuts.replaceGravaaiEntries(added, List.of()));
		assertEquals(added, CosmicShortcuts.replaceGravaaiEntries(added, List.of(ours("r"), ours("p"))));
	}

	@Test
	@DisplayName("replaceGravaaiEntries keeps foreign entries placed after ours byte-identical")
	void keepsForeignEntries() {
		String added = CosmicShortcuts.replaceGravaaiEntries(CUSTOM_TEXT, List.of(ours("r")));
		String foreign = "    (\n"
				+ "        modifiers: [],\n"
				+ "        key: \"F7\",\n"
				+ "    ): Spawn(\"foo\"),\n";
		String withForeign =
				added.substring(0, added.lastIndexOf('}')) + foreign + "}\n";
		String cleaned = CosmicShortcuts.replaceGravaaiEntries(withForeign, List.of());
		assertEquals(
				CUSTOM_TEXT.substring(0, CUSTOM_TEXT.lastIndexOf('}')) + foreign + "}\n", cleaned);
	}

	@Test
	@DisplayName("replaceGravaaiEntries removes stale gravaai-ctl entries without our description")
	void removesStaleCtlEntries() {
		String staleEntry = "    (\n"
				+ "        modifiers: [Super],\n"
				+ "        key: \"z\",\n"
				+ "    ): Spawn(\"'/tmp/.mount_Old/gravaai-ctl' toggle-recording\"),\n";
		String stale = CUSTOM_TEXT.substring(0, CUSTOM_TEXT.length() - 2) + staleEntry + "}\n";
		assertEquals(CUSTOM_TEXT, CosmicShortcuts.replaceGravaaiEntries(stale, List.of()));
	}

	@Test
	@DisplayName("replaceGravaaiEntries creates a map for empty files and handles {}")
	void emptyFiles() {
		String fromEmpty = CosmicShortcuts.replaceGravaaiEntries("", List.of(ours("r")));
		assertEquals(1, CosmicShortcuts.parseCosmicShortcuts(fromEmpty).entries().size());
		assertTrue(fromEmpty.startsWith("{\n    (\n"));
		String fromBraces = CosmicShortcuts.replaceGravaaiEntries("{}", List.of(ours("r")));
		assertEquals(1, CosmicShortcuts.parseCosmicShortcuts(fromBraces).entries().size());
	}

	@Test
	@DisplayName("replaceGravaaiEntries adds a comma after a last entry that has none")
	void addsMissingComma() {
		String text = "{\n    (modifiers: [], key: \"x\"): Close\n}\n";
		String result = CosmicShortcuts.replaceGravaaiEntries(text, List.of(ours("r")));
		assertTrue(result.startsWith("{\n    (modifiers: [], key: \"x\"): Close,\n"));
		assertEquals(2, CosmicShortcuts.parseCosmicShortcuts(result).entries().size());
	}

	@Test
	@DisplayName("replaceGravaaiEntries escapes quotes and backslashes")
	void escapesQuotesAndBackslashes() {
		String result = CosmicShortcuts.replaceGravaaiEntries(
				"{\n}\n",
				List.of(new GravaaiCosmicEntry(
						binding("r", false, true, false, false), "Gravaai: \"x\"", "a\\b")));
		CosmicShortcutEntry entry = CosmicShortcuts.parseCosmicShortcuts(result).entries().get(0);
		assertEquals("Gravaai: \"x\"", entry.description());
		assertEquals("a\\b", entry.spawnCommand());
	}

	@Test
	@DisplayName("replaceGravaaiEntries throws instead of rewriting a broken file")
	void throwsInsteadOfRewriting() {
		assertThrows(
				CosmicShortcutsParseException.class,
				() -> CosmicShortcuts.replaceGravaaiEntries("{ (modifiers: [", List.of(ours("r"))));
	}

	@Test
	@DisplayName("replaceGravaaiEntries keeps a user edit of an unrelated line byte for byte")
	void keepsUserEdit() {
		String edited = CUSTOM_TEXT.replace("key: \"Print\"", "key: \"F7\"");
		String added = CosmicShortcuts.replaceGravaaiEntries(edited, List.of(ours("r"), ours("m")));
		assertEquals(edited, CosmicShortcuts.replaceGravaaiEntries(added, List.of()));
	}

	@Test
	@DisplayName("replaceGravaaiEntries preserves a leading BOM")
	void preservesBom() {
		String withBom = "\uFEFF" + CUSTOM_TEXT;
		String added = CosmicShortcuts.replaceGravaaiEntries(withBom, List.of(ours("r")));
		assertEquals(withBom, CosmicShortcuts.replaceGravaaiEntries(added, List.of()));
		assertEquals('\uFEFF', CosmicShortcuts.replaceGravaaiEntries(added, List.of()).charAt(0));
	}

	@Test
	@DisplayName("replaceGravaaiEntries preserves a file without a trailing newline")
	void preservesMissingTrailingNewline() {
		String noTrailingNewline = CUSTOM_TEXT.substring(0, CUSTOM_TEXT.length() - 1);
		String added = CosmicShortcuts.replaceGravaaiEntries(noTrailingNewline, List.of(ours("r")));
		assertEquals(noTrailingNewline, CosmicShortcuts.replaceGravaaiEntries(added, List.of()));
	}

	@Test
	@DisplayName("replaceGravaaiEntries preserves comments anywhere outside our entries")
	void preservesComments() {
		String withComment = "// my note\n" + CUSTOM_TEXT;
		String added = CosmicShortcuts.replaceGravaaiEntries(withComment, List.of(ours("r")));
		assertEquals(withComment, CosmicShortcuts.replaceGravaaiEntries(added, List.of()));
	}

	@Test
	@DisplayName("buildCtlCommand single-quotes paths")
	void buildCtlCommand() {
		assertEquals(
				"'/home/u/bin/gravaai-ctl' toggle-pause '/opt/Gravaai/app'",
				CosmicShortcuts.buildCtlCommand(
						Path.of("/home/u/bin/gravaai-ctl"), GlobalShortcutAction.TOGGLE_PAUSE, "/opt/Gravaai/app"));
		assertEquals(
				"'/home/o'\\''neil/ctl' toggle-recording '/a'",
				CosmicShortcuts.buildCtlCommand(
						Path.of("/home/o'neil/ctl"), GlobalShortcutAction.TOGGLE_RECORDING, "/a"));
	}

	@Test
	@DisplayName("findCosmicConflict finds Super+Q in the defaults")
	void findsSuperQ() {
		List<CosmicShortcutEntry> defaults = CosmicShortcuts.parseCosmicShortcuts(DEFAULTS_TEXT).entries();
		GlobalShortcutBinding binding = binding("q", true, false, false, false);
		CosmicShortcutEntry conflict = CosmicShortcuts.findCosmicConflict(binding, defaults);
		assertNotNull(conflict);
		assertEquals("Close", conflict.action());
		assertEquals("Super + Q is used by COSMIC (Close)", CosmicShortcuts.describeCosmicConflict(binding, conflict));
	}

	@Test
	@DisplayName("findCosmicConflict compares modifier sets regardless of order and ignores keycode entries")
	void conflictComparesModifierSets() {
		List<CosmicShortcutEntry> defaults = CosmicShortcuts.parseCosmicShortcuts(DEFAULTS_TEXT).entries();
		assertEquals(
				"System(WindowSwitcherPrevious)",
				CosmicShortcuts.findCosmicConflict(binding("Tab", false, false, true, true), defaults).action());
		assertNull(CosmicShortcuts.findCosmicConflict(binding("r", true, false, true, false), defaults));
		assertEquals(
				"Resizing(Outwards)",
				CosmicShortcuts.findCosmicConflict(binding("R", true, false, false, false), defaults).action());
	}

	@Test
	@DisplayName("findCosmicConflict skips our own entries")
	void conflictSkipsOurs() {
		String added = CosmicShortcuts.replaceGravaaiEntries(CUSTOM_TEXT, List.of(ours("r")));
		List<CosmicShortcutEntry> entries = CosmicShortcuts.parseCosmicShortcuts(added).entries();
		assertNull(CosmicShortcuts.findCosmicConflict(binding("r", true, false, true, false), entries));
		assertEquals(
				"Super + R is used by COSMIC (Lepramim: Speak highlighted selection)",
				CosmicShortcuts.describeCosmicConflict(
						binding("r", true, false, false, false),
						CosmicShortcuts.findCosmicConflict(binding("r", true, false, false, false), entries)));
	}

	@Test
	@DisplayName("getCosmicShortcutPaths resolves XDG paths and readCosmicDefaultShortcuts prefers the user's override")
	void resolvesXdgPaths() throws IOException {
		Path dir = Files.createTempDirectory(tempDir, "cosmic-");
		Map<String, String> env = Map.of(
				"XDG_CONFIG_HOME", dir.resolve("config").toString(),
				"XDG_DATA_DIRS", dir.resolve("a") + ":" + dir.resolve("b"));
		CosmicShortcutPaths paths = CosmicShortcuts.getCosmicShortcutPaths(env);
		assertEquals(
				dir.resolve("config/cosmic/com.system76.CosmicSettings.Shortcuts/v1/custom"), paths.custom());
		assertEquals("", CosmicShortcuts.readCosmicDefaultShortcuts(paths));
		Files.createDirectories(paths.systemDefaults().get(1).getParent());
		Files.writeString(paths.systemDefaults().get(1), "system");
		assertEquals("system", CosmicShortcuts.readCosmicDefaultShortcuts(paths));
		Files.createDirectories(paths.userDefaults().getParent());
		Files.writeString(paths.userDefaults(), "user");
		assertEquals("user", CosmicShortcuts.readCosmicDefaultShortcuts(paths));
	}

	@Test
	@DisplayName("writeCosmicCustomShortcuts backs up the original file once and replaces it")
	void writesWithBackup() throws IOException {
		Path dir = Files.createTempDirectory(tempDir, "cosmic-");
		CosmicShortcutPaths paths = CosmicShortcuts.getCosmicShortcutPaths(
				Map.of("XDG_CONFIG_HOME", dir.toString(), "XDG_DATA_DIRS", dir.toString()));
		CosmicShortcuts.writeCosmicCustomShortcuts("first", paths);
		assertFalse(Files.exists(Path.of(paths.custom() + ".gravaai-backup")));
		CosmicShortcuts.writeCosmicCustomShortcuts("second", paths);
		CosmicShortcuts.writeCosmicCustomShortcuts("third", paths);
		assertEquals("third", Files.readString(paths.custom()));
		assertEquals("first", Files.readString(Path.of(paths.custom() + ".gravaai-backup")));
		assertFalse(Files.exists(Path.of(paths.custom() + ".tmp-gravaai")));
	}
}
