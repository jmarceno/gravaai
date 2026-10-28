package dev.gravaai.media.compose;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.FilterMipmap;
import io.github.humbleui.skija.FilterMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.MipmapMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import dev.gravaai.media.gl.SkiaGlSurface;

/**
 * The single compositor used by both preview and export (plan §5.8), so the two can never
 * disagree.
 *
 * <p>Render order is ported from {@code modernFrameRenderer.renderFrame/renderOutput} and grows
 * stage by stage, each stage landing with golden-frame tests before the next one:</p>
 *
 * <ol>
 *   <li>background: wallpaper image (cover fit) or colour/gradient, plus background blur</li>
 *   <li>layout: aspect ratio, padding, crop ({@link LayoutUtils} port)</li>
 *   <li>zoom/camera transform</li>
 *   <li>video layer: squircle mask + drop shadow</li>
 *   <li>motion blur (SkSL RuntimeEffect ports of the pixi zoom-blur/motion-blur)</li>
 *   <li>cursor</li>
 *   <li>webcam overlay</li>
 *   <li>annotations</li>
 *   <li>captions</li>
 *   <li>keystroke overlay</li>
 *   <li>clip transitions</li>
 *   <li>extension render hooks (Phase 3): post-video/post-zoom/post-cursor inside the scene
 *       transform, post-webcam/post-annotations after it, and final as the last pass
 *       ({@link #setOverlayHooks})</li>
 * </ol>
 *
 * <p>Implemented so far: stage 1 (colour, gradient, image wallpaper, proportional background
 * blur with overscan), stage 2's padding/crop placement via {@link LayoutUtils} (the render
 * target defines the aspect ratio) and stage 4's plain video layer (bilinear sampling). The
 * pre-rendered background is cached and only rebuilt when the background object, blur or size
 * changes.</p>
 *
 * <p>Threading: created once per GL context (one for preview, one for export) and used only on
 * that context's thread. The caller flushes the surface after {@link #render}; this class
 * flushes as well so the PBO readback sees the frame and raster images can be released.</p>
 */
public final class Compositor implements AutoCloseable {
	/**
	 * The webcam is drawn several times smaller than its source, so it samples the mipmapped
	 * texture trilinearly; plain bilinear skips texels and shimmers.
	 */
	private static final SamplingMode WEBCAM_SAMPLING = new FilterMipmap(FilterMode.LINEAR,
			MipmapMode.LINEAR);

	private static final System.Logger LOG = System.getLogger(Compositor.class.getName());

	private final ColorSpace colorSpace;
	private int width;
	private int height;
	private Background cachedBackground;
	private double cachedBackgroundBlur = -1;
	private Image cachedBackgroundImage;
	private int cachedWidth = -1;
	private int cachedHeight = -1;
	// Stage 3 camera animation state: springs are advanced per rendered frame using content
	// time deltas, exactly like modernFrameRenderer.updateAnimationState.
	private final SpringSmoothing.SpringState zoomSpringScale = SpringSmoothing.createSpringState();
	private final SpringSmoothing.SpringState zoomSpringX = SpringSmoothing.createSpringState();
	private final SpringSmoothing.SpringState zoomSpringY = SpringSmoothing.createSpringState();
	private final CursorFollowCamera.CursorFollowCameraState cursorFollowCamera =
			CursorFollowCamera.createCursorFollowCameraState();
	private Double lastContentTimeMs;
	private volatile ZoomTransform.AppliedTransform lastAppliedTransform =
			new ZoomTransform.AppliedTransform(1, 0, 0);
	// Stage 5: zoom motion blur. The state mirrors the TS motion-blur frame history; the effect
	// is consumed by the SkSL shaders over an offscreen capture of the video layer.
	private final ZoomTransform.MotionBlurState motionBlurState =
			ZoomTransform.createMotionBlurState();
	private volatile MotionBlur.Effect lastMotionBlurEffect = MotionBlur.Effect.NONE;
	// Stage 8: the video mask rect is the annotation coordinate rect (TS layoutCache.maskRect).
	private volatile Rect lastMaskRect;
	private Surface blurSurface;
	// Persistent GL textures for the screen and webcam frames (no per-frame copies or allocations).
	private final GpuFrameTextures frameTextures = new GpuFrameTextures();
	private SkiaGlSurface currentTarget;
	private final CursorRenderer cursorRenderer = new CursorRenderer();
	// Extension render hooks (Phase 3, plan §5.18); NONE keeps the frame byte-identical.
	private volatile FrameOverlayHooks overlayHooks = FrameOverlayHooks.NONE;
	private boolean closed;

