package dev.gravaai.services.extensions;

import java.nio.file.Path;

/**
 * One extension as the extension manager sees it: identity, state and the last error.
 *
 * @param manifest null when the manifest could not be read/validated
 * @param error null unless {@code state} is {@link ExtensionState#FAILED}
 */
public record ExtensionInfo(
		String id, Path root, ExtensionManifest manifest, ExtensionState state, String error) {}
