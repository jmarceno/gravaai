package dev.gravaai.services.elements;

import java.util.function.DoubleUnaryOperator;

import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import dev.gravaai.media.compose.anim.DeterministicRandom;
import dev.gravaai.media.compose.anim.Easing;
import dev.gravaai.media.compose.anim.Interpolation;
import dev.gravaai.media.compose.anim.Spring;

/**
 * The animation kernel as seen from component code: the only host object injected into an element
 * context besides the canvas. Each method forwards to the Java kernel in
 * {@code dev.gravaai.media.compose.anim}, so a curve written in a component and the same curve
 * evaluated by the host can never disagree. The JavaScript prelude wraps these into the
 * documented {@code interpolate}, {@code spring}, {@code Easing} and {@code random} globals.
 */
public final class ElementKernel {

	static final ElementKernel INSTANCE = new ElementKernel();

	private ElementKernel() {}

	@HostAccess.Export
	public double interpolate(double input, Value inRange, Value outRange, String left,
			String right, Value easing) {
		Interpolation.Options options = new Interpolation.Options(easing(easing)::applyAsDouble,
				Interpolation.Extrapolation.parse(left), Interpolation.Extrapolation.parse(right));
		return Interpolation.interpolate(input, numbers(inRange, "inputRange"),
				numbers(outRange, "outputRange"), options);
	}

	@HostAccess.Export
	public double ease(String name, double t) {
		return Easing.parse(name).orElseThrow(() -> new IllegalArgumentException(
				"unknown easing \"" + name + "\"")).apply(t);
	}

	@HostAccess.Export
	public double bezier(double x1, double y1, double x2, double y2, double t) {
		return Easing.bezier(x1, y1, x2, y2).apply(t);
	}

	@HostAccess.Export
	public double elastic(double bounciness, double t) {
		return Easing.elastic(bounciness).apply(t);
	}

	@HostAccess.Export
	public double steps(int count, double t) {
		return Easing.steps(count).apply(t);
	}

	@HostAccess.Export
	public double spring(double frame, double fps, double mass, double damping, double stiffness,
			boolean overshootClamping, double from, double to, double delay, double duration,
			boolean reverse) {
		return Spring.value(frame, fps, new Spring.Params(mass, damping, stiffness,
				overshootClamping, from, to, delay, duration, reverse));
	}

	@HostAccess.Export
	public double springDuration(double fps, double mass, double damping, double stiffness,
			boolean overshootClamping, double threshold) {
		return Spring.durationInFrames(fps, new Spring.Params(mass, damping, stiffness,
				overshootClamping, 0, 1, 0, 0, false), threshold);
	}

	@HostAccess.Export
	public double random(String seed) {
		return DeterministicRandom.random(seed);
	}

	private static DoubleUnaryOperator easing(Value easing) {
		if (easing == null || easing.isNull()) {
			return Easing.LINEAR::apply;
		}
		if (easing.isString()) {
			Easing parsed = Easing.parse(easing.asString()).orElseThrow(() ->
					new IllegalArgumentException("unknown easing \"" + easing.asString() + "\"; use "
							+ Easing.names()));
			return parsed::apply;
		}
		if (!easing.canExecute()) {
			throw new IllegalArgumentException("easing must be a function such as Easing.cubic");
		}
		return t -> {
			Value result = easing.execute(t);
			if (!result.isNumber()) {
				throw new IllegalArgumentException("easing function must return a number");
			}
			return result.asDouble();
		};
	}

	private static double[] numbers(Value array, String name) {
		if (array == null || !array.hasArrayElements()) {
			throw new IllegalArgumentException(name + " must be an array of numbers");
		}
		int size = (int) Math.min(10_000, array.getArraySize());
		double[] values = new double[size];
		for (int index = 0; index < size; index++) {
			Value element = array.getArrayElement(index);
			if (!element.isNumber()) {
				throw new IllegalArgumentException(name + "[" + index + "] is not a number");
			}
			values[index] = element.asDouble();
		}
		return values;
	}
}
