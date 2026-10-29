package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** One word of a caption cue, with its source timing. */
public record CaptionCueWord(String text, long startMs, long endMs, @Nullable Boolean leadingSpace) {}
