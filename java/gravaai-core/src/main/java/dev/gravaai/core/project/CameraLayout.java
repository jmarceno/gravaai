package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The camera layout a {@link CameraLayoutSegment} switches to: a webcam position preset (a corner
 * or edge bubble, or a split screen side), the camera full frame, or no camera. The wire is the
 * preset wire for positions, {@code camera-only} or {@code hide-camera} otherwise.
 */
public sealed interface CameraLayout {

	/** The webcam drawn at a position preset (bubble or split screen). */
	record Position(WebcamPositionPreset preset) implements CameraLayout {
		@Override
		public String wire() {
			return preset.wire();
		}

		@Override
		public String label() {
			return switch (preset) {
				case SPLIT_RIGHT -> "Split right";
				case SPLIT_LEFT -> "Split left";
				case SPLIT_TOP -> "Split top";
				case SPLIT_BOTTOM -> "Split bottom";
				case CUSTOM -> "Custom position";
				default -> {
					String wire = preset.wire().replace('-', ' ');
					yield Character.toUpperCase(wire.charAt(0)) + wire.substring(1);
				}
			};
		}
	}

	/** The camera full frame, the screen hidden. */
	record CameraOnly() implements CameraLayout {
		@Override
		public String wire() {
			return "camera-only";
		}

		@Override
		public String label() {
			return "Camera only";
		}
	}

	/** The screen only, the camera hidden. */
	record HideCamera() implements CameraLayout {
		@Override
		public String wire() {
			return "hide-camera";
		}

		@Override
		public String label() {
			return "No camera";
		}
	}

	String wire();

	/** The short name the timeline block and the layout menus show. */
	String label();

	/** Every choice in menu order: splits, then bubbles, then camera only and no camera. */
	static List<CameraLayout> all() {
		List<CameraLayout> layouts = new ArrayList<>();
		for (WebcamPositionPreset preset : WebcamPositionPreset.values()) {
			if (preset.isSplit()) {
				layouts.add(new Position(preset));
			}
		}
		for (WebcamPositionPreset preset : WebcamPositionPreset.values()) {
			if (!preset.isSplit() && preset != WebcamPositionPreset.CUSTOM) {
				layouts.add(new Position(preset));
			}
		}
		layouts.add(new CameraOnly());
		layouts.add(new HideCamera());
		return List.copyOf(layouts);
	}

	static Optional<CameraLayout> fromWire(String wire) {
		return switch (wire) {
			case "camera-only" -> Optional.of(new CameraOnly());
			case "hide-camera" -> Optional.of(new HideCamera());
			default -> Optional.ofNullable(WebcamPositionPreset.fromWireOrNull(wire))
					.map(Position::new);
		};
	}
}
