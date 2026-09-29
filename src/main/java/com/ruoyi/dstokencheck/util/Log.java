package com.ruoyi.dstokencheck.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tiny append-only log for startup diagnostics.
 *
 * <p>A logon-started GUI app is invisible by nature: if it exits early or fails to launch, the user
 * sees nothing at all and there is no way to tell "never started" apart from "started and quit".
 * Recording each launch makes that difference checkable.
 *
 * <p>Written to {@code <config dir>/startup.log}. Never throws: logging must not be able to break
 * the app.
 */
public final class Log {

    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 256 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {
    }

    public static File file() {
        return new File(com.ruoyi.dstokencheck.config.AppConfig.directory(), "startup.log");
    }

    /** Appends one timestamped line. */
    public static void write(String message) {
        synchronized (LOCK) {
            try {
                File target = file();
                File dir = target.getParentFile();
                if (dir != null && !dir.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                // Keep growth bounded; a single old file is enough for diagnostics.
                if (target.isFile() && target.length() > MAX_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                String line = LocalDateTime.now().format(STAMP) + "  " + message + System.lineSeparator();
                Files.write(target.toPath(), line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // Diagnostics are best-effort.
            }
        }
    }

    /** Records an exception with its type and message. */
    public static void write(String context, Throwable t) {
        write(context + ": " + (t == null ? "null" : t.getClass().getSimpleName() + ": " + t.getMessage()));
    }
}
