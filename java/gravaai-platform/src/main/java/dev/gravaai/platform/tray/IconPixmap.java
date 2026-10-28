package dev.gravaai.platform.tray;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;

/**
 * {@code (iiay)} icon pixmap of the StatusNotifierItem specification: width, height and ARGB32
 * pixels in network byte order.
 */
public final class IconPixmap extends Struct {

	@Position(0)
	public final int width;

	@Position(1)
	public final int height;

	@Position(2)
	public final byte[] data;

	public IconPixmap(int width, int height, byte[] data) {
		this.width = width;
		this.height = height;
		this.data = data.clone();
	}
}
