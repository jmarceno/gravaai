package dev.gravaai.platform.compositor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The display-server classification, which everything else builds on. */
class SessionTypeTest {

	private static Map<String, String> env(String... pairs) {
		Map<String, String> env = new HashMap<>();
		for (int index = 0; index < pairs.length; index += 2) {
			env.put(pairs[index], pairs[index + 1]);
		}
		return env;
	}

	@Test
	@DisplayName("XDG_SESSION_TYPE wins, case- and space-insensitively")
	void explicitSessionTypeWins() {
		assertEquals(SessionType.WAYLAND, SessionType.detect(env("XDG_SESSION_TYPE", "wayland")));
		assertEquals(SessionType.WAYLAND, SessionType.detect(env("XDG_SESSION_TYPE", " Wayland ")));
		assertEquals(SessionType.X11, SessionType.detect(env("XDG_SESSION_TYPE", "x11")));
		// An explicit X11 wins over a stale WAYLAND_DISPLAY left in the environment.
		assertEquals(SessionType.X11, SessionType.detect(env(
				"XDG_SESSION_TYPE", "x11", "WAYLAND_DISPLAY", "wayland-0")));
	}

	@Test
	@DisplayName("with no declared type, WAYLAND_DISPLAY proves Wayland and DISPLAY proves X11")
	void fallsBackToTheDisplayVariables() {
		assertEquals(SessionType.WAYLAND, SessionType.detect(env("WAYLAND_DISPLAY", "wayland-1")));
		assertEquals(SessionType.X11, SessionType.detect(env("DISPLAY", ":0")));
		// A Wayland session frequently has both; Wayland is the real one.
		assertEquals(SessionType.WAYLAND, SessionType.detect(env(
				"WAYLAND_DISPLAY", "wayland-1", "DISPLAY", ":0")));
	}

	@Test
	@DisplayName("an unrecognised declared type falls through to the display variables")
	void unknownDeclaredTypeFallsThrough() {
		assertEquals(SessionType.X11, SessionType.detect(env(
				"XDG_SESSION_TYPE", "tty", "DISPLAY", ":1")));
	}

	@Test
	@DisplayName("nothing set means UNKNOWN, never a guess")
	void nothingSetIsUnknown() {
		assertEquals(SessionType.UNKNOWN, SessionType.detect(Map.of()));
		assertEquals(SessionType.UNKNOWN, SessionType.detect(env("WAYLAND_DISPLAY", "")));
		assertEquals(SessionType.UNKNOWN, SessionType.detect(env("DISPLAY", "  ")));
	}
}
