/**
 * The Linux desktop substrate: process execution, X11, D-Bus (tray, notifications, portals, file
 * manager), trash, native helper lookup, unix sockets and the session classifier.
 *
 * <p>Layout rule: everything that needs to know about the operating system or the desktop
 * environment lives in this module, in the package named after that concern. Higher layers ask
 * these types instead of reading environment variables or probing the system themselves.
 *
 * <ul>
 *   <li>{@link dev.gravaai.platform.compositor} — the single classifier for
 *       {@code XDG_CURRENT_DESKTOP} / {@code XDG_SESSION_TYPE} and the per-desktop profile they
 *       resolve to. Nothing outside that package interprets those variables.</li>
 *   <li>{@code dbus}, {@code portal}, {@code notification}, {@code tray}, {@code filemanager} —
 *       freedesktop services, each degrading quietly when the running desktop lacks one.</li>
 *   <li>{@code x11} — the X11/XWayland bridge (EWMH, input shapes).</li>
 *   <li>{@code process} — {@code ProcessRunner} and {@code ExecutableLookup}.</li>
 *   <li>{@code trash}, {@code nativehelpers}, {@code unix} — the freedesktop trash spec, the
 *       native payload locator, and unix-domain sockets.</li>
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package dev.gravaai.platform;
