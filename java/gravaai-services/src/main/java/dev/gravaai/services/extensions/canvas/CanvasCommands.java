package dev.gravaai.services.extensions.canvas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

/**
 * A batched command stream for {@link Canvas2D}: JS writes opcodes and numbers into a
 * {@code Float64Array} over this direct buffer and the host replays them in one call. Without
 * the Graal JIT every JS-to-host call costs microseconds; a 200-particle component makes ~1,200
 * of them per frame, so batching is what keeps elements inside the frame budget.
 *
 * <p>The opcode numbers are mirrored in the element prelude ({@code OP} table); keep both in sync.
 * Strings travel in a side array, referenced by index.
 */
public final class CanvasCommands {

	/** Capacity in doubles; the prelude flushes before it would overflow. */
	public static final int CAPACITY = 1 << 15;

	static final int SAVE = 1, RESTORE = 2, TRANSLATE = 3, ROTATE = 4, SCALE = 5, TRANSFORM = 6,
			SET_TRANSFORM = 7, RESET_TRANSFORM = 8, BEGIN_PATH = 9, CLOSE_PATH = 10, MOVE_TO = 11,
			LINE_TO = 12, QUADRATIC = 13, BEZIER = 14, ARC = 15, ARC_CCW = 16, RECT = 17, FILL = 18,
			STROKE = 19, CLIP = 20, FILL_RECT = 21, STROKE_RECT = 22, CLEAR_RECT = 23,
			FILL_TEXT = 24, STROKE_TEXT = 25, ELLIPSE = 26, ELLIPSE_CCW = 27, ROUND_RECT = 28;
	static final int LINE_WIDTH = 40, GLOBAL_ALPHA = 41, SHADOW_BLUR = 42, SHADOW_X = 43,
			SHADOW_Y = 44, MITER_LIMIT = 45, LINE_DASH_OFFSET = 46;
	static final int FILL_STYLE = 50, STROKE_STYLE = 51, FONT = 52, TEXT_ALIGN = 53,
			TEXT_BASELINE = 54, LINE_CAP = 55, LINE_JOIN = 56, COMPOSITE = 57, SHADOW_COLOR = 58,
			FILTER = 59;

	private final ByteBuffer buffer = ByteBuffer.allocateDirect(CAPACITY * Double.BYTES)
			.order(ByteOrder.nativeOrder());

	/** The shared memory the prelude wraps in a {@code Float64Array}. */
	@HostAccess.Export
	public ByteBuffer buffer() {
		return buffer;
	}

	@HostAccess.Export
	public int capacity() {
		return CAPACITY;
	}

