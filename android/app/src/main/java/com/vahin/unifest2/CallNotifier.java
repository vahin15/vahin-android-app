package com.vahin.unifest2;

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
import android.util.Log;
import androidx.core.app.NotificationCompat;

public final class CallNotifier {

    private static final String TAG = "CallNotifier";

    // FIX: bumped from "vahin_calls" — Android caches a NotificationChannel's sound/
    // AudioAttributes the FIRST time it's created and silently ignores any changes on
    // subsequent createNotificationChannel() calls with the same ID, even after an app
    // update. Anyone who already had the app installed with the old
    // USAGE_VOICE_COMMUNICATION_SIGNALLING channel (see ensureChannels() below) would
    // keep getting the old one-shot "blip" behavior forever, since the corrected
    // AudioAttributes would never actually apply to their already-created channel. A
    // new channel ID makes Android create it fresh with the fixed ringtone attributes
    // automatically on the next app launch — no reinstall/clear-data needed.
    public static final String CHANNEL_ID_CALLS    = "vahin_calls_v2";
    public static final String CHANNEL_ID_MESSAGES = "vahin_messages";

    private CallNotifier() {}

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        // FIX D: wrap channel creation — some OEM builds have broken NotificationManager
        // implementations that throw on createNotificationChannel().
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) {
                Log.e(TAG, "ensureChannels: NotificationManager is null — cannot create channels");
                return;
            }

            // ── CALLS channel ────────────────────────────────────────────────────────
            // Must be IMPORTANCE_HIGH so the heads-up / full-screen intent fires.
            // We also set a ringtone sound on the channel so the notification itself
            // makes noise *before* IncomingCallActivity even opens (covers the case
            // where the full-screen intent is delayed by the OS, or — very common —
            // where Android deliberately downgrades a full-screen intent to a plain
            // heads-up notification because the device is unlocked/in active use;
            // Android only auto-launches full-screen over the lock screen, by design,
            // even with the USE_FULL_SCREEN_INTENT permission granted).
            //
            // FIX (root cause of "it rang as a notification with sound, but only for a
            // moment, instead of ringing properly"): this channel's AudioAttributes
            // used USAGE_VOICE_COMMUNICATION_SIGNALLING — that usage class is for
            // short in-call signalling blips (think: a call-waiting tone), and Android
            // plays it once, briefly, NOT as a looping ringtone. Whenever the full-
            // screen intent above got downgraded to a plain heads-up notification
            // (i.e., IncomingCallActivity's own looping MediaPlayer ringtone — see
            // IncomingCallActivity — never even got a chance to start, since that only
            // runs once the Activity itself is created), the ONLY sound the user ever
            // heard was this channel's one-shot blip. That exactly matches "voice, but
            // for a very short time". USAGE_NOTIFICATION_RINGTONE is the correct usage
            // for an actual incoming-call sound — it's what tells Android's audio/
            // notification framework to treat this as a real ringtone (loops for as
            // long as the notification is active, respects ringer volume/silent mode
            // the way a phone call should, same class real dialer apps use) rather
            // than a single signalling tone.
            // setSound() on a channel only takes effect the FIRST time the channel is
            // created — after that Android ignores it (user controls it in settings).
            // So if you previously created this channel without a sound, uninstall the
            // app and reinstall, or clear app data, to pick it up.
            Uri ringtoneUri = null;
            try {
                ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
                    ctx, RingtoneManager.TYPE_RINGTONE);
            } catch (Exception e) {
                Log.w(TAG, "ensureChannels: could not resolve default ringtone URI — " + e.getMessage());
            }

            AudioAttributes audioAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
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

            Log.d(TAG, "ensureChannels: notification channels created/verified");

        } catch (Exception e) {
            Log.e(TAG, "ensureChannels: exception creating channels — "
                + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public static void showIncomingCall(Context ctx, String type, String from) {
        // FIX D: wrap entire body — if this throws (bad context after process restart,
        // OEM notification manager bug) we log it and survive. A crash here is exactly
        // the kind of thing that would surface as the "back error" the user reported.
        try {
            ensureChannels(ctx);
            String safeFrom = (from == null) ? "Someone" : from;
            boolean isConf  = "conf".equals(type);

            Log.d(TAG, "showIncomingCall: from=" + safeFrom + " type=" + type);

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
                    // FIX: was setOngoing(true), making the notification impossible to
                    // manually swipe away. That's normally fine for a real phone dialer
                    // because the OS guarantees cleanup — but every auto-cancel path in
                    // this app (caller-cancel signal, Telecom onAbort, answer/decline)
                    // depends on our own code running successfully, and any gap in that
                    // chain left users with a notification that could NEVER be removed,
                    // not even manually. Swipeable now, so a stuck notification is at
                    // worst an annoyance instead of a permanent, un-removable one.
                    .setOngoing(false)
                    .setFullScreenIntent(fullScreenPI, true)
                    // FIX: this branch never set a contentIntent — tapping the notification
                    // body did nothing. Tapping now opens the full-screen call UI, same as
                    // the buttons.
                    .setContentIntent(fullScreenPI)
                    // FIX: CallStyle's native green/red circular buttons are frequently
                    // NOT rendered by heavily customized OEM notification shades (MIUI,
                    // ColorOS, FuntouchOS, One UI on older versions) — the notification
                    // still posts, but with no visible way to answer. Adding explicit
                    // addAction() buttons here is redundant on stock Android (CallStyle
                    // already shows them) but guarantees a tappable Answer/Decline row
                    // on OEMs that silently ignore CallStyle.
                    .addAction(android.R.drawable.ic_menu_call, "Answer", answerPI)
                    .addAction(android.R.drawable.ic_delete, "Decline", declinePI);
            } else {
                // Pre-Android 12 fallback
                builder = new NotificationCompat.Builder(ctx, CHANNEL_ID_CALLS)
                    .setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setContentTitle(isConf ? "Conference invite" : "Incoming call")
                    .setContentText(safeFrom + " is calling you on Unifest")
                    .setPriority(NotificationCompat.PRIORITY_MAX)   // MAX not HIGH for calls
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(false) // FIX: same reasoning as the API 31+ branch above — always swipeable
                    .setFullScreenIntent(fullScreenPI, true)
                    .setContentIntent(fullScreenPI)
                    .addAction(android.R.drawable.ic_menu_call, "Answer",
                        actionPendingIntent(ctx, safeFrom, "accept"))
                    .addAction(android.R.drawable.ic_delete, "Decline",
                        actionPendingIntent(ctx, safeFrom, "decline"));
            }

            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) {
                Log.e(TAG, "showIncomingCall: NotificationManager is null — notification cannot be posted");
                return;
            }
            nm.notify(callNotifId(safeFrom), builder.build());
            Log.d(TAG, "showIncomingCall: notification posted, id=" + callNotifId(safeFrom));
            DebugLog.log(TAG, "Notification POSTED — from=" + safeFrom
                + " branch=" + (Build.VERSION.SDK_INT >= 31 ? "CallStyle(API31+)" : "fallback(pre-API31)"));
            // Do NOT call ctx.startActivity() here — the full-screen intent handles it.
            // A direct startActivity() from a Service pushes MainActivity to the back
            // and causes the "app closes to home screen" bug.

        } catch (Exception e) {
            Log.e(TAG, "showIncomingCall: exception — "
                + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** Cancel the ongoing call notification — call this when answered, rejected, or ended. */
    public static void cancelCallNotification(Context ctx, String from) {
        if (from == null) return;
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(callNotifId(from));
                Log.d(TAG, "cancelCallNotification: cancelled id=" + callNotifId(from));
            }
        } catch (Exception e) {
            Log.w(TAG, "cancelCallNotification: exception — " + e.getMessage(), e);
        }
    }

    public static int callNotifId(String from) {
        return ("call-" + from).hashCode();
    }

    public static void showMessage(Context ctx, String from, String text) {
        try {
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

        } catch (Exception e) {
            Log.e(TAG, "showMessage: exception — " + e.getMessage(), e);
        }
    }

    public static void showGroupNotification(Context ctx, String title, String text, String action, String targetId) {
        try {
            ensureChannels(ctx);
            String safeTitle = (title == null || title.isEmpty()) ? "Group" : title;
            String safeAction = (action == null) ? "group" : action;
            String safeTarget = (targetId == null) ? "" : targetId;

            Intent openIntent = new Intent(ctx, MainActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            openIntent.putExtra("vahinAction", safeAction);
            openIntent.putExtra("vahinFrom", safeTarget);

            PendingIntent pi = PendingIntent.getActivity(
                ctx, (safeAction + "-" + safeTarget).hashCode(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID_MESSAGES)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(safeTitle)
                .setContentText(text == null ? "New group update" : text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(("group-" + safeTarget).hashCode(), builder.build());

        } catch (Exception e) {
            Log.e(TAG, "showGroupNotification: exception — " + e.getMessage(), e);
        }
    }

    // Deduplicates a ring arriving over both FCM and WebSocket within a short window.
    private static String lastRingKey = null;
    private static long   lastRingAt  = 0;
    public static synchronized boolean shouldSkipDuplicateRing(String from) {
        String key = String.valueOf(from);
        long now = System.currentTimeMillis();
        boolean dup = key.equals(lastRingKey) && (now - lastRingAt) < 4000;
        if (dup) {
            Log.d(TAG, "shouldSkipDuplicateRing: skipping duplicate ring for from=" + from);
        }
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