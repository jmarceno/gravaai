package dev.gravaai.core.xdg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The XDG base-directory rules, including every default and the blank-value handling. */
class XdgDirectoriesTest {

	private static final Path HOME = Path.of("/home/tester");

	private static Map<String, String> env(String... pairs) {
		Map<String, String> env = new HashMap<>();
		for (int index = 0; index < pairs.length; index += 2) {
			env.put(pairs[index], pairs[index + 1]);
		}
		return env;
	}

	@Test
	@DisplayName("every directory falls back to the spec default")
	void defaultsToTheSpecValues() {
		XdgDirectories xdg = XdgDirectories.of(Map.of(), HOME);

		assertEquals(HOME.resolve(".config"), xdg.configHome());
		assertEquals(HOME.resolve(".local/share"), xdg.dataHome());
		assertEquals(List.of(Path.of("/usr/local/share"), Path.of("/usr/share")), xdg.dataDirs());
		assertNull(xdg.runtimeDirectory());
		assertEquals(HOME, xdg.home());
	}

	@Test
	@DisplayName("an explicit value wins over the default")
	void explicitValuesWin() {
		XdgDirectories xdg = XdgDirectories.of(env(
				"XDG_CONFIG_HOME", "/cfg",
				"XDG_DATA_HOME", "/data",
				"XDG_DATA_DIRS", "/a:/b",
				"XDG_RUNTIME_DIR", "/run/user/1000"), HOME);

		assertEquals(Path.of("/cfg"), xdg.configHome());
		assertEquals(Path.of("/data"), xdg.dataHome());
		assertEquals(List.of(Path.of("/a"), Path.of("/b")), xdg.dataDirs());
		assertEquals(Path.of("/run/user/1000"), xdg.runtimeDirectory());
	}

	@Test
	@DisplayName("a blank value counts as unset, the way the spec says")
	void blankValuesAreUnset() {
		XdgDirectories xdg = XdgDirectories.of(env(
				"XDG_CONFIG_HOME", "  ",
				"XDG_DATA_HOME", "",
				"XDG_DATA_DIRS", "",
				"XDG_RUNTIME_DIR", " "), HOME);

		assertEquals(HOME.resolve(".config"), xdg.configHome());
		assertEquals(HOME.resolve(".local/share"), xdg.dataHome());
		assertEquals(List.of(Path.of("/usr/local/share"), Path.of("/usr/share")), xdg.dataDirs());
		assertNull(xdg.runtimeDirectory());
	}

	@Test
	@DisplayName("empty entries in XDG_DATA_DIRS are skipped, not treated as the working directory")
	void emptyDataDirEntriesAreSkipped() {
		XdgDirectories xdg = XdgDirectories.of(
				env("XDG_DATA_DIRS", ":/a::/b:"), HOME);

		assertEquals(List.of(Path.of("/a"), Path.of("/b")), xdg.dataDirs());
		assertEquals("/a:/b", xdg.dataDirsText());
	}

	@Test
	@DisplayName("the home trash lives under the data home")
	void homeTrashIsUnderDataHome() {
		XdgDirectories xdg = XdgDirectories.of(env("XDG_DATA_HOME", "/data"), HOME);

		assertEquals(Path.of("/data/Trash"), xdg.homeTrash());
	}
}