	/** Creates a compositor for a render target size. */
	public Compositor(int width, int height) {
		validateSize(width, height);
		this.width = width;
		this.height = height;
		this.colorSpace = ColorSpace.getSRGB();
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/**
	 * Resets the stateful render timeline (zoom/cursor-follow springs, motion-blur history, cursor
	 * animation, annotation coordinate cache) so a fresh render pass starts from frame 0. The
	 * compositor-backed {@code ExportFrameProvider} calls this from its {@code reset()} between the
	 * GIF palette and output passes; a new export can reuse one compositor instead of recreating it.
	 */
	public void reset() {
		resetZoomState();
		resetMotionBlur();
		lastMaskRect = null;
		cursorRenderer.reset();
	}

	/** Changes the output size; the caller recreates the render target and Skia surface. */
	public void resize(int width, int height) {
		validateSize(width, height);
		this.width = width;
		this.height = height;
		invalidateBackgroundCache();
		resetZoomState();
		resetMotionBlur();
		lastMaskRect = null;
		cursorRenderer.reset();
	}

	/**
	 * Installs the extension render hooks (plan §5.18). The compositor and its canvas belong to one
	 * GL thread, so the adapter must tolerate being called from that thread only; the media layer
	 * stays independent of the extension host. Passing null restores {@link FrameOverlayHooks#NONE},
	 * which skips every hook dispatch and leaves the rendered frame byte-identical.
	 */
	public void setOverlayHooks(FrameOverlayHooks hooks) {
		this.overlayHooks = hooks == null ? FrameOverlayHooks.NONE : hooks;
	}

	/** The installed extension render hooks (tests/diagnostics). */
	FrameOverlayHooks overlayHooks() {
		return overlayHooks;
	}

	/**
	 * Renders one frame into the target surface.
	 *
	 * @param target the Skia surface over the compositor's framebuffer; its size must match this
	 *        compositor
	 * @param input the per-frame input
	 * @throws IllegalArgumentException when the target size differs from the compositor size
	 */
	public void render(SkiaGlSurface target, FrameInput input) {
		if (closed) {
			throw new IllegalStateException("compositor is closed");
		}
		if (target == null || input == null) {
			throw new IllegalArgumentException("target and input must not be null");
		}
		if (target.width() != width || target.height() != height) {
			throw new IllegalArgumentException("render target is " + target.width() + "x"
					+ target.height() + ", compositor is " + width + "x" + height);
		}
		currentTarget = target;
		Canvas canvas = target.canvas();
		// Stage 1: background colour/gradient/image plus the proportional blur.
		renderBackground(canvas, input.background(), input.backgroundBlur());
		lastAppliedTransform = new ZoomTransform.AppliedTransform(1, 0, 0);
		SceneSpec scene = input.scene();
		double screenOpacity = clamp01(scene.screenOpacity());
		// Stage 2 + 3 + 4 (partial): padded/cropped and zoom-transformed video layer without the
		// squircle mask/shadow yet. The per-clip camera layout can fade the screen layer.
		if (input.sourceFrame() != null && screenOpacity > 0) {
			if (screenOpacity < 1) {
				canvas.saveLayerAlpha(Rect.makeWH(width, height),
						(int) Math.round(screenOpacity * 255));
				renderSourceVideo(canvas, input);
				canvas.restore();
			} else {
				renderSourceVideo(canvas, input);
			}
		} else {
			// Extension hooks still dispatch on background-only frames (idle/poster frames); with no
			// video layer the scene transform is the identity.
			dispatchSceneHooks(canvas, input);
		}
		// Stage 7: webcam overlay. It is a sibling of the camera container in the TS scene graph
		// (overlayContainer), so it is drawn outside the camera transform, after the cursor.
		if (input.sourceFrame() != null && input.webcamFrame() != null
				&& input.webcam().isVisibleAt(input.sourceTimeSeconds() * 1000)
				&& scene.cameraOpacity() > 0) {
			renderWebcam(canvas, input);
		}
		// Extension hook: post-webcam runs after the built-in transform is restored.
		dispatchHook(canvas, FrameOverlayHooks.Phase.POST_WEBCAM, input);
		// Stage 8: annotations sit inside the camera transform in the TS scene graph and blur
		// annotations sample the already-rendered frame, so they draw last but before flush.
		if (!input.annotations().isEmpty()) {
			renderAnnotations(canvas, target, input);
		}
		// Extension hook: post-annotations runs after the built-in transform is restored.
		dispatchHook(canvas, FrameOverlayHooks.Phase.POST_ANNOTATIONS, input);
		// Stage 9: captions (TS captionContainer is an overlay sibling, above annotations).
		if (input.captions().enabled() && !input.captions().cues().isEmpty()) {
			CaptionRenderer.draw(canvas, input.captions(), width, height,
					Math.round(input.sourceTimeSeconds() * 1000));
		}
		// Stage 10: key-press chips (TS keystrokeContainer is the topmost overlay sibling).
		if (input.keystrokes().enabled() && !input.keystrokes().presses().isEmpty()) {
			KeystrokeRenderer.draw(canvas, input.keystrokes(), width, height,
					Math.round(input.sourceTimeSeconds() * 1000));
		}
		// Stage 11: fade-through-black clip transitions. The TS fadeOverlay is above every layer.
		double fadeAmount = input.fade().amountAt(input.timeSeconds() * 1000);
		if (fadeAmount > 0) {
			int alpha = (int) Math.round(Math.min(1, fadeAmount) * 255);
			try (Paint paint = new Paint()) {
				paint.setColor(alpha << 24);
				canvas.drawRect(Rect.makeWH(width, height), paint);
			}
		}
		// Extension hook: final is the last pass, for HUD-style overlays above every layer.
		dispatchHook(canvas, FrameOverlayHooks.Phase.FINAL, input);
		// Flush before closing raster images: Skia must not read freed pixels, and the PBO
		// readback that follows in GlPreviewThread needs the frame submitted.
		target.flush();
	}

	// ---- stage 1: background -------------------------------------------------------------

	private void renderBackground(Canvas canvas, Background background, double backgroundBlur) {
		Image image = backgroundImage(background, backgroundBlur);
		canvas.drawImageRect(image,
				Rect.makeXYWH(0, 0, width, height),
				Rect.makeXYWH(0, 0, width, height),
				SamplingMode.LINEAR, null, true);
	}

	private Image backgroundImage(Background background, double backgroundBlur) {
		if (cachedBackgroundImage != null && cachedBackground == background
				&& cachedBackgroundBlur == backgroundBlur && cachedWidth == width
				&& cachedHeight == height) {
			return cachedBackgroundImage;
		}
		invalidateBackgroundCache();
		ImageInfo info = new ImageInfo(width, height, ColorType.RGBA_8888,
				ColorAlphaType.PREMUL, colorSpace);
		Image base;
		Surface surface = Surface.makeRaster(info);
		try {
			drawBackground(surface.getCanvas(), background);
			base = surface.makeImageSnapshot();
		} finally {
			surface.close();
		}
		Image result = base;
		if (backgroundBlur > 0) {
			result = blurBackground(base, info, backgroundBlur);
			base.close();
		}
		cachedBackground = background;
		cachedBackgroundBlur = backgroundBlur;
		cachedBackgroundImage = result;
		cachedWidth = width;
		cachedHeight = height;
		return result;
	}

	private Image blurBackground(Image source, ImageInfo info, double backgroundBlur) {
		SceneEffects.Metrics metrics = SceneEffects.backgroundMetrics(width, backgroundBlur);
		float radius = (float) metrics.backgroundBlurPx();
		int overscan = metrics.backgroundOverscanPx();
		ImageFilter filter = ImageFilter.makeBlur(radius, radius, FilterTileMode.DECAL);
		Surface surface = Surface.makeRaster(info);
		try (Paint paint = new Paint()) {
			paint.setImageFilter(filter);
			// Port of createPreblurredBackgroundCanvas: draw the fitted background stretched by
			// the overscan before blurring, so the edges stay filled.
			surface.getCanvas().drawImageRect(source,
					Rect.makeXYWH(0, 0, width, height),
					Rect.makeXYWH(-overscan, -overscan, width + 2 * overscan,
							height + 2 * overscan),
					SamplingMode.LINEAR, paint, true);
			return surface.makeImageSnapshot();
		} finally {
			surface.close();
			filter.close();
		}
	}

	private void drawBackground(Canvas canvas, Background background) {
		switch (background) {
			case Background.Colour colour -> canvas.drawColor(colour.argb());
			case Background.LinearGradient gradient -> {
				try (Shader shader = Shader.makeLinearGradient(0, 0, 0, height,
						gradient.argbColours(), gradient.positions());
						Paint paint = new Paint()) {
					paint.setShader(shader);
					canvas.drawPaint(paint);
				}
			}
			case Background.RadialGradient gradient -> {
				float radius = Math.max(width, height) / 2f;
				try (Shader shader = Shader.makeRadialGradient(width / 2f, height / 2f, radius,
						gradient.argbColours(), gradient.positions());
						Paint paint = new Paint()) {
					paint.setShader(shader);
					canvas.drawPaint(paint);
				}
			}
			case Background.Image image -> drawBackgroundImage(canvas, image);
			case Background.VideoFrame video -> {
				if (video.frame() == null) {
					canvas.drawColor(Background.BLACK);
					return;
				}
				try (Image image = SkiaImages.fromRgbaFrame(video.frame(), colorSpace)) {
					drawCover(canvas, image);
				}
			}
		}
	}

	private void drawBackgroundImage(Canvas canvas, Background.Image background) {
		Image image = Image.makeFromEncoded(background.encodedImage());
		if (image == null) {
			LOG.log(System.Logger.Level.WARNING,
					"cannot decode background wallpaper; painting black");
			canvas.drawColor(Background.BLACK);
			return;
		}
		try {
			drawCover(canvas, image);
		} finally {
			image.close();
		}
	}

	/** applyCoverLayoutToSprite / drawSourceCoverToCanvas: cover fit, centred. */
	private void drawCover(Canvas canvas, Image image) {
		double scale = Math.max(width / (double) image.getWidth(),
				height / (double) image.getHeight());
		double drawWidth = image.getWidth() * scale;
		double drawHeight = image.getHeight() * scale;
		Rect destination = Rect.makeXYWH((float) ((width - drawWidth) / 2.0),
				(float) ((height - drawHeight) / 2.0), (float) drawWidth, (float) drawHeight);
		canvas.drawImageRect(image,
				Rect.makeXYWH(0, 0, image.getWidth(), image.getHeight()),
				destination, SamplingMode.LINEAR, null, true);
	}

	private void invalidateBackgroundCache() {
		cachedBackground = null;
		cachedBackgroundBlur = -1;
		cachedWidth = -1;
		cachedHeight = -1;
		if (cachedBackgroundImage != null) {
			cachedBackgroundImage.close();
			cachedBackgroundImage = null;
		}
	}

	// ---- stage 8: annotations ------------------------------------------------------------

	/**
	 * Port of {@code renderAnnotations} (annotationRenderer.ts): rects are percentages of the
	 * video mask rect transformed by the camera; text/image/figure draw directly, blur samples the
	 * frame rendered so far (snapshot after stages 1-7) through a Skia blur image filter.
	 */
	private void renderAnnotations(Canvas canvas, SkiaGlSurface target, FrameInput input) {
		long timeMs = Math.round(input.timeSeconds() * 1000);
		Rect coordinateRect = lastMaskRect == null
				? Rect.makeWH(width, height)
				: lastMaskRect;
		Image snapshot = null;
		try {
			if (hasActiveBlur(input.annotations(), timeMs)) {
				snapshot = target.snapshot();
			}
			AnnotationRenderer.draw(canvas, input.annotations(), timeMs, width, height,
					coordinateRect,
					new AnnotationRenderer.Transform(lastAppliedTransform.scale(),
							lastAppliedTransform.x(), lastAppliedTransform.y()),
					annotationScaleFactor(), snapshot);
		} finally {
			if (snapshot != null) {
				snapshot.close();
			}
		}
	}

	private static boolean hasActiveBlur(java.util.List<AnnotationSpec> annotations, long timeMs) {
		for (AnnotationSpec annotation : annotations) {
			if (annotation.type() == AnnotationSpec.Type.BLUR && annotation.isActiveAt(timeMs)) {
				return true;
			}
		}
		return false;
	}

	/** {@code calculateAnnotationScaleFactor}: output size over the 1920x1080 reference. */
	private double annotationScaleFactor() {
		return (width / 1920.0 + height / 1080.0) / 2;
	}

	// ---- stage 7: webcam overlay ---------------------------------------------------------

	/**
	 * Port of {@code modernFrameRenderer.updateWebcamOverlay/applyWebcamLayout} for one frame:
	 * preset/custom position, margin, crop-matched size, {@code reactToZoom} counter-scale,
	 * squircle mask, {@code WEBCAM_SHADOW_LAYER_PROFILES} shadow, mirror and crop, plus the per-clip
	 * camera layout: {@code cameraFill} lerps the box to the full frame (camera-only) and
	 * {@code cameraOpacity} fades the whole overlay.
	 */
	private void renderWebcam(Canvas canvas, FrameInput input) {
		WebcamSpec spec = input.webcam();
		RgbaFrame frame = input.webcamFrame();
		double zoomScale = lastAppliedTransform.scale();
		double margin = WebcamOverlay.scalePixels(spec.margin(), width);
		double widthPercent = spec.width() > 0 ? spec.width() : spec.size();
		double heightPercent = spec.height() > 0 ? spec.height() : spec.size();
		double matchedHeightPercent = WebcamOverlay.cropMatchedHeightPercent(widthPercent,
				heightPercent, (double) frame.width(), (double) frame.height(),
				spec.cropRegion());
		WebcamOverlay.Size size = WebcamOverlay.dimensionsPx(width, height, widthPercent,
				matchedHeightPercent, margin, zoomScale, spec.reactToZoom());
		WebcamOverlay.Position position = WebcamOverlay.position(width, height, size.width(),
				size.height(), margin, spec.positionPreset(), spec.positionX(), spec.positionY(),
				spec.corner());
		double radius = WebcamOverlay.cornerRadiusPx(spec.roundness(), size.width(),
				size.height());
		Rect box = Rect.makeXYWH((float) position.x(), (float) position.y(),
				(float) size.width(), (float) size.height());
		SceneSpec scene = input.scene();
		double fill = clamp01(scene.cameraFill());
		double opacity = clamp01(scene.cameraOpacity());
		if (fill > 0) {
			box = lerpRect(box, Rect.makeWH(width, height), (float) fill);
			radius *= 1 - fill;
		}
		WebcamOverlay.CropRect crop = WebcamOverlay.cropSourceRect(spec.cropRegion(),
				frame.width(), frame.height());
		Rect source = Rect.makeXYWH((float) crop.sx(), (float) crop.sy(), (float) crop.sw(),
				(float) crop.sh());
		boolean layered = opacity < 1;
		if (layered) {
			canvas.saveLayerAlpha(Rect.makeWH(width, height),
					(int) Math.round(opacity * 255));
		}
		try {
			try (Image image = frameTextures.image(currentTarget,
					GpuFrameTextures.Slot.WEBCAM, frame, colorSpace)) {
				drawWebcamShadowLayers(canvas, box, radius, spec.shadow() * (1 - fill),
						Math.min(box.getWidth(), box.getHeight()));
				fillImage(canvas, image, webcamCoverMatrix(source, box, spec.mirror()), box,
						radius, WEBCAM_SAMPLING);
			}
		} finally {
			if (layered) {
				canvas.restore();
			}
		}
	}

	private static double clamp01(double value) {
		return Math.min(1, Math.max(0, value));
	}

	private static Rect lerpRect(Rect from, Rect to, float t) {
		return new Rect(from._left + (to._left - from._left) * t,
				from._top + (to._top - from._top) * t,
				from.getRight() + (to.getRight() - from.getRight()) * t,
				from.getBottom() + (to.getBottom() - from.getBottom()) * t);
	}

	private void drawWebcamShadowLayers(Canvas canvas, Rect box, double radius,
			double shadowValue, double shadowSize) {
		for (WebcamOverlay.ShadowLayer layer : WebcamOverlay.shadowLayers(shadowSize,
				shadowValue)) {
			int alpha = (int) Math.round(layer.alpha() * 255) << 24;
			if (alpha == 0) {
				continue;
			}
			SquircleGeometry.SquircleRect shadowRect = new SquircleGeometry.SquircleRect(
					box._left, box._top + layer.offsetY(), box.getWidth(), box.getHeight(),
					radius);
			try (Path path = SquircleGeometry.toPath(shadowRect); Paint paint = new Paint()) {
				if (path == null) {
					continue;
				}
				paint.setColor(alpha);
				if (layer.blur() > 0) {
					// The TS shadow canvases use ctx.filter = blur(Npx), whose N is the Gaussian
					// standard deviation; a mask filter keeps the outside transparent (no edge clamp).
					try (MaskFilter filter = MaskFilter.makeBlur(FilterBlurMode.NORMAL,
							(float) layer.blur())) {
						paint.setMaskFilter(filter);
						canvas.drawPath(path, paint);
					}
				} else {
					canvas.drawPath(path, paint);
				}
			}
		}
	}

	/**
	 * Image-to-canvas matrix that cover-fits the (cropped) webcam frame into the box, optionally
	 * mirrored around the box centre like the sprite {@code scale.x = -1} path.
	 */
	private static Matrix33 webcamCoverMatrix(Rect source, Rect box, boolean mirror) {
		double scale = Math.max(box.getWidth() / source.getWidth(),
				box.getHeight() / source.getHeight());
		double dx = box._left + (box.getWidth() - source.getWidth() * scale) / 2.0
				- source._left * scale;
		double dy = box._top + (box.getHeight() - source.getHeight() * scale) / 2.0
				- source._top * scale;
		if (mirror) {
			return new Matrix33((float) -scale, 0, (float) (2 * box._left + box.getWidth() - dx),
					0, (float) scale, (float) dy, 0, 0, 1);
		}
		return new Matrix33((float) scale, 0, (float) dx, 0, (float) scale, (float) dy, 0, 0, 1);
	}

	/** Image-to-canvas matrix that stretches {@code source} onto {@code destination}. */
	private static Matrix33 rectToRectMatrix(Rect source, Rect destination) {
		double scaleX = destination.getWidth() / source.getWidth();
		double scaleY = destination.getHeight() / source.getHeight();
		return new Matrix33((float) scaleX, 0, (float) (destination._left - source._left * scaleX),
				0, (float) scaleY, (float) (destination._top - source._top * scaleY), 0, 0, 1);
	}

	/**
	 * Fills the squircle (or plain rectangle) outline of {@code box} with the image through an
	 * anti-aliased shader paint. Coverage comes from the path rasterizer, so the edge is smooth
	 * on every target; a clip is not, as GL targets without MSAA rasterize it hard.
	 */
	private static void fillImage(Canvas canvas, Image image, Matrix33 imageToCanvas, Rect box,
			double radius, SamplingMode sampling) {
		try (Shader shader = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
				sampling, imageToCanvas); Paint paint = new Paint()) {
			paint.setAntiAlias(true);
			paint.setShader(shader);
			if (radius <= 0.5) {
				canvas.drawRect(box, paint);
				return;
			}
			try (Path path = SquircleGeometry.toPath(squircleRect(box, radius))) {
				if (path != null) {
					canvas.drawPath(path, paint);
				}
			}
		}
	}

