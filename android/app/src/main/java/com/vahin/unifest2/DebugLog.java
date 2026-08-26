package com.vahin.unifest2;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * In-memory ring buffer of the key call-flow checkpoints and bridge to
 * Firebase Crashlytics so crashes and call events are visible in production.
 */
public class DebugLog {
    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
        new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static synchronized void log(String tag, String msg) {
        String line = FMT.format(new Date()) + "  [" + tag + "]  " + msg;
        lines.addLast(line);
        while (lines.size() > MAX_LINES) lines.removeFirst();
        Log.d(tag, msg);
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log("[" + tag + "] " + msg);
        } catch (Exception ignored) {}
    }

    public static void recordException(Throwable throwable) {
        if (throwable == null) return;
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(throwable);
        } catch (Exception ignored) {}
    }

    public static synchronized String getAll() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.length() == 0 ? "(no diagnostic entries recorded yet — trigger a test call first)" : sb.toString();
    }

    public static synchronized void clear() {
        lines.clear();
    }
}
