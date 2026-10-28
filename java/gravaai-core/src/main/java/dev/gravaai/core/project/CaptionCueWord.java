package dev.gravaai.core.project;

/** One word of a caption cue, with its source timing. */
public record CaptionCueWord(String text, long startMs, long endMs, Boolean leadingSpace) {}