	// ---- stage 2 + 4 (partial): video layer ----------------------------------------------

	private void renderSourceVideo(Canvas canvas, FrameInput input) {
		RgbaFrame frame = input.sourceFrame();
		LayoutSpec spec = input.layout();
		PaddedLayout layout = LayoutUtils.computePaddedLayout(width, height, spec.padding(),
				null, spec.crop(), frame.width(), frame.height());
		// The camera transform is applied around the cropped content rect (the TS
		// layoutCache.maskRect), while the background stays untransformed.
		Rect baseMask = Rect.makeXYWH((float) layout.centerOffsetX(),
				(float) layout.centerOffsetY(), (float) layout.croppedDisplayWidth(),
				(float) layout.croppedDisplayHeight());
		lastMaskRect = baseMask;
		ZoomTransform.AppliedTransform transform = resolveZoomTransform(input, baseMask);
		lastAppliedTransform = transform;
		Rect source = Rect.makeXYWH((float) layout.cropStartX(), (float) layout.cropStartY(),
				(float) (frame.width() * spec.crop().width()),
				(float) (frame.height() * spec.crop().height()));
		FrameStyle style = input.style();
		double maskRadius = LayoutUtils.scalePreviewBorderRadius(
				layout.croppedDisplayWidth(), layout.croppedDisplayHeight(),
				style.borderRadiusPercent());
		MotionBlur.Effect motionBlur = resolveMotionBlur(input, baseMask, transform);
		lastMotionBlurEffect = motionBlur;
		try (Image image = frameTextures.image(currentTarget, GpuFrameTextures.Slot.SOURCE, frame,
				colorSpace)) {
			if (motionBlur.path() == MotionBlur.Path.NONE) {
				drawVideoScene(canvas, image, source, baseMask, maskRadius, style, transform);
			} else {
				drawMotionBlurredScene(canvas, image, source, baseMask, maskRadius, style,
						transform, motionBlur);
			}
		}
		// Stage 6: the cursor overlay is a sibling of the video layer inside the camera
		// transform (the TS cursorContainer), so it zooms with the camera and is not clipped by
		// the video mask. Extension hooks post-video/post-zoom draw after the video layer and
		// post-cursor after the cursor, all inside the same scene transform.
		canvas.save();
		canvas.translate((float) transform.x(), (float) transform.y());
		canvas.scale((float) transform.scale(), (float) transform.scale());
		dispatchHook(canvas, FrameOverlayHooks.Phase.POST_VIDEO, input);
		dispatchHook(canvas, FrameOverlayHooks.Phase.POST_ZOOM, input);
		cursorRenderer.draw(canvas, baseMask, spec.crop(), input.cursorSamples(),
				input.sourceTimeSeconds() * 1000, input.cursor());
		dispatchHook(canvas, FrameOverlayHooks.Phase.POST_CURSOR, input);
		canvas.restore();
	}

