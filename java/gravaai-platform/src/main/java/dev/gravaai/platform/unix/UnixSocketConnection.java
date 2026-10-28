package dev.gravaai.platform.unix;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Newline-delimited text connection on a unix-domain socket.
 *
 * <p>The control socket (plan §5.17) answers one action per line with {@code ok}/{@code error},
 * and the agent socket (plan §5.16) speaks newline-delimited JSON. Both use this class.
 */
public final class UnixSocketConnection implements AutoCloseable {

	private final SocketChannel channel;
	private final BufferedReader reader;
	private final BufferedWriter writer;

	UnixSocketConnection(SocketChannel channel) {
		this.channel = channel;
		this.reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
				StandardCharsets.UTF_8));
		this.writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel),
				StandardCharsets.UTF_8));
	}

	/** Reads one line, or {@code null} at end of stream. */
	public String readLine() throws IOException {
		return reader.readLine();
	}

	/** Writes one line (without the trailing newline) and flushes. */
	public void writeLine(String line) throws IOException {
		writer.write(line);
		writer.write('\n');
		writer.flush();
	}

	/** Raw input, for protocols that do not use lines. */
	public InputStream inputStream() {
		return Channels.newInputStream(channel);
	}

	/** Raw output, for protocols that do not use lines. */
	public OutputStream outputStream() {
		return Channels.newOutputStream(channel);
	}

	/** The underlying channel. */
	public SocketChannel channel() {
		return channel;
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
