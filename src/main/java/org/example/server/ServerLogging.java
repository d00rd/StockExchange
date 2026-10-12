package org.example.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Sends server logs to the console and to rotating files in the given directory. */
public final class ServerLogging {
    private ServerLogging() {
    }

    public static void configure(Path directory) throws IOException {
        Files.createDirectories(directory);
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        Formatter formatter = new LineFormatter();
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(formatter);
        FileHandler file = new FileHandler(directory.resolve("server-%g.log").toString(), 10_000_000, 5, true);
        file.setFormatter(formatter);
        file.setEncoding("UTF-8");
        root.addHandler(console);
        root.addHandler(file);
    }

    private static final class LineFormatter extends Formatter {
        private static final DateTimeFormatter TIME =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord r) {
            String name = r.getLoggerName() == null ? "" : r.getLoggerName();
            StringBuilder sb = new StringBuilder()
                    .append(TIME.format(r.getInstant())).append(' ')
                    .append(String.format("%-7s", r.getLevel().getName()))
                    .append(" [").append(name.substring(name.lastIndexOf('.') + 1)).append("] ")
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }
}
