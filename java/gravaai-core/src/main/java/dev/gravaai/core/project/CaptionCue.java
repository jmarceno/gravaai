package dev.gravaai.core.project;

import java.util.List;

/** One caption cue (from whisper or manual edits). */
public record CaptionCue(String id, long startMs, long endMs, String text, List<CaptionCueWord> words) {}
