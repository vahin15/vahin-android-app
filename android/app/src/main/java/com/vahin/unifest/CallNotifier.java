package com.vahin.unifest;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

/**
 * Builds and posts the incoming-call / message notifications. Pulled out of
 * VahinMessagingService so the FCM delivery path and the always-on WebSocket
 * delivery path (SignalService) show the exact same notification instead of two
 * independently-maintained copies that could quietly drift apart.
 */
public final class CallNotifier {

    public static final String CHANNEL_ID_CALLS = "vahin_calls";
    public static final String CHANNEL_ID_MESSAGES = "vahin_messages";

    private CallNotifier() {}

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel calls = new NotificationChannel(
            CHANNEL_ID_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Rings for incoming Unifest calls");
        calls.enableVibration(true);
        calls.setBypassDnd(true);
        nm.createNotificationChannel(calls);

        NotificationChannel messages = new NotificationChannel(
            CHANNEL_ID_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(messages);
    }

    // Built from Android's own documented pattern for call notifications
    // (source.android.com/docs/core/connect/call-notification), not copied from any
    // single project: NotificationCompat.CallStyle is the system-recognized template
    // for "this is an incoming call", available since Android 12 (API 31) via
    // AndroidX Core. Two things this buys us over a hand-built notification:
    //   1. Android is documented to deprioritize/suppress repeated high-priority
    //      notifications from apps that DON'T use CallStyle — so on newer Android and
    //      some OEM skins, a plain Builder-based "call" notification can start being
    //      silently downgraded over time even though the code posting it hasn't changed.
    //      CallStyle is the one shape the system always treats as a real call.
    //   2. It renders native green-answer/red-decline buttons directly in the
    //      notification shade (and on Wear OS / Android Auto), with a one-tap Answer
    //      that doesn't require the full-screen UI to appear first.
    public static void showIncomingCall(Context ctx, String type, String from) {
        ensureChannels(ctx);
        String safeFrom = (from == null) ? "Someone" : from;
        boolean isConf = "conf".equals(type);

        Intent fullScreenIntent = new Intent(ctx, IncomingCallActivity.class);
        fullScreenIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        fullScreenIntent.putExtra("from", safeFrom);
        fullScreenIntent.putExtra("isConf", isConf);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
            ctx, safeFrom.hashCode(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder;
        if (Build.VERSION.SDK_INT >= 31) {
            androidx.core.app.Person caller = new androidx.core.app.Person.Builder()
                .setName(safeFrom)
                .setImportant(true)
                .build();

            PendingIntent answerPendingIntent = actionPendingIntent(ctx, safeFrom, "accept");
            PendingIntent declinePendingIntent = actionPendingIntent(ctx, safeFrom, "decline");

            builder = new NotificationCompat.Builder(ctx, CHANNEL_ID_CALLS)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentText(isConf ? "Conference invite \u00b7 Unifest" : "Incoming call \u00b7 Unifest")
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePendingIntent, answerPendingIntent))
                .addPerson(caller)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPendingIntent, true);
        } else {
            // Pre-Android 12 fallback — CallStyle doesn't exist yet on these versions,
            // so keep the manually-built high-priority notification that already worked.
            builder = new NotificationCompat.Builder(ctx, CHANNEL_ID_CALLS)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(isConf ? "Conference invite" : "Incoming call")
                .setContentText(safeFrom + " is calling you")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent);
        }

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(("call-" + safeFrom).hashCode(), builder.build());
        // NOTE: Do NOT call ctx.startActivity() here. Starting an Activity from a
        // Service/BroadcastReceiver context without a live back-stack causes Android
        // to push the current task to the back and show the home screen — which is
        // exactly the "app keeps closing" bug. The full-screen intent on the notification
        // handles launching IncomingCallActivity correctly (it has its own task due to
        // taskAffinity="" in the manifest), so no direct launch is needed.
    }

    // Shared by the CallStyle Answer/Decline shade actions: relaunches MainActivity
    // with the same vahinAction/vahinFrom extras IncomingCallActivity's own buttons
    // already use, so a one-tap answer from the notification shade goes through the
    // exact same accept/decline path as the full-screen UI instead of a second one.
    private static PendingIntent actionPendingIntent(Context ctx, String safeFrom, String action) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("vahinAction", action);
        intent.putExtra("vahinFrom", safeFrom);
        return PendingIntent.getActivity(
            ctx, (action + "-" + safeFrom).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void showMessage(Context ctx, String from, String text) {
        ensureChannels(ctx);
        String safeFrom = (from == null) ? "Unifest" : from;

        Intent openIntent = new Intent(ctx, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openIntent.putExtra("vahinAction", "message");
        openIntent.putExtra("vahinFrom", safeFrom);

        PendingIntent pi = PendingIntent.getActivity(
            ctx, safeFrom.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(safeFrom)
            .setContentText(text == null ? "New message" : text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi);

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(("msg-" + safeFrom).hashCode(), builder.build());
    }

    // De-dupes a ring that arrives over both FCM and the socket within a short window
    // of each other, so the phone doesn't briefly show/rebuild the notification twice
    // for the same call.
    private static String lastRingKey = null;
    private static long lastRingAt = 0;
    public static synchronized boolean shouldSkipDuplicateRing(String from) {
        String key = String.valueOf(from);
        long now = System.currentTimeMillis();
        boolean dup = key.equals(lastRingKey) && (now - lastRingAt) < 4000;
        lastRingKey = key;
        lastRingAt = now;
        return dup;
    }
}