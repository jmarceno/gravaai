/**
 * Linux desktop-environment and compositor differences: the session classifier and the per-desktop
 * profile it resolves to.
 *
 * <p>This package is the only place {@code XDG_CURRENT_DESKTOP}, {@code XDG_SESSION_TYPE},
 * {@code WAYLAND_DISPLAY} and {@code DISPLAY} are interpreted. Everything downstream asks a
 * {@link dev.gravaai.platform.compositor.DesktopSession} what the session is, so there is one
 * answer instead of one per call site.
 */
@org.jspecify.annotations.NullMarked
package dev.gravaai.platform.compositor;
