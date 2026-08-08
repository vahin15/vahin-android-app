package com.vahin.unifest;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * A tiny in-memory ring buffer of the key call-flow checkpoints, so the ringing/
 * notification pipeline can be diagnosed FROM INSIDE THE APP — no PC, no USB
 * debugging, no adb required.
 *
 * This is not a replacement for full logcat — it only records the specific
 * checkpoints added at call sites below (see log() calls in CallNotifier,
 * IncomingCallActivity, VahinConnection, VahinTelecom, SignalService, and
 * VahinMessagingService), chosen to answer exactly the question "what actually
 * happened during this call, in order" — enough to see e.g. whether
 * IncomingCallActivity ever launched, when the ring timeout fired, and when/why
 * the notification got cancelled.
 *
 * Read from JS via VahinPermissionsPlugin.getDiagnosticLog(), which the in-app
 * "Copy debug log" button in Settings uses.
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
        Log.d(tag, msg); // still goes to normal logcat too, for anyone who does have adb
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
