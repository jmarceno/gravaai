package dev.gravaai.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeystrokeEventsTest {

	@TempDir
	Path temp;

	@Test
	@DisplayName("counts the labelled key presses of the recording's .keys.json")
	void countsSidecarEvents() throws Exception {
		Path video = temp.resolve("rec.mp4");
		assertEquals(0, KeystrokeEvents.count(null));
		assertEquals(0, KeystrokeEvents.count(video));

		Files.writeString(temp.resolve("rec.mp4.keys.json"), """
				{"version":1,"events":[
				  {"timeMs":100,"key":"S","modifiers":["ctrl"]},
				  {"timeMs":200,"key":" ","modifiers":[]},
				  {"timeMs":300,"key":"Enter","modifiers":[]}
				]}
				""");
		assertEquals(2, KeystrokeEvents.count(video));

		Files.writeString(temp.resolve("rec.mp4.keys.json"), "not json");
		assertEquals(0, KeystrokeEvents.count(video));
	}
}
