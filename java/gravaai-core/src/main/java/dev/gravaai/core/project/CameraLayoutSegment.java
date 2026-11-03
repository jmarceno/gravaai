package dev.gravaai.core.project;

import java.util.List;
import java.util.Optional;

/**
 * A stretch of the timeline where the camera uses {@code layout} instead of the project's webcam
 * settings (the Camera layout track). Segments never overlap; the gaps between them use the
 * project layout.
 */
public record CameraLayoutSegment(String id, long startMs, long endMs, CameraLayout layout) {

	public CameraLayoutSegment {
		if (id.isBlank()) {
			throw new IllegalArgumentException("camera layout segment id must not be blank");
		}
		if (endMs < startMs) {
			endMs = startMs;
		}
	}

	public CameraLayoutSegment withSpan(long nextStartMs, long nextEndMs) {
		return new CameraLayoutSegment(id, nextStartMs, nextEndMs, layout);
	}

	public CameraLayoutSegment withLayout(CameraLayout nextLayout) {
		return new CameraLayoutSegment(id, startMs, endMs, nextLayout);
	}

	public boolean contains(double timeMs) {
		return timeMs >= startMs && timeMs < endMs;
	}

	/** The segment covering a timeline time (segments are kept in time order, non-overlapping). */
	public static Optional<CameraLayoutSegment> at(List<CameraLayoutSegment> segments,
			double timeMs) {
		for (CameraLayoutSegment segment : segments) {
			if (segment.contains(timeMs)) {
				return Optional.of(segment);
			}
		}
		return Optional.empty();
	}
}
