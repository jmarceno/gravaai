package dev.gravaai.services.agent;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The per-install AI-control token (port of {@code electron/agent/token.ts}, plan §5.16): 32 random
 * bytes as lowercase hex, stored in {@code agent-token} with mode 0600, compared in constant time.
 */
public final class AgentToken {

	public static final int AGENT_TOKEN_BYTES = 32;

	private static final System.Logger LOG = System.getLogger("gravaai.agent.token");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final HexFormat HEX = HexFormat.of();

	private AgentToken() {}

	/** {@code generateAgentToken}. */
	public static String generate() {
		byte[] bytes = new byte[AGENT_TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return HEX.formatHex(bytes);
	}

	/** {@code readOrCreateAgentToken}: reads the token, creating it (mode 0600) on first use. */
	public static String readOrCreate(Path tokenFile) throws IOException {
		Optional<String> existing = read(tokenFile);
		if (existing.isPresent()) {
			return existing.get();
		}
		String token = generate();
		write(tokenFile, token);
		return token;
	}

	/** {@code writeAgentToken}. */
	public static void write(Path tokenFile, String token) throws IOException {
		Path parent = tokenFile.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(tokenFile, token, StandardCharsets.UTF_8);
		try {
			Files.setPosixFilePermissions(tokenFile,
					PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not chmod the agent token: " + error);
		}
	}

	/** {@code readAgentToken}: the trimmed token, or empty when missing/blank. */
	public static Optional<String> read(Path tokenFile) {
		try {
			String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
			return token.isEmpty() ? Optional.empty() : Optional.of(token);
		} catch (IOException error) {
			return Optional.empty();
		}
	}

	/** {@code tokensMatch}: constant-time comparison of equal-length UTF-8 tokens. */
	public static boolean tokensMatch(String expected, @Nullable Object received) {
		if (!(received instanceof String text) || text.length() != expected.length()) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				text.getBytes(StandardCharsets.UTF_8));
	}
}
