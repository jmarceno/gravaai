package dev.gravaai.core.result;

import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * Replaces the renderer's {@code { success, error }} IPC results (plan §5.3).
 *
 * @param <T> the success value type
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {

	/** The unit value: the success payload of an operation whose only result is its effect. */
	enum Unit { UNIT }

	/** The operation succeeded. */
	record Ok<T>(T value) implements Result<T> {}

	/** The operation failed with a human-readable message. */
	record Err<T>(String error) implements Result<T> {}

	static <T> Result<T> ok(T value) {
		return new Ok<>(value);
	}

	/**
	 * Success without a payload, for operations whose only result is their side effect.
	 *
	 * <p>{@code Void} cannot be used: it has no value, so a {@code Result<Void>} success could only
	 * be modelled by a null {@link Ok#value()} — {@link Unit} keeps the success payload non-null.
	 */
	static Result<Unit> okUnit() {
		return new Ok<>(Unit.UNIT);
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

	/**
	 * The success value, or the caller-supplied non-null fallback for a failure.
	 *
	 * @see #orNull() for the "no value" case
	 */
	default T orElse(T fallback) {
		return this instanceof Ok<T> ok ? ok.value() : fallback;
	}

	/** The success value, or null when this is an error. */
	default @Nullable T orNull() {
		return this instanceof Ok<T> ok ? ok.value() : null;
	}

	default <R> Result<R> map(Function<? super T, ? extends R> mapper) {
		return this instanceof Ok<T> ok ? Result.ok(mapper.apply(ok.value())) : Result.err(errorMessage());
	}

	default String errorMessage() {
		return this instanceof Err<?> err ? err.error() : "";
	}
}
