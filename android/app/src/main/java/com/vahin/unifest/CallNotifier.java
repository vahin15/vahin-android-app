package com.vahin.unifest;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public final class CallNotifier {

    public static final String CHANNEL_ID_CALLS    = "vahin_calls";
    public static final String CHANNEL_ID_MESSAGES = "vahin_messages";

    private CallNotifier() {}

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // ── CALLS channel ────────────────────────────────────────────────────────
        // Must be IMPORTANCE_HIGH so the heads-up / full-screen intent fires.
        // We also set a ringtone sound on the channel so the notification itself
        // makes noise *before* IncomingCallActivity even opens (covers the case
        // where the full-screen intent is delayed by the OS).
        // setSound() on a channel only takes effect the FIRST time the channel is
        // created — after that Android ignores it (user controls it in settings).
        // So if you previously created this channel without a sound, uninstall the
        // app and reinstall, or clear app data, to pick it up.
        Uri ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
            ctx, RingtoneManager.TYPE_RINGTONE);
        AudioAttributes audioAttr = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(AudioManager.STREAM_RING)
            .build();

        NotificationChannel calls = new NotificationChannel(
            CHANNEL_ID_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Rings for incoming Unifest calls");
        calls.enableVibration(true);
        calls.setBypassDnd(true);
        if (ringtoneUri != null) calls.setSound(ringtoneUri, audioAttr);
        nm.createNotificationChannel(calls);

        // ── MESSAGES channel ─────────────────────────────────────────────────────
        // Default importance = shows a heads-up with the default notification sound.
        // No setOngoing — messages must be swipeable.
        NotificationChannel messages = new NotificationChannel(
            CHANNEL_ID_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(messages);
    }

    public static void showIncomingCall(Context ctx, String type, String from) {
        ensureChannels(ctx);
        String safeFrom = (from == null) ? "Someone" : from;
        boolean isConf  = "conf".equals(type);

        Intent fullScreenIntent = new Intent(ctx, IncomingCallActivity.class);
        fullScreenIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        fullScreenIntent.putExtra("from", safeFrom);
        fullScreenIntent.putExtra("isConf", isConf);
        PendingIntent fullScreenPI = PendingIntent.getActivity(
            ctx, safeFrom.hashCode(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+ — use CallStyle: gives native green/red buttons in the shade
            // and is the only notification shape Android guarantees won't be throttled
            // for repeated high-priority calls.
            androidx.core.app.Person caller = new androidx.core.app.Person.Builder()
                .setName(safeFrom)
                .setImportant(true)
                .build();
            PendingIntent answerPI  = actionPendingIntent(ctx, safeFrom, "accept");
            PendingIntent declinePI = actionPendingIntent(ctx, safeFrom, "decline");

            builder = new NotificationCompat.Builder(ctx, CHANNEL_ID_CALLS)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentText(isConf ? "Conference invite \u00b7 Unifest" : "Incoming call \u00b7 Unifest")
                .setStyle(NotificationCompat.CallStyle
                    .forIncomingCall(caller, declinePI, answerPI))
                .addPerson(caller)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)                   // call notifications must not be swipeable
                .setFullScreenIntent(fullScreenPI, true);
        } else {
            // Pre-Android 12 fallback
            builder = new NotificationCompat.Builder(ctx, CHANNEL_ID_CALLS)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(isConf ? "Conference invite" : "Incoming call")
                .setContentText(safeFrom + " is calling you on Unifest")
                .setPriority(NotificationCompat.PRIORITY_MAX)   // MAX not HIGH for calls
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPI, true)
                .setContentIntent(fullScreenPI)
                .addAction(android.R.drawable.ic_menu_call, "Answer",
                    actionPendingIntent(ctx, safeFrom, "accept"))
                .addAction(android.R.drawable.ic_delete, "Decline",
                    actionPendingIntent(ctx, safeFrom, "decline"));
        }

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(callNotifId(safeFrom), builder.build());
        // Do NOT call ctx.startActivity() here — the full-screen intent handles it.
        // A direct startActivity() from a Service pushes MainActivity to the back
        // and causes the "app closes to home screen" bug.
    }

    /** Cancel the ongoing call notification — call this when answered, rejected, or ended. */
    public static void cancelCallNotification(Context ctx, String from) {
        if (from == null) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(callNotifId(from));
    }

    public static int callNotifId(String from) {
        return ("call-" + from).hashCode();
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

        // No setOngoing — messages must be swipeable.
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

    // Deduplicates a ring arriving over both FCM and WebSocket within a short window.
    private static String lastRingKey = null;
    private static long   lastRingAt  = 0;
    public static synchronized boolean shouldSkipDuplicateRing(String from) {
        String key = String.valueOf(from);
        long now = System.currentTimeMillis();
        boolean dup = key.equals(lastRingKey) && (now - lastRingAt) < 4000;
        lastRingKey = key;
        lastRingAt  = now;
        return dup;
    }

    // Answer/Decline intents reuse the same accept/decline path as IncomingCallActivity
    // buttons so tapping from the shade works identically to the full-screen UI.
    private static PendingIntent actionPendingIntent(Context ctx, String safeFrom, String action) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("vahinAction", action);
        intent.putExtra("vahinFrom", safeFrom);
        return PendingIntent.getActivity(
            ctx, (action + "-" + safeFrom).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}