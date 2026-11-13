package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.PushToTalkTelemetry.MutedSpan;

/**
 * Gates the microphone of a push-to-talk recording: the mic is muted unless the global shortcut is
 * held, and the muted stretches are recorded for the {@code .ptt.json} sidecar.
 *
 * <p>The hold comes from the interaction helper's evdev lines ({@code KEY:down}/{@code KEY:up}),
 * which carry press and release on every desktop and arrive here through the cursor-capture
 * session. A desktop's own shortcut delivery is press-only everywhere, so while the key telemetry
 * is unavailable it degrades to a toggle: each shortcut press flips the mic. Once an evdev line of
 * the bound key arrives, the toggle is switched off so the two sources cannot fight.
 *
 * <p>Muting writes silence into the mic's PCM stream (the drain zero-fills chunks), so the file
 * offset stays aligned with the capture clock and the timeline never desyncs. Spans are stamped
 * with the pause-aware capture clock, like the cursor telemetry.
 *
 * <p>Thread-safe: the helper's reader thread feeds key lines, the audio drain reads
 * {@link #isMuted()}, and the recorder configures and takes on its own threads.
 */
public final class MicGate {

	private static final Pattern KEY_LINE = Pattern.compile("^KEY:(down|up):(\\d+)$");

	/** Upper bound matching {@code InkTelemetry.MAX_SESSIONS}: a corrupt state cannot grow forever. */
	static final int MAX_SPANS = 10_000;

	private final LongSupplier clockMs;
	private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

	private volatile boolean active;
	private volatile boolean muted;
	private @Nullable PushToTalkKeys keys;

	private final Set<Integer> heldCodes = new LinkedHashSet<>();
	/** True from the moment the bound combo is held down until a key of it is released. */
	private boolean comboHeld;
	/** True once the helper reported the bound key at all: evdev owns the key, presses just toggle. */
	private boolean sawEvdev;

	private final List<MutedSpan> spans = new ArrayList<>();
	/** Start of the muted stretch being recorded, or -1 while the mic is live. */
	private long openSpanStartMs = -1;

	public MicGate(LongSupplier clockMs) {
		this.clockMs = clockMs;
	}

	/**
	 * Configures the gate for one recording. {@code null} turns it off (the recording does not gate
	 * its mic); otherwise the mic starts muted and a muted span opens once the recording's clock
	 * runs (see {@link #beginRecording()}).
	 */
	public synchronized void configure(@Nullable PushToTalkKeys keys) {
		this.keys = keys;
		active = keys != null;
		muted = active;
		heldCodes.clear();
		comboHeld = false;
		sawEvdev = false;
		spans.clear();
		openSpanStartMs = -1;
	}

	/**
	 * The recording's capture clock is running: open the first muted span. Called from the capture
	 * session's start, after the clock was reset, so the span starts at the recording's zero.
	 */
	public synchronized void beginRecording() {
		if (active && muted && openSpanStartMs < 0) {
			openSpanStartMs = clockMs.getAsLong();
		}
	}

	/** True while this recording gates its microphone. */
	public boolean isActive() {
		return active;
	}

	/** True while the mic is muted (silence is being written). */
	public boolean isMuted() {
		return muted;
	}

	/**
	 * Subscribes to mute flips ({@code true} = mic muted). Called from the key-line reader thread
	 * and the recorder's threads; the listener hops itself where it needs to.
	 */
	public AutoCloseable onMutedChanged(Consumer<Boolean> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/**
	 * One helper line. Modifier and key state always keep tracking (so a key released during a
	 * pause cannot stick), but only the bound combo's press and release flip the mic.
	 */
	public synchronized void onKeyLine(String line) {
		Matcher match = KEY_LINE.matcher(line);
		if (!match.matches() || keys == null || !active) {
			return;
		}
		int code = Integer.parseInt(match.group(2));
		if ("up".equals(match.group(1))) {
			heldCodes.remove(code);
			if (comboHeld && (code == keys.keyCode() || EvdevKeys.isModifierCode(code))) {
				comboHeld = false;
				setMuted(true);
			}
			return;
		}
		heldCodes.add(code);
		if (code == keys.keyCode() && heldModifiers().equals(keys.required())) {
			comboHeld = true;
			sawEvdev = true;
			setMuted(false);
		}
	}

	/**
	 * The desktop's shortcut delivery fired (a press): while the key telemetry has never reported
	 * the bound key, a press can only mean a toggle. Once evdev proved available, its release
	 * events own the state and presses are ignored.
	 */
	public synchronized void onShortcutPress() {
		if (active && !sawEvdev) {
			setMuted(!muted);
		}
	}

	/** Takes the muted spans and switches the gate off (the recording is over). */
	public synchronized PushToTalkTelemetry.Recording take() {
		closeOpenSpan();
		PushToTalkTelemetry.Recording recording = new PushToTalkTelemetry.Recording(
				List.copyOf(spans));
		spans.clear();
		active = false;
		comboHeld = false;
		heldCodes.clear();
		return recording;
	}

	private Set<KeyModifier> heldModifiers() {
		Set<KeyModifier> held = new LinkedHashSet<>();
		for (int code : heldCodes) {
			KeyModifier modifier = EvdevKeys.MODIFIER_CODES.get(code);
			if (modifier != null) {
				held.add(modifier);
			}
		}
		return held;
	}

	/** Flips the mute state and opens or closes the muted span with it. */
	private void setMuted(boolean value) {
		boolean changed = value != muted;
		muted = value;
		if (value) {
			if (openSpanStartMs < 0) {
				openSpanStartMs = clockMs.getAsLong();
			}
		} else {
			closeOpenSpan();
		}
		if (changed) {
			publish(value);
		}
	}

	private void closeOpenSpan() {
		if (openSpanStartMs < 0) {
			return;
		}
		long end = Math.max(openSpanStartMs, clockMs.getAsLong());
		if (end > openSpanStartMs && spans.size() < MAX_SPANS) {
			spans.add(new MutedSpan(openSpanStartMs, end));
		}
		openSpanStartMs = -1;
	}

	private void publish(boolean mutedNow) {
		for (Consumer<Boolean> listener : listeners) {
			try {
				listener.accept(mutedNow);
			} catch (RuntimeException error) {
				// A broken listener must not break the audio path.
			}
		}
	}
}
