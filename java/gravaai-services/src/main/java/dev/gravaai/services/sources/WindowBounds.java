package dev.gravaai.services.sources;

/** A window's bounds in global display points, from {@code WindowBounds} in electron/ipc/types.ts. */
public record WindowBounds(double x, double y, double width, double height) {
}
