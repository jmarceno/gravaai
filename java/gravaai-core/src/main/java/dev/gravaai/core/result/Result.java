package dev.gravaai.core.result;

import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * Replaces the renderer's {@code { success, error }} IPC results (plan §5.3).
 *
 * @param <T> the success value type
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public sealed interface Result<T> permits Result.Ok, Result.Err {

	/** The operation succeeded. */
	record Ok<T>(T value) implements Result<T> {}

	/** The operation failed with a human-readable message. */
	record Err<T>(@Nullable String error) implements Result<T> {}

	static <T> Result<T> ok(T value) {
		return new Ok<>(value);
	}

	static <T> Result<T> err(@Nullable String error) {
		return new Err<>(error);
	}

	default boolean isOk() {
		return this instanceof Ok<?>;
	}

	default @Nullable boolean isErr() {
		return this instanceof Err<?>;
	}

	default T orElse(@Nullable T fallback) {
		return this instanceof Ok<T> ok ? ok.value() : fallback;
	}

	default <R> Result<R> map(Function<? super T, ? extends R> mapper) {
		return this instanceof Ok<T> ok ? Result.ok(mapper.apply(ok.value())) : Result.err(errorMessage());
	}

	default String errorMessage() {
		return this instanceof Err<?> err ? err.error() : "";
	}
}