	/**
	 * Dispatches the inside-the-scene-transform extension phases for a frame without a video
	 * layer: the scene transform is the identity then (background-only idle/poster frames).
	 */
	private void dispatchSceneHooks(Canvas canvas, FrameInput input) {
		if (overlayHooks == FrameOverlayHooks.NONE) {
			return;
		}
		ZoomTransform.AppliedTransform transform = lastAppliedTransform;
		canvas.save();
		canvas.translate((float) transform.x(), (float) transform.y());
		canvas.scale((float) transform.scale(), (float) transform.scale());
		try {
			dispatchHook(canvas, FrameOverlayHooks.Phase.POST_VIDEO, input);
			dispatchHook(canvas, FrameOverlayHooks.Phase.POST_ZOOM, input);
			dispatchHook(canvas, FrameOverlayHooks.Phase.POST_CURSOR, input);
		} finally {
			canvas.restore();
		}
	}

	/**
	 * Runs the installed extension hooks for one phase; never throws (a broken extension must not
	 * break preview or export, plan §5.18). {@link FrameOverlayHooks.Phase#BACKGROUND} is reserved
	 * and never dispatched.
	 */
	private void dispatchHook(Canvas canvas, FrameOverlayHooks.Phase phase, FrameInput input) {
		FrameOverlayHooks hooks = overlayHooks;
		if (hooks == FrameOverlayHooks.NONE || !phase.dispatched()) {
			return;
		}
		try {
			hooks.render(phase, canvas,
					new FrameOverlayHooks.FrameInfo(width, height,
							Math.round(input.timeSeconds() * 1000)));
		} catch (RuntimeException error) {
			// The host registry logs and skips individual broken hooks; this is the compositor's
			// last line of defence for adapter failures (and keeps preview/export alive).
			LOG.log(System.Logger.Level.WARNING,
					"[extensions] " + phase.wire() + " hook dispatch failed: " + error);
		}
	}

