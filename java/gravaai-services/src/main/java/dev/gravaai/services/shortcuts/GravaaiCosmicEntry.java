package dev.gravaai.services.shortcuts;

/**
 * One shortcut Gravaai wants in COSMIC's custom file (port of {@code GravaaiCosmicEntry}).
 */
public record GravaaiCosmicEntry(GlobalShortcutBinding binding, String description, String command) {}
