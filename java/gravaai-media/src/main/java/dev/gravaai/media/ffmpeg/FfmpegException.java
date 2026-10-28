package dev.gravaai.media.ffmpeg;

/**
 * A failed FFmpeg call: every negative FFmpeg return value is raised as one of these with
 * the error code and the text from {@code av_strerror} (plan §5.7).
 *
 * <p>FFmpeg error codes are negative values; the macros behind the well-known ones are not
 * visible to jextract, so {@link #AVERROR(int)} and the named constants below compute them
 * exactly like {@code libavutil/error.h} does:
 * {@code AVERROR(e) = -e} and {@code FFERRTAG(a,b,c,d) = -(a | b<<8 | c<<16 | d<<24)}.</p>
 */
public final class FfmpegException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** {@code FFERRTAG('E','O','F',' ')} — end of file / end of stream. */
	public static final int AVERROR_EOF = fferrTag('E', 'O', 'F', ' ');

	/** {@code FFERRTAG('I','N','D','A')} — invalid data found when processing input. */
	public static final int AVERROR_INVALIDDATA = fferrTag('I', 'N', 'D', 'A');

	/** {@code FFERRTAG('E','X','I','T')} — immediate exit was requested. */
	public static final int AVERROR_EXIT = fferrTag('E', 'X', 'I', 'T');

	/** {@code FFERRTAG('U','N','K','N')} — unknown error. */
	public static final int AVERROR_UNKNOWN = fferrTag('U', 'N', 'K', 'N');

	/** {@code AVERROR(EAGAIN)} — the call needs more input/output to make progress. */
	public static final int AVERROR_EAGAIN = AVERROR(11);

	/** {@code AVERROR(ENOMEM)} — out of memory. */
	public static final int AVERROR_ENOMEM = AVERROR(12);

	private final int code;
	private final String operation;

	public FfmpegException(String operation, int code) {
		super(operation + " failed: " + errorText(code) + " (" + code + ")");
		this.operation = operation;
		this.code = code;
	}

	/** The operation that failed, for example {@code avformat_open_input}. */
	public String operation() {
		return operation;
	}

	/** The raw negative FFmpeg error code. */
	public int code() {
		return code;
	}

	/** {@code AVERROR(e)}: negates a positive errno. */
	public static int AVERROR(int errno) {
		return -errno;
	}

	/** {@code FFERRTAG(a,b,c,d)}: packs four characters into a negative FFmpeg error code. */
	public static int fferrTag(int a, int b, int c, int d) {
		return -(a | (b << 8) | (c << 16) | (d << 24));
	}

	/** The {@code av_strerror} text for a code, or a numeric fallback when that fails. */
	public static String errorText(int code) {
		FfmpegLibs.ensureLoaded();
		int size = 256;
		try (var arena = java.lang.foreign.Arena.ofConfined()) {
			var buffer = arena.allocate(size);
			int result = dev.gravaai.media.ffmpeg.gen.ffmpeg_h.av_strerror(code, buffer, size);
			if (result < 0) {
				return "error " + code;
			}
			return buffer.getString(0);
		}
	}

	/**
	 * Raises an {@link FfmpegException} when {@code code} is negative; otherwise returns the
	 * code unchanged so callers can write {@code int ret = check(...)}.
	 */
	public static int check(int code, String operation) {
		if (code < 0) {
			throw new FfmpegException(operation, code);
		}
		return code;
	}

	/** Returns the code when it is {@link #AVERROR_EOF}, raises otherwise. */
	public static int checkNotEof(int code, String operation) {
		if (code == AVERROR_EOF) {
			return code;
		}
		return check(code, operation);
	}
}
