package dev.gravaai.media.compose.anim;

import java.nio.charset.StandardCharsets;

/**
 * Seeded randomness for elements: the same seed always gives the same value on every machine and
 * thread, so frames can be rendered in any order. {@link #random(String)} is a stable hash into
 * [0, 1); {@link Sequence} is a seeded generator for code that wants a stream of values.
 */
public final class DeterministicRandom {

	private DeterministicRandom() {}

	/** A stable value in [0, 1) for {@code seed}. */
	public static double random(String seed) {
		return toUnit(mix(hash(seed)));
	}

	/** A seeded generator (splitmix64); the n-th value is fixed by the seed. */
	public static final class Sequence {
		private long state;

		public Sequence(String seed) {
			this.state = hash(seed);
		}

		public double next() {
			state += 0x9E3779B97F4A7C15L;
			return toUnit(mix(state));
		}
	}

	private static long hash(String seed) {
		// FNV-1a over the UTF-8 bytes: stable across JVMs, unlike String.hashCode's spread.
		long hash = 0xcbf29ce484222325L;
		for (byte value : seed.getBytes(StandardCharsets.UTF_8)) {
			hash ^= value & 0xFF;
			hash *= 0x100000001b3L;
		}
		return hash;
	}

	private static long mix(long value) {
		long z = value;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private static double toUnit(long value) {
		return (value >>> 11) * 0x1.0p-53;
	}
}
