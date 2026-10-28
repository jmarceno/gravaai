package dev.gravaai.media.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The FFmpeg error mapping (plan §5.7: every negative return becomes an exception). */
class FfmpegExceptionTest {

	@Test
	@DisplayName("AVERROR negates a positive errno")
	void averrorNegatesErrno() {
		assertEquals(-11, FfmpegException.AVERROR(11));
		assertEquals(-12, FfmpegException.AVERROR(12));
	}

	@Test
	@DisplayName("AVERROR_EOF is FFERRTAG('E','O','F',' ')")
	void averrorEofIsFferrTag() {
		assertEquals(-541_478_725, FfmpegException.AVERROR_EOF);
		assertEquals(-541_478_725, FfmpegException.fferrTag('E', 'O', 'F', ' '));
	}

	@Test
	@DisplayName("av_strerror text is attached to the exception")
	void errorTextFromAvStrerror() {
		assertEquals("No such file or directory", FfmpegException.errorText(-2));
	}

	@Test
	@DisplayName("check raises with the operation name and the raw code")
	void checkRaisesWithOperationAndCode() {
		FfmpegException failure = assertThrows(FfmpegException.class,
				() -> FfmpegException.check(FfmpegException.AVERROR_EOF, "av_read_frame"));
		assertEquals("av_read_frame", failure.operation());
		assertEquals(FfmpegException.AVERROR_EOF, failure.code());
		assertTrue(failure.getMessage().contains("av_read_frame"));
		assertTrue(failure.getMessage().contains(String.valueOf(FfmpegException.AVERROR_EOF)));
	}

	@Test
	@DisplayName("check returns a non-negative code unchanged")
	void checkReturnsNonNegativeCode() {
		assertEquals(0, FfmpegException.check(0, "noop"));
		assertEquals(7, FfmpegException.check(7, "noop"));
	}

	@Test
	@DisplayName("checkNotEof lets AVERROR_EOF through")
	void checkNotEofLetsEofThrough() {
		assertEquals(FfmpegException.AVERROR_EOF,
				FfmpegException.checkNotEof(FfmpegException.AVERROR_EOF, "av_read_frame"));
		assertSame(FfmpegException.class,
				assertThrows(FfmpegException.class,
						() -> FfmpegException.checkNotEof(-2, "av_read_frame")).getClass());
	}
}
