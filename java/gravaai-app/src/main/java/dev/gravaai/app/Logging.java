package dev.gravaai.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Logging (plan §5.1): {@link System.Logger} backed by JUL, one rotating file handler at
 * {@code <userData>/logs/gravaai.log} (5 × 2 MB) plus stderr.
 *
 * <p>stderr, never stdout: {@code --mcp} mode uses stdout for JSON-RPC.
 */
public final class Logging {

	private static final int LIMIT_BYTES = 2 * 1024 * 1024;
	private static final int FILE_COUNT = 5;

	private Logging() {}

	public static void configure(Path logFile) {
		Logger root = Logger.getLogger("");
		root.setLevel(Level.INFO);
		for (Handler handler : root.getHandlers()) {
			root.removeHandler(handler);
		}

		Formatter formatter = new LineFormatter();
		try {
			Files.createDirectories(logFile.getParent());
			FileHandler file = new FileHandler(logFile.toString(), LIMIT_BYTES, FILE_COUNT, true);
			file.setEncoding("UTF-8");
			file.setFormatter(formatter);
			root.addHandler(file);
		} catch (IOException | SecurityException error) {
			System.getLogger("gravaai").log(System.Logger.Level.WARNING,
					"Could not open the log file " + logFile + ": " + error);
		}

		ConsoleHandler console = new ConsoleHandler();
		console.setLevel(Level.INFO);
		console.setFormatter(formatter);
		root.addHandler(console);
	}

	private static final class LineFormatter extends Formatter {
		@Override
		public String format(LogRecord record) {
			String thrown = "";
			if (record.getThrown() != null) {
				thrown = "\n" + stackTrace(record.getThrown());
			}
			return String.format("%1$tH:%1$tM:%1$tS.%1$tL %2$-7s %3$s%4$s%n",
					record.getMillis(), record.getLevel().getName(), formatMessage(record), thrown);
		}

		private static String stackTrace(Throwable error) {
			StringBuilder text = new StringBuilder();
			for (StackTraceElement element : error.getStackTrace()) {
				text.append("\tat ").append(element).append('\n');
			}
			return text.toString();
		}
	}
}
