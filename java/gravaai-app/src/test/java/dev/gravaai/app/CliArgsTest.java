package dev.gravaai.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line: flags, launch actions (resolved later) and the positional launch file. */
class CliArgsTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("flags parse and unknown flags are ignored")
	void flags() {
		CliArgs cli = CliArgs.parse(new String[] {"--mcp", "--ui-kit", "--dashboard"});
		assertEquals(true, cli.mcp());
		assertEquals(true, cli.uiKit());
		assertEquals(true, cli.dashboard());
		assertNull(cli.launchFile());
	}

	@Test
	@DisplayName("a positional argument naming an existing file is the launch file")
	void launchFile() throws Exception {
		Path project = tempDir.resolve("my project.gravaai");
		Files.writeString(project, "{}");
		CliArgs cli = CliArgs.parse(new String[] {"--dashboard", project.toString()});
		assertEquals(project.toString(), cli.launchFile());
	}

	@Test
	@DisplayName("a positional argument that is not an existing file is not a launch file")
	void missingFileIgnored() {
		assertNull(CliArgs.parse(new String[] {tempDir.resolve("missing.gravaai").toString()}).launchFile());
		assertNull(CliArgs.parse(new String[] {}).launchFile());
	}
}
