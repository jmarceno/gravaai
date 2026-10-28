package dev.gravaai.core.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for the sealed {@link Result} type that replaces the IPC {@code { success, error }} objects. */
class ResultTest {

	@Test
	@DisplayName("carries a success value")
	void carriesSuccessValue() {
		Result<String> result = Result.ok("saved");

		assertTrue(result.isOk());
		assertTrue(!result.isErr());
		assertEquals("saved", result.orElse("fallback"));
		assertEquals("", result.errorMessage());
		assertEquals("saved", switch (result) {
			case Result.Ok<String> ok -> ok.value();
			case Result.Err<String> error -> error.error();
		});
	}

	@Test
	@DisplayName("carries an error message")
	void carriesErrorMessage() {
		Result<String> result = Result.err("disk full");

		assertTrue(result.isErr());
		assertEquals("fallback", result.orElse("fallback"));
		assertEquals("disk full", result.errorMessage());
	}

	@Test
	@DisplayName("maps only the success value")
	void mapsSuccessValue() {
		Result<String> ok = Result.ok("12");
		Result<Integer> mapped = ok.map(Integer::valueOf);
		assertTrue(mapped.isOk());
		assertEquals(12, mapped.orElse(0));

		Result<String> error = Result.err("bad");
		Result<Integer> notMapped = error.map(Integer::valueOf);
		assertTrue(notMapped.isErr());
		assertEquals("bad", notMapped.errorMessage());
	}
}
