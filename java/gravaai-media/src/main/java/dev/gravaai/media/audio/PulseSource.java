package dev.gravaai.media.audio;

/**
 * One PulseAudio source (input) as reported by {@code pactl -f json list sources}.
 *
 * @param name the source name, usable with {@link PulseAudio#openRecord(String)} and
 *        {@code parec -d}
 * @param description the human-readable description
 */
public record PulseSource(String name, String description) {
}
