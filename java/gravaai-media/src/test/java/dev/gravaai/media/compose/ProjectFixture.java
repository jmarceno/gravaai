package dev.gravaai.media.compose;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;

/**
 * Loads the committed fixture projects ({@code java/parity/fixtures/projects}) through the real
 * project model and maps the editor state to the media-local compositor inputs. This is the
 * test-scope stand-in for the production services adapter (lead decision: FrameInput stays
 * media-local).
 */
final class ProjectFixture {

	private static final Path REPO_ROOT = locateRepoRoot();

	private ProjectFixture() {}

	/** The repository root, found from the Maven module working directory. */
	static Path repoRoot() {
		return REPO_ROOT;
	}

	/**
	 * The bundled wallpaper assets. {@code ProjectEditorInputs} expects a directory containing
	 * {@code wallpapers/}; the Java resources keep the Electron layout under {@code ui/editor/},
	 * and {@code public/} is removed in P3.6.
	 */
	static Path wallpaperRoot() {
		return REPO_ROOT.resolve("java/gravaai-ui/src/main/resources/ui/editor");
	}

	/** A loaded fixture project. */
	record Loaded(Path projectFile, ProjectDocument document, ProjectEditorState editor) {
		Path videoPath() {
			return Path.of(document.videoPath());
		}
	}

	/** Loads {@code java/parity/fixtures/projects/<name>.gravaai}. */
	static Loaded load(String name) throws IOException {
		Path file = REPO_ROOT.resolve("java/parity/fixtures/projects").resolve(name + ".gravaai");
		if (!Files.isRegularFile(file)) {
			throw new IOException("fixture project not found: " + file);
		}
		ProjectDocument document = ProjectDocument.load(file);
		return new Loaded(file, document, document.editor());
	}

	/** Resolves the project wallpaper to a compositor background (shared production mapping). */
	static Background background(ProjectEditorState editor) {
		return ProjectEditorInputs.background(editor, wallpaperRoot());
	}

	/** Maps the padded/cropped layout (shared production mapping). */
	static LayoutSpec layout(ProjectEditorState editor) {
		return ProjectEditorInputs.layout(editor);
	}

	/** Maps the editor zoom state (shared production mapping). */
	static ZoomSpec zoomSpec(ProjectEditorState editor) {
		return ProjectEditorInputs.zoomSpec(editor);
	}

	/** Maps the stage-4 frame style (shared production mapping). */
	static FrameStyle frameStyle(ProjectEditorState editor) {
		return ProjectEditorInputs.frameStyle(editor);
	}

	/** Maps the editor cursor state (shared production mapping). */
	static CursorSpec cursorSpec(ProjectEditorState editor) {
		return ProjectEditorInputs.cursorSpec(editor);
	}

	private static CursorClickEffect cursorClickEffect(
			dev.gravaai.core.project.CursorClickEffectStyle effect) {
		if (effect == null) {
			return CursorClickEffect.NONE;
		}
		String name = effect.name().toUpperCase();
		if (name.contains("RIPPLE")) {
			return CursorClickEffect.RIPPLE;
		}
		if (name.contains("SPOTLIGHT")) {
			return CursorClickEffect.SPOTLIGHT;
		}
		if (name.contains("ECHO")) {
			return CursorClickEffect.ECHO;
		}
		return CursorClickEffect.NONE;
	}

	private static int parseColour(String value) {
		if (value == null || value.length() < 7) {
			return 0xFF2563EB;
		}
		String hex = value.startsWith("#") ? value.substring(1) : value;
		if (hex.length() == 8) {
			return (int) Long.parseLong(hex, 16);
		}
		if (hex.length() == 6) {
			return 0xFF000000 | (int) Long.parseLong(hex, 16);
		}
		return 0xFF2563EB;
	}

	static WebcamSpec webcamSpec(ProjectEditorState editor) {
		return ProjectEditorInputs.webcamSpec(editor);
	}

	/** Maps the editor annotations (shared production mapping). */
	static java.util.List<AnnotationSpec> annotationSpecs(ProjectEditorState editor) {
		return ProjectEditorInputs.annotationSpecs(editor);
	}

	private static Integer parseColourOrNull(String value) {
		if (value == null || value.isBlank() || "transparent".equalsIgnoreCase(value.strip())) {
			return null;
		}
		int parsed = parseColour(value);
		return (parsed >>> 24) == 0 ? null : parsed;
	}

	/** Maps the editor caption settings/cues (shared production mapping). */
	static CaptionSpec captionSpec(ProjectEditorState editor) {
		return ProjectEditorInputs.captionSpec(editor);
	}

	/** Maps the editor keystroke overlay (shared production mapping). */
	static KeystrokeSpec keystrokeSpec(ProjectEditorState editor, Path videoPath) {
		return ProjectEditorInputs.keystrokeSpec(editor, videoPath);
	}

	/** Maps the project's fade-through-black clip transitions (shared production mapping). */
	static FadeSpec fadeSpec(ProjectEditorState editor) {
		return ProjectEditorInputs.fadeSpec(editor);
	}

	/** The output size for a fixed output width, following the project aspect ratio. */
	static RenderTargetSize outputSize(ProjectEditorState editor, int width) {
		return AspectRatioMath.dimensionsForWidth(editor.aspectRatio(), width);
	}

	private static Path locateRepoRoot() {
		List<Path> candidates = List.of(
				Path.of("..", ".."),
				Path.of(".."),
				Path.of("."));
		for (Path candidate : candidates) {
			Path absolute = candidate.toAbsolutePath().normalize();
			if (Files.isDirectory(absolute.resolve("java/gravaai-ui/src/main/resources/ui/editor")
					.resolve("wallpapers"))
					&& Files.isDirectory(absolute.resolve("java").resolve("parity"))) {
				return absolute;
			}
		}
		throw new IllegalStateException(
				"cannot locate the repository root (tried " + candidates + ")");
	}
}
