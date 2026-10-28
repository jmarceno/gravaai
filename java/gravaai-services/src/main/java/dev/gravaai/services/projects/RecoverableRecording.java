package dev.gravaai.services.projects;

/**
 * One interrupted recording offered by the dashboard banner (port of the
 * {@code listRecoverableRecordings} item shape in {@code electron/electron-env.d.ts}).
 *
 * @param id the recording timestamp as a string (the recoverable manifest id)
 * @param manifestPath the {@code .gravaai-session.json} path
 * @param createdAt the manifest {@code createdAt} ISO timestamp
 * @param hasVideo {@code true} when the interrupted video file is non-empty
 * @param meeting {@code true} when the session kept speaker tracks (a meeting recording)
 * @param bytes total bytes of the surviving video and audio files
 */
public record RecoverableRecording(
		String id,
		String manifestPath,
		String createdAt,
		boolean hasVideo,
		boolean meeting,
		long bytes) {
}
