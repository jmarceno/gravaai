package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import dev.gravaai.core.project.CropRegion;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Dialog;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * "Crop Video" dialog (port of {@code CropEditorDialog.tsx} + {@code CropControl.tsx}): the
 * current video frame with a 60% black mask outside the crop and four blue edge handles. Each
 * edge drags independently and keeps at least 10% of the frame. Done commits the region, any
 * other close (backdrop, Escape, ×) restores the snapshot taken on open, like the React modal.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class CropEditorDialog {

	private static final double MIN_SPAN = 0.1;

	private enum Edge { TOP, RIGHT, BOTTOM, LEFT }

	private final Dialog dialog;
	private final CropSurface surface = new CropSurface();
	private final Consumer<CropRegion> onDone;
	private CropRegion crop;
	private boolean committed;

	public CropEditorDialog(CropRegion initial, double videoWidth, double videoHeight,
			Consumer<CropRegion> onDone) {
		this.crop = initial == null ? CropRegion.DEFAULT : initial;
		this.onDone = onDone;
		surface.aspect = videoWidth > 0 && videoHeight > 0 ? videoWidth / videoHeight : 16.0 / 9;
		dialog = new Dialog("Crop Video");
		dialog.description("Drag on each side to adjust the crop area");
		dialog.width(1024);
		StackPane holder = new StackPane(surface);
		holder.setPadding(new Insets(32));
		dialog.content(holder);
		Button done = new Button("Done", Button.Variant.DEFAULT, Button.Size.LG);
		done.setOnAction(event -> {
			committed = true;
			dialog.close();
		});
		dialog.footer(done);
		dialog.onClose(() -> {
			if (committed && onDone != null) {
				onDone.accept(crop);
			}
		});
	}

	/** Opens the dialog inside the kit root that owns {@code owner}. */
	public CropEditorDialog show(Node owner) {
		double sceneHeight = owner.getScene() == null ? 800 : owner.getScene().getHeight();
		surface.maxHeight = Math.max(200, sceneHeight * 0.6);
		surface.maxWidth = surface.aspect < 1 ? 400 : 896;
		surface.requestLayout();
		dialog.show(owner);
		return this;
	}

	/** The frame under the mask; may arrive after {@link #show} (decoded off the FX thread). */
	public void setFrame(Image frame) {
		surface.image.setImage(frame);
	}

	public CropRegion region() {
		return crop;
	}

	public Dialog dialog() {
		return dialog;
	}

	/** Top-first RGBA pixels → an FX image (one frame, so a plain int copy is fine). */
	public static WritableImage toImage(int width, int height, ByteBuffer rgba) {
		int[] argb = new int[width * height];
		ByteBuffer pixels = rgba.duplicate();
		pixels.position(0);
		for (int i = 0; i < argb.length; i++) {
			int r = pixels.get() & 0xFF;
			int g = pixels.get() & 0xFF;
			int b = pixels.get() & 0xFF;
			pixels.get();
			argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
		}
		WritableImage image = new WritableImage(width, height);
		image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(),
				argb, 0, width);
		return image;
	}

	/** Pure edge drag (CropControl handlePointerMove), deltas in normalised frame units. */
	static CropRegion drag(@Nullable CropRegion start, String edge, double deltaX, double deltaY) {
		double x = start.x();
		double y = start.y();
		double width = start.width();
		double height = start.height();
		switch (edge) {
			case "top" -> {
				double bottom = start.y() + start.height();
				y = Math.min(Math.max(0, start.y() + deltaY), bottom - MIN_SPAN);
				height = bottom - y;
			}
			case "bottom" -> height = Math.max(MIN_SPAN,
					Math.min(start.height() + deltaY, 1 - start.y()));
			case "left" -> {
				double right = start.x() + start.width();
				x = Math.min(Math.max(0, start.x() + deltaX), right - MIN_SPAN);
				width = right - x;
			}
			case "right" -> width = Math.max(MIN_SPAN,
					Math.min(start.width() + deltaX, 1 - start.x()));
			default -> { }
		}
		return new CropRegion(x, y, width, height);
	}

	private final class CropSurface extends Pane {

		private final ImageView image = new ImageView();
		private final Canvas mask = new Canvas();
		private final Region[] handles = new Region[4];
		private double aspect;
		private double maxWidth = 896;
		private double maxHeight = 480;
		private @Nullable Edge dragging;
		private double startX;
		private double startY;
		private @Nullable CropRegion startCrop;

		CropSurface() {
			setStyle("-fx-background-color: #000000; -fx-background-radius: 8px; "
					+ "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 40, 0, 0, 20);");
			image.setPreserveRatio(false);
			image.setSmooth(true);
			Rectangle clip = new Rectangle();
			clip.setArcWidth(16);
			clip.setArcHeight(16);
			clip.widthProperty().bind(widthProperty());
			clip.heightProperty().bind(heightProperty());
			image.setClip(clip);
			mask.setMouseTransparent(true);
			getChildren().addAll(image, mask);
			for (Edge edge : Edge.values()) {
				Region handle = new Region();
				handle.setCursor(edge == Edge.TOP || edge == Edge.BOTTOM ? Cursor.V_RESIZE
						: Cursor.H_RESIZE);
				// 3px blue bar centred in a wider transparent hit strip.
				handle.setStyle("-fx-background-color: transparent, #2563EB; "
						+ (edge == Edge.TOP || edge == Edge.BOTTOM
								? "-fx-background-insets: 0, 4 0 4 0;"
								: "-fx-background-insets: 0, 0 4 0 4;"));
				handle.setOnMousePressed(event -> begin(edge, event));
				handle.setOnMouseDragged(this::move);
				handle.setOnMouseReleased(event -> dragging = null);
				handles[edge.ordinal()] = handle;
				getChildren().add(handle);
			}
		}

		private void begin(Edge edge, MouseEvent event) {
			if (getWidth() <= 0 || getHeight() <= 0) {
				return;
			}
			dragging = edge;
			javafx.geometry.Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
			startX = local.getX() / getWidth();
			startY = local.getY() / getHeight();
			startCrop = crop;
			event.consume();
		}

		private void move(MouseEvent event) {
			if (dragging == null || getWidth() <= 0 || getHeight() <= 0) {
				return;
			}
			javafx.geometry.Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
			crop = drag(startCrop, dragging.name().toLowerCase(java.util.Locale.ROOT),
					local.getX() / getWidth() - startX, local.getY() / getHeight() - startY);
			requestLayout();
			event.consume();
		}

		@Override
		protected double computePrefWidth(double height) {
			return Math.min(maxWidth, maxHeight * aspect);
		}

		@Override
		protected double computePrefHeight(double width) {
			return computePrefWidth(-1) / aspect;
		}

		@Override
		protected double computeMaxWidth(double height) {
			return computePrefWidth(height);
		}

		@Override
		protected double computeMaxHeight(double width) {
			return computePrefHeight(width);
		}

		@Override
		protected void layoutChildren() {
			double w = getWidth();
			double h = getHeight();
			image.setFitWidth(w);
			image.setFitHeight(h);
			image.relocate(0, 0);
			mask.setWidth(w);
			mask.setHeight(h);
			double cx = crop.x() * w;
			double cy = crop.y() * h;
			double cw = crop.width() * w;
			double ch = crop.height() * h;
			GraphicsContext g = mask.getGraphicsContext2D();
			g.clearRect(0, 0, w, h);
			g.setFill(Color.rgb(0, 0, 0, 0.6));
			g.fillRect(0, 0, w, cy);
			g.fillRect(0, cy + ch, w, h - cy - ch);
			g.fillRect(0, cy, cx, ch);
			g.fillRect(cx + cw, cy, w - cx - cw, ch);
			double strip = 11;
			handles[Edge.TOP.ordinal()].resizeRelocate(cx, cy - strip / 2, cw, strip);
			handles[Edge.BOTTOM.ordinal()].resizeRelocate(cx, cy + ch - strip / 2, cw, strip);
			handles[Edge.LEFT.ordinal()].resizeRelocate(cx - strip / 2, cy, strip, ch);
			handles[Edge.RIGHT.ordinal()].resizeRelocate(cx + cw - strip / 2, cy, strip, ch);
		}
	}
}
