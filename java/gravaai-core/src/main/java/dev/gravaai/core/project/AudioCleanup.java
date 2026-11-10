package dev.gravaai.core.project;

import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Edit-time audio cleanup of one source-audio track: noise reduction strength, a simple high-pass
 * and a loudness target for the programme. Cleanup is stored on the same track entry as
 * {@code volume}/{@code normalize} ({@link SourceAudioTrackSettings.Track}) and is applied
 * non-destructively: the recording files are never rewritten.
 *
 * <p>{@code 0} means "off" for both numeric fields (null is never stored), so a default instance is
 * {@link #NONE}. The typed view clamps invalid input, exactly like the volume resolver.
 */
public record AudioCleanup(NoiseReduction noiseReduction, int highPassHz,
		double loudnessTargetLufs) {

	/** Noise-reduction strength, wire-compatible with the record-time mic filter setting. */
	public enum NoiseReduction {
		OFF("off"),
		LIGHT("light"),
		BALANCED("balanced"),
		STRONG("strong");

		/** The default: the recording is left alone. */
		public static final NoiseReduction DEFAULT = OFF;

		private final String wire;

		NoiseReduction(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		/** The level for a stored wire value, or {@link #DEFAULT} when it is unknown. */
		public static NoiseReduction fromWire(JsonNode value) {
			if (value == null || !value.isString()) {
				return DEFAULT;
			}
			return fromText(value.asString());
		}

		/** The level for a wire word (the inspector's chip values), or {@link #DEFAULT}. */
		public static NoiseReduction fromText(String wire) {
			if (wire == null) {
				return DEFAULT;
			}
			String text = wire.trim().toLowerCase(Locale.ROOT);
			for (NoiseReduction level : values()) {
				if (level.wire.equals(text)) {
					return level;
				}
			}
			return DEFAULT;
		}
	}

	/** The widest high-pass the editor offers (a rumble filter, never a voice band-split). */
	public static final int MAX_HIGH_PASS_HZ = 240;

	/** Loudness targets are EBU R128 programme targets; outside this range is a typo. */
	public static final double MIN_LOUDNESS_LUFS = -30;
	public static final double MAX_LOUDNESS_LUFS = -5;

	/** The streaming/podcast targets the inspector offers. */
	public static final double LOUDNESS_PODCAST_LUFS = -16;
	public static final double LOUDNESS_STREAMING_LUFS = -14;
	public static final double LOUDNESS_BROADCAST_LUFS = -23;

	/** Nothing enabled: the recorder's own audio is used untouched. */
	public static final AudioCleanup NONE = new AudioCleanup(NoiseReduction.OFF, 0, 0);

	public AudioCleanup {
		noiseReduction = noiseReduction == null ? NoiseReduction.DEFAULT : noiseReduction;
		highPassHz = clampHighPass(highPassHz);
		loudnessTargetLufs = clampLoudness(loudnessTargetLufs);
	}

	/** True when any stage would touch the audio. */
	public boolean isActive() {
		return noiseReduction != NoiseReduction.OFF || highPassHz > 0 || hasLoudnessTarget();
	}

	/** True when the programme mix is normalized to {@link #loudnessTargetLufs()} on export. */
	public boolean hasLoudnessTarget() {
		return loudnessTargetLufs < 0;
	}

	/** True when the track needs a high-pass stage. */
	public boolean hasHighPass() {
		return highPassHz > 0;
	}

	public AudioCleanup withNoiseReduction(NoiseReduction level) {
		return new AudioCleanup(level, highPassHz, loudnessTargetLufs);
	}

	public AudioCleanup withHighPassHz(int hertz) {
		return new AudioCleanup(noiseReduction, hertz, loudnessTargetLufs);
	}

	public AudioCleanup withLoudnessTargetLufs(double lufs) {
		return new AudioCleanup(noiseReduction, highPassHz, lufs);
	}

	/** A copy with every stage off, keeping no field of this one. */
	public static AudioCleanup off() {
		return NONE;
	}

	/** Clamps a high-pass frequency into {@code 0..MAX_HIGH_PASS_HZ}. */
	public static int clampHighPass(int hertz) {
		return Math.max(0, Math.min(MAX_HIGH_PASS_HZ, hertz));
	}

	/** Clamps a loudness target: 0 stays "off", anything finite lands in the accepted range. */
	public static double clampLoudness(double lufs) {
		if (!Double.isFinite(lufs) || lufs == 0) {
			return 0;
		}
		return Math.max(MIN_LOUDNESS_LUFS, Math.min(MAX_LOUDNESS_LUFS, lufs));
	}

	/** Parses one track entry's cleanup; missing or invalid fields fall back to {@link #NONE}. */
	public static AudioCleanup parse(JsonNode rawTrack) {
		if (rawTrack == null || !rawTrack.isObject()) {
			return NONE;
		}
		NoiseReduction level = NoiseReduction.fromWire(rawTrack.get("noiseReduction"));
		int highPass = rawTrack.path("highPassHz").isNumber()
				? (int) Math.round(rawTrack.get("highPassHz").doubleValue()) : 0;
		double lufs = rawTrack.path("loudnessLufs").isNumber()
				? rawTrack.get("loudnessLufs").doubleValue() : 0;
		return new AudioCleanup(level, highPass, lufs);
	}

	/**
	 * Writes the cleanup fields into {@code target}: the three keys when a stage is active, removed
	 * otherwise, so a project that never used cleanup keeps exactly the keys it had.
	 */
	public void writeTo(ObjectNode target) {
		if (target == null) {
			return;
		}
		if (!isActive()) {
			target.remove("noiseReduction");
			target.remove("highPassHz");
			target.remove("loudnessLufs");
			return;
		}
		target.put("noiseReduction", noiseReduction.wire());
		if (highPassHz > 0) {
			target.put("highPassHz", highPassHz);
		} else {
			target.remove("highPassHz");
		}
		if (hasLoudnessTarget()) {
			target.put("loudnessLufs", loudnessTargetLufs);
		} else {
			target.remove("loudnessLufs");
		}
	}
}