	/** Replays {@code length} doubles of commands onto {@code ctx}. */
	@HostAccess.Export
	public void run(Canvas2D ctx, int length, Value strings) {
		int end = Math.min(length, CAPACITY);
		int at = 0;
		while (at < end) {
			int op = (int) d(at++);
			switch (op) {
				case SAVE -> ctx.save();
				case RESTORE -> ctx.restore();
				case TRANSLATE -> { ctx.translate(d(at), d(at + 1)); at += 2; }
				case ROTATE -> { ctx.rotate(d(at)); at += 1; }
				case SCALE -> { ctx.scale(d(at), d(at + 1)); at += 2; }
				case TRANSFORM -> {
					ctx.transform(d(at), d(at + 1), d(at + 2), d(at + 3), d(at + 4), d(at + 5));
					at += 6;
				}
				case SET_TRANSFORM -> {
					ctx.setTransform(d(at), d(at + 1), d(at + 2), d(at + 3), d(at + 4), d(at + 5));
					at += 6;
				}
				case RESET_TRANSFORM -> ctx.resetTransform();
				case BEGIN_PATH -> ctx.beginPath();
				case CLOSE_PATH -> ctx.closePath();
				case MOVE_TO -> { ctx.moveTo(d(at), d(at + 1)); at += 2; }
				case LINE_TO -> { ctx.lineTo(d(at), d(at + 1)); at += 2; }
				case QUADRATIC -> { ctx.quadraticCurveTo(d(at), d(at + 1), d(at + 2), d(at + 3)); at += 4; }
				case BEZIER -> {
					ctx.bezierCurveTo(d(at), d(at + 1), d(at + 2), d(at + 3), d(at + 4), d(at + 5));
					at += 6;
				}
				case ARC -> { ctx.arc(d(at), d(at + 1), d(at + 2), d(at + 3), d(at + 4)); at += 5; }
				case ARC_CCW -> {
					ctx.arc(d(at), d(at + 1), d(at + 2), d(at + 3), d(at + 4), d(at + 5) != 0);
					at += 6;
				}
				case RECT -> { ctx.rect(d(at), d(at + 1), d(at + 2), d(at + 3)); at += 4; }
				case FILL -> ctx.fill();
				case STROKE -> ctx.stroke();
				case CLIP -> ctx.clip();
				case FILL_RECT -> { ctx.fillRect(d(at), d(at + 1), d(at + 2), d(at + 3)); at += 4; }
				case STROKE_RECT -> { ctx.strokeRect(d(at), d(at + 1), d(at + 2), d(at + 3)); at += 4; }
				case CLEAR_RECT -> { ctx.clearRect(d(at), d(at + 1), d(at + 2), d(at + 3)); at += 4; }
				case FILL_TEXT -> { ctx.fillText(s(strings, at), d(at + 1), d(at + 2)); at += 3; }
				case STROKE_TEXT -> { ctx.strokeText(s(strings, at), d(at + 1), d(at + 2)); at += 3; }
				case ELLIPSE -> {
					ctx.ellipse(d(at), d(at + 1), d(at + 2), d(at + 3), d(at + 4), d(at + 5), d(at + 6));
					at += 7;
				}
				case ELLIPSE_CCW -> {
					ctx.ellipse(d(at), d(at + 1), d(at + 2), d(at + 3), d(at + 4), d(at + 5), d(at + 6),
							d(at + 7) != 0);
					at += 8;
				}
				case ROUND_RECT -> {
					ctx.roundRect(d(at), d(at + 1), d(at + 2), d(at + 3), Value.asValue(d(at + 4)));
					at += 5;
				}
				case LINE_WIDTH -> ctx.setLineWidth(d(at++));
				case GLOBAL_ALPHA -> ctx.setGlobalAlpha(d(at++));
				case SHADOW_BLUR -> ctx.setShadowBlur(d(at++));
				case SHADOW_X -> ctx.setShadowOffsetX(d(at++));
				case SHADOW_Y -> ctx.setShadowOffsetY(d(at++));
				case MITER_LIMIT -> ctx.setMiterLimit(d(at++));
				case LINE_DASH_OFFSET -> ctx.setLineDashOffset(d(at++));
				case FILL_STYLE -> ctx.setFillStyle(s(strings, at++));
				case STROKE_STYLE -> ctx.setStrokeStyle(s(strings, at++));
				case FONT -> ctx.setFont(s(strings, at++));
				case TEXT_ALIGN -> ctx.setTextAlign(s(strings, at++));
				case TEXT_BASELINE -> ctx.setTextBaseline(s(strings, at++));
				case LINE_CAP -> ctx.setLineCap(s(strings, at++));
				case LINE_JOIN -> ctx.setLineJoin(s(strings, at++));
				case COMPOSITE -> ctx.setGlobalCompositeOperation(s(strings, at++));
				case SHADOW_COLOR -> ctx.setShadowColor(s(strings, at++));
				case FILTER -> ctx.setFilter(s(strings, at++));
				default -> throw new IllegalStateException("unknown canvas command " + op + " at " + (at - 1));
			}
		}
	}

	private double d(int index) {
		return buffer.getDouble(index * Double.BYTES);
	}

	private String s(Value strings, int index) {
		return strings.getArrayElement((long) d(index)).asString();
	}
}
