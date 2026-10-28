package dev.gravaai.services.projects;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.gravaai.core.json.Json;

/** Fixtures shared by the project-library tests (PNG headers and minimal project files). */
final class ProjectTestFiles {

	private ProjectTestFiles() {}

	/** A PNG header with the given dimensions; enough for {@code ThumbnailFreshness}. */
	static byte[] pngHeader(int width, int height) {
		byte[] header = new byte[24];
		byte[] signature = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
		System.arraycopy(signature, 0, header, 0, signature.length);
		putInt(header, 16, width);
		putInt(header, 20, height);
		return header;
	}

	/** A loadable project file (version 2, a video path and an editor object). */
	static void writeProject(Path projectPath, Path videoPath) throws IOException {
		Map<String, Object> project = new LinkedHashMap<>();
		project.put("version", 2);
		project.put("videoPath", videoPath.toString());
		project.put("editor", new LinkedHashMap<>());
		writeJson(projectPath, Json.stringify(project));
	}

	static void writeJson(Path projectPath, String content) throws IOException {
		Files.createDirectories(projectPath.getParent());
		Files.writeString(projectPath, content, StandardCharsets.UTF_8);
	}

	static void writeProjectWithByteOrderMark(Path projectPath, Path videoPath) throws IOException {
		Map<String, Object> project = new LinkedHashMap<>();
		project.put("version", 1);
		project.put("videoPath", videoPath.toString());
		project.put("editor", new LinkedHashMap<>());
		writeJson(projectPath, "\uFEFF" + Json.stringify(project));
	}

	private static void putInt(byte[] target, int offset, int value) {
		target[offset] = (byte) (value >>> 24);
		target[offset + 1] = (byte) (value >>> 16);
		target[offset + 2] = (byte) (value >>> 8);
		target[offset + 3] = (byte) value;
	}
}
