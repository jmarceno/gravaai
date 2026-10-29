package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** One caption cue (from whisper or manual edits). */
public record CaptionCue(String id, long startMs, long endMs, String text, @Nullable List<CaptionCueWord> words) {}
