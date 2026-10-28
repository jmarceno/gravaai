package dev.gravaai.core.project;

/** A removed gap on the timeline (legacy representation, complement of the clips). */
public record TrimRegion(String id, long startMs, long endMs) {}