	private void drawMotionBlurredScene(Canvas canvas, Image image, Rect source, Rect baseMask,
			double maskRadius, FrameStyle style, ZoomTransform.AppliedTransform transform,
			MotionBlur.Effect motionBlur) {
		// Stage 5: the pixi filters run on the whole camera container, so capture the
		// video layer (shadow stack + squircle-masked video) offscreen and blit the blurred
		// result over the background.
		ensureBlurSurface();
		Canvas blurCanvas = blurSurface.getCanvas();
		blurCanvas.clear(0);
		drawVideoScene(blurCanvas, image, source, baseMask, maskRadius, style, transform);
		Image blurredLayer = blurSurface.makeImageSnapshot();
		try {
			Shader shader = motionBlur.path() == MotionBlur.Path.DIRECTIONAL
					? MotionBlurShaders.directional(blurredLayer, motionBlur, width, height)
					: MotionBlurShaders.zoom(blurredLayer, motionBlur, width, height);
			try (Paint paint = new Paint()) {
				paint.setShader(shader);
				canvas.drawRect(Rect.makeWH(width, height), paint);
			} finally {
				shader.close();
			}
		} finally {
			blurredLayer.close();
		}
	}

	/** Draws the camera-transformed video layer (shadow stack + masked video) onto a canvas. */
	private void drawVideoScene(Canvas canvas, Image image, Rect source, Rect baseMask,
			double maskRadius, FrameStyle style, ZoomTransform.AppliedTransform transform) {
		canvas.save();
		canvas.translate((float) transform.x(), (float) transform.y());
		canvas.scale((float) transform.scale(), (float) transform.scale());
		drawVideoShadowLayers(canvas, baseMask, maskRadius, style);
		fillImage(canvas, image, rectToRectMatrix(source, baseMask), baseMask, maskRadius,
				SamplingMode.LINEAR);
		canvas.restore();
	}

