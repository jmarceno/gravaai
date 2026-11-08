package dev.gravaai.services.privacy;

import java.util.List;

/**
 * The PII-detection port. Auto masking depends on this interface, not on GLiNER, so the
 * recognition pass can be exercised in tests with a fake while the real detector lives in
 * {@code gravaai-media} (the module that owns ONNX Runtime).
 *
 * <p>Services never link ONNX directly — the same split the captions service uses for Whisper.</p>
 */
public interface PiiDetector extends AutoCloseable {

	/**
	 * One detected span of {@code text}.
	 *
	 * @param label  the entity type ("person", "email", "phone number", …)
	 * @param start  inclusive character offset into the original text
	 * @param end    exclusive character offset into the original text
	 * @param score  confidence in {@code [0,1]}
	 */
	record PiiSpan(String label, int start, int end, float score) {

		public PiiSpan {
			if (label == null || label.isBlank()) {
				throw new IllegalArgumentException("PII span needs a label");
			}
			if (start < 0 || end <= start) {
				throw new IllegalArgumentException("invalid PII span range: " + start + ".." + end);
			}
			if (score < 0 || score > 1) {
				throw new IllegalArgumentException("PII score out of range: " + score);
			}
		}

		public int length() {
			return end - start;
		}
	}

	/**
	 * Detects PII in one line of text. Returns spans in the text's own character coordinates,
	 * sorted by start offset.
	 */
	List<PiiSpan> detect(String text);

	/** A short label for logs and the inspector's status line. */
	String describe();

	/**
	 * Releases the underlying model. The default is a no-op so a test fake needs no lifecycle;
	 * the GLiNER adapter closes its ORT session.
	 */
	@Override
	default void close() {
		// Nothing to release by default.
	}
}
