package dev.gravaai.core.result;

import java.util.function.Function;

/**
 * Replaces the renderer's {@code { success, error }} IPC results (plan §5.3).
 *
 * @param <T> the success value type
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {

	/** The operation succeeded. */
	record Ok<T>(T value) implements Result<T> {}

	/** The operation failed with a human-readable message. */
	record Err<T>(String error) implements Result<T> {}

	static <T> Result<T> ok(T value) {
		return new Ok<>(value);
	}

	static <T> Result<T> err(String error) {
		return new Err<>(error);
	}

	default boolean isOk() {
		return this instanceof Ok<?>;
	}

	default boolean isErr() {
		return this instanceof Err<?>;
	}

	default T orElse(T fallback) {
		@SuppressWarnings("unchecked")
		T value = this instanceof Ok<?> ok ? (T) ok.value() : fallback;
		return value;
	}

	default <R> Result<R> map(Function<? super T, ? extends R> mapper) {
		@SuppressWarnings("unchecked")
		Result<R> mapped = this instanceof Ok<?> ok ? Result.ok(mapper.apply((T) ok.value())) : Result.err(errorMessage());
		return mapped;
	}

	default String errorMessage() {
		return this instanceof Err<?> err ? err.error() : "";
	}
}