	/**
	 * Resolves the stage-5 motion blur parameters from the applied camera transform (the
	 * {@code applyZoomTransform} transformOverride path) and the frame history.
	 */
	private MotionBlur.Effect resolveMotionBlur(FrameInput input, Rect baseMask,
			ZoomTransform.AppliedTransform transform) {
		ZoomSpec zoom = input.zoom();
		double amount = zoom == null ? 0 : zoom.motionBlurAmount();
		if (amount <= 0) {
			MotionBlur.resetMotionBlurState(motionBlurState);
			return MotionBlur.Effect.NONE;
		}
		return MotionBlur.update(motionBlurState, baseMask, transform,
				new RenderTargetSize(width, height), true, amount, MotionBlur.Tuning.DEFAULT,
				input.timeSeconds() * 1000, null, false);
	}

	private void ensureBlurSurface() {
		if (blurSurface == null) {
			// A GPU offscreen surface: the motion-blur capture used to be drawn in software.
			blurSurface = currentTarget.makeOffscreen(width, height);
		}
	}

	private void resetMotionBlur() {
		MotionBlur.resetMotionBlurState(motionBlurState);
		lastMotionBlurEffect = MotionBlur.Effect.NONE;
		if (blurSurface != null) {
			blurSurface.close();
			blurSurface = null;
		}
	}

