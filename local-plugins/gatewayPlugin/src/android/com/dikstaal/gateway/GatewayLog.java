package com.dikstaal.gateway;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class GatewayLog {
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private GatewayLog() {
    }

    public static void i(Context context, String tag, String message) {
        Log.i(tag, message);
        append(context, "INFO", tag, message, null);
    }

    public static void w(Context context, String tag, String message) {
        Log.w(tag, message);
        append(context, "WARN", tag, message, null);
    }

    public static void e(Context context, String tag, String message, Throwable error) {
        Log.e(tag, message, error);
        append(context, "ERROR", tag, message, error);
    }

    public static String read(Context context) {
        synchronized (LOCK) {
            try {
                File file = getLogFile(context);
                if (!file.exists()) {
                    return "";
                }

                long length = file.length();
                int readBytes = (int) Math.min(length, GatewayConstants.LOG_MAX_READ_BYTES);
                byte[] buffer = new byte[readBytes];

                FileInputStream input = new FileInputStream(file);
                try {
                    long skip = Math.max(0, length - readBytes);
                    if (skip > 0) {
                        input.skip(skip);
                    }
                    int actual = input.read(buffer);
                    if (actual <= 0) {
                        return "";
                    }
                    return new String(buffer, 0, actual, StandardCharsets.UTF_8);
                } finally {
                    input.close();
                }
            } catch (Exception e) {
                Log.e(GatewayConstants.TAG_SERVICE, "Failed to read gateway log", e);
                return "Failed to read log: " + e.getMessage();
            }
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            try {
                File file = getLogFile(context);
                if (file.exists()) {
                    file.delete();
                }
            } catch (Exception e) {
                Log.e(GatewayConstants.TAG_SERVICE, "Failed to clear gateway log", e);
            }
        }
    }

    private static void append(Context context, String level, String tag, String message, Throwable error) {
        if (context == null) {
            return;
        }

        synchronized (LOCK) {
            FileWriter writer = null;
            try {
                File file = getLogFile(context);
                writer = new FileWriter(file, true);
                writer.write(DATE_FORMAT.format(new Date()));
                writer.write(" ");
                writer.write(level);
                writer.write(" ");
                writer.write(tag);
                writer.write(" - ");
                writer.write(message == null ? "" : message);

                if (error != null) {
                    writer.write(" | ");
                    writer.write(error.toString());
                }
                writer.write("\n");
            } catch (Exception ignored) {
                // Avoid recursive logging failures.
            } finally {
                try {
                    if (writer != null) {
                        writer.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static File getLogFile(Context context) {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, GatewayConstants.LOG_FILE_NAME);
    }
}
