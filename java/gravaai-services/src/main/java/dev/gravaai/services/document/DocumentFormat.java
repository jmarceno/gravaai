package dev.gravaai.services.document;

/**
 * The document formats the app writes. Each constant knows its file extension and display name,
 * so a picker, a file chooser filter and a writer lookup all read the same declaration instead of
 * comparing strings.
 */
public enum DocumentFormat {
	MARKDOWN("md", "Markdown"),
	DOCX("docx", "Word document"),
	PDF("pdf", "PDF document");

	private final String extension;
	private final String label;

	DocumentFormat(String extension, String label) {
		this.extension = extension;
		this.label = label;
	}

	/** The file-name extension, without the dot. */
	public String extension() {
		return extension;
	}

	/** The human-readable format name. */
	public String label() {
		return label;
	}

	/** The file chooser glob for this format, e.g. {@code *.docx}. */
	public String glob() {
		return "*." + extension;
	}

	/**
	 * The format of a file name, when it has one of ours.
	 *
	 * @param fileName the file name
	 * @return the matching format; empty when the name carries no known extension
	 */
	public static java.util.Optional<DocumentFormat> fromFileName(String fileName) {
		String lower = fileName.toLowerCase(java.util.Locale.ROOT);
		for (DocumentFormat format : values()) {
			if (lower.endsWith("." + format.extension)) {
				return java.util.Optional.of(format);
			}
		}
		return java.util.Optional.empty();
	}

	/** True when {@code fileName} already ends in this format's extension (any case). */
	public boolean matches(String fileName) {
		return fileName.toLowerCase(java.util.Locale.ROOT).endsWith("." + extension);
	}
}