	/** The motion-blur effect used by the last {@link #render} (tests). */
	MotionBlur.Effect lastMotionBlurEffect() {
		return lastMotionBlurEffect;
	}

	private static SquircleGeometry.SquircleRect squircleRect(Rect rect, double radius) {
		return new SquircleGeometry.SquircleRect(rect._left, rect._top, rect.getWidth(),
				rect.getHeight(), radius);
	}

	/**
	 * Port of {@code modernFrameRenderer.updateVideoShadowLayout}: the three drop-shadow layers
	 * from {@link SceneEffects#shadowLayers(int, double)}, drawn behind the (unclipped) video
	 * layer inside the camera transform so they zoom with it.
	 */
	private void drawVideoShadowLayers(Canvas canvas, Rect baseMask, double maskRadius,
			FrameStyle style) {
		if (!style.showShadow() || style.shadowIntensity() <= 0) {
			return;
		}
		double intensity = Math.min(1, Math.max(0, style.shadowIntensity()));
		for (SceneEffects.ShadowLayer layer : SceneEffects.shadowLayers(width, intensity)) {
			int alpha = (int) Math.round(layer.alpha() * 255) << 24;
			if (alpha == 0) {
				continue;
			}
			SquircleGeometry.SquircleRect shadowRect = new SquircleGeometry.SquircleRect(
					baseMask._left, baseMask._top + layer.offsetY(), baseMask.getWidth(),
					baseMask.getHeight(), maskRadius);
			try (Path path = SquircleGeometry.toPath(shadowRect); Paint paint = new Paint()) {
				if (path == null) {
					continue;
				}
				paint.setColor(alpha);
				if (layer.blur() > 0) {
					// The TS shadow canvases use ctx.filter = blur(Npx), whose N is the Gaussian
					// standard deviation; a mask filter keeps the outside transparent (no edge clamp).
					try (MaskFilter filter = MaskFilter.makeBlur(FilterBlurMode.NORMAL,
							(float) layer.blur())) {
						paint.setMaskFilter(filter);
						canvas.drawPath(path, paint);
					}
				} else {
					canvas.drawPath(path, paint);
				}
			}
		}
	}

