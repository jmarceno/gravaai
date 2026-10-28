package dev.gravaai.media.export;

/** The user cancelled the export; temp files have been removed. */
public class ExportCancelledException extends ExportException {

	private static final long serialVersionUID = 1L;

	public ExportCancelledException() {
		super("export cancelled");
	}
}
