package dev.gravaai.media.compose;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Loads the committed golden fixtures for the compositor tests.
 *
 * <p>Preferred source: {@code java/parity/golden/manifest.json} (the agreed task-13 format,
 * read by {@link GoldenManifest}). Until the platform publishes that manifest, the per-project
 * {@code metadata.json} files that task-13 writes next to the references are used: they carry
 * the project path, the capture times, the output width and the reference PNGs; the output
 * height comes from the reference image itself.</p>
 */
final class GoldenFixtures {

	private GoldenFixtures() {}

	/**
	 * @param name the fixture name, also the golden directory name
	 * @param projectFile the fixture project
	 * @param width the output width
	 * @param height the output height
	 * @param times the capture times in seconds, in order
	 * @param references the reference PNGs, in the same order as {@code times}
	 */
	record Case(String name, Path projectFile, int width, int height, double[] times,
			List<Path> references) {
		Case {
			times = times.clone();
			references = List.copyOf(references);
		}
	}

	/** The golden directory under {@code java/parity/golden}. */
	static Path goldenDirectory() {
		return ProjectFixture.repoRoot().resolve("java/parity/golden");
	}

	/** The cases available right now; empty until the platform publishes the manifest. */
	static List<Case> loadAvailable() throws IOException {
		var manifest = GoldenManifest.loadDefault();
		Path goldenDirectory = goldenDirectory();
		if (manifest.isPresent()) {
			List<Case> cases = new ArrayList<>();
			for (GoldenManifest.Case entry : manifest.get()) {
				String name = entry.referenceImages().isEmpty()
						? entry.projectPath().getFileName().toString()
						: entry.referenceImages().get(0).getParent().getFileName().toString();
				cases.add(new Case(name, entry.projectPath(), entry.outputWidth(),
						entry.outputHeight(), entry.times(), entry.referenceImages()));
			}
			return List.copyOf(cases);
		}
		if (!Files.isDirectory(goldenDirectory)) {
			return List.of();
		}
		List<Path> metadataFiles;
		try (var entries = Files.list(goldenDirectory)) {
			metadataFiles = entries
					.filter(Files::isDirectory)
					.map(directory -> directory.resolve("metadata.json"))
					.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(path -> path.getParent().getFileName().toString()))
					.toList();
		}
		List<Case> cases = new ArrayList<>(metadataFiles.size());
		for (Path metadataFile : metadataFiles) {
			cases.add(loadProjectMetadata(metadataFile));
		}
		return List.copyOf(cases);
	}

	private static Case loadProjectMetadata(Path metadataFile) throws IOException {
		Object root = GoldenManifest.parseJson(Files.readString(metadataFile));
		if (!(root instanceof Map<?, ?> object)) {
			throw new IllegalArgumentException("invalid golden metadata: " + metadataFile);
		}
		Path repoRoot = ProjectFixture.repoRoot();
		String project = requireString(object, "project");
		List<Double> timesSeconds = requireNumbers(object, "timesSeconds");
		List<String> frames = requireStrings(object, "frames");
		if (timesSeconds.size() != frames.size()) {
			throw new IllegalArgumentException("metadata times/frames length mismatch: "
					+ metadataFile);
		}
		int width = ((Number) object.get("width")).intValue();
		List<Path> references = new ArrayList<>(frames.size());
		for (String frame : frames) {
			references.add(repoRoot.resolve(frame).normalize());
		}
		// The metadata does not carry the output height; the reference image defines it.
		RgbaFrame reference = GoldenImages.read(references.get(0));
		if (reference.width() != width) {
			throw new IllegalArgumentException("metadata width " + width + " does not match the"
					+ " reference width " + reference.width() + ": " + metadataFile);
		}
		double[] times = new double[timesSeconds.size()];
		for (int i = 0; i < times.length; i++) {
			times[i] = timesSeconds.get(i);
		}
		String name = metadataFile.getParent().getFileName().toString();
		return new Case(name, repoRoot.resolve(project).normalize(), width, reference.height(),
				times, references);
	}

	private static String requireString(Map<?, ?> object, String key) {
		if (!(object.get(key) instanceof String text)) {
			throw new IllegalArgumentException("golden metadata is missing string " + key);
		}
		return text;
	}

	private static List<Double> requireNumbers(Map<?, ?> object, String key) {
		if (!(object.get(key) instanceof List<?> list)) {
			throw new IllegalArgumentException("golden metadata is missing array " + key);
		}
		List<Double> numbers = new ArrayList<>(list.size());
		for (Object entry : list) {
			numbers.add(((Number) entry).doubleValue());
		}
		return numbers;
	}

	private static List<String> requireStrings(Map<?, ?> object, String key) {
		if (!(object.get(key) instanceof List<?> list)) {
			throw new IllegalArgumentException("golden metadata is missing array " + key);
		}
		List<String> strings = new ArrayList<>(list.size());
		for (Object entry : list) {
			strings.add((String) entry);
		}
		return strings;
	}
}
