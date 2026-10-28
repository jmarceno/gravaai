package dev.gravaai.services.recording;

/** One output reported by {@code gravaai-wayland-capture --list}, from @{code CosmicOutput} in electron/ipc/recording/cosmic.ts. */
public record CosmicOutput(String name, double x, double y, double width, double height) {
}