	/**
	 * Port of {@code modernFrameRenderer.updateAnimationState}: resolve the scene camera target
	 * for this frame and advance the zoom springs by the content-time delta (or snap in classic
	 * mode).
	 */
	private ZoomTransform.AppliedTransform resolveZoomTransform(FrameInput input, Rect baseMask) {
		ZoomSpec zoom = input.zoom();
		if (zoom == null || !zoom.isActive()) {
			resetZoomState();
			return new ZoomTransform.AppliedTransform(1, 0, 0);
		}
		double timeMs = input.timeSeconds() * 1000;
		SceneMotion.SceneZoomTarget target = SceneMotion.resolveSceneZoomTarget(
				new SceneMotion.SceneZoomRequest(zoom.regions(), timeMs,
						input.sourceTimeSeconds() * 1000,
						zoom.connectZooms(), zoom.zoomInDurationMs(), zoom.zoomOutDurationMs(),
						zoom.zoomClassicMode(), input.cursorSamples(), cursorFollowCamera));
		ZoomTransform.AppliedTransform projected = ZoomTransform.computeZoomTransform(
				new RenderTargetSize(width, height), baseMask, target.scale(), target.progress(),
				target.focus().cx(), target.focus().cy());
		double deltaMs = lastContentTimeMs == null
				? 1000.0 / 60.0
				: timeMs - lastContentTimeMs;
		lastContentTimeMs = timeMs;

		if (zoom.zoomClassicMode()) {
			resetSprings(projected);
			return projected;
		}
		SpringSmoothing.SpringConfig config = SpringSmoothing.getZoomSpringConfig(
				zoom.zoomSmoothness(), zoom.cameraSpringTuning());
		double scale = SpringSmoothing.stepSpringValue(zoomSpringScale, projected.scale(),
				deltaMs, config);
		double x = SpringSmoothing.stepSpringValue(zoomSpringX, projected.x(), deltaMs, config);
		double y = SpringSmoothing.stepSpringValue(zoomSpringY, projected.y(), deltaMs, config);
		return new ZoomTransform.AppliedTransform(scale, x, y);
	}

	private void resetSprings(ZoomTransform.AppliedTransform transform) {
		SpringSmoothing.resetSpringState(zoomSpringScale, transform.scale());
		SpringSmoothing.resetSpringState(zoomSpringX, transform.x());
		SpringSmoothing.resetSpringState(zoomSpringY, transform.y());
	}

	private void resetZoomState() {
		if (lastContentTimeMs != null) {
			SpringSmoothing.resetSpringState(zoomSpringScale, 1.0);
			SpringSmoothing.resetSpringState(zoomSpringX, 0.0);
			SpringSmoothing.resetSpringState(zoomSpringY, 0.0);
		}
		lastContentTimeMs = null;
		CursorFollowCamera.resetCursorFollowCamera(cursorFollowCamera);
	}

	/** The transform applied to the video layer by the last {@link #render} (tests). */
	ZoomTransform.AppliedTransform lastAppliedTransform() {
		return lastAppliedTransform;
	}

	/**
	 * Video-layer geometry of the last {@link #render}: the recording rect and the camera
	 * transform annotations are positioned against. The annotation overlay reads this from the
	 * preview thread; it falls back to the full frame while no source video has been drawn.
	 */
	public CameraGeometry geometry() {
		Rect mask = lastMaskRect;
		ZoomTransform.AppliedTransform transform = lastAppliedTransform;
		if (mask == null || mask.getWidth() <= 0 || mask.getHeight() <= 0) {
			CameraGeometry full = CameraGeometry.fullFrame(width, height);
			return new CameraGeometry(full.maskX(), full.maskY(), full.maskWidth(),
					full.maskHeight(), transform.scale(), transform.x(), transform.y(), width,
					height);
		}
		return new CameraGeometry(mask._left, mask._top, mask.getWidth(), mask.getHeight(),
				transform.scale(), transform.x(), transform.y(), width, height);
	}

	private static void validateSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid compositor size: " + width + "x" + height);
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		invalidateBackgroundCache();
		// GPU resources go with the GL context; release them early only on the GL thread (an owner
		// that closes after its context is gone has nothing left to free).
		if (glCurrentOnThisThread()) {
			frameTextures.close();
			if (blurSurface != null) {
				blurSurface.close();
				blurSurface = null;
			}
		}
		colorSpace.close();
		LOG.log(System.Logger.Level.DEBUG, () -> "compositor closed (" + width + "x" + height + ")");
	}

	private static boolean glCurrentOnThisThread() {
		try {
			org.lwjgl.opengl.GL.getCapabilities();
			return true;
		} catch (IllegalStateException noContext) {
			return false;
		}
	}
}
