package com.vahin.connect;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/**
 * Receives data pushes from the Unifest backend via Firebase Cloud Messaging.
 *
 * Expected data payload from the backend (data-only messages, NOT the "notification"
 * field — data-only is required so this runs even when the app is fully killed):
 *   { "type": "call" | "conf" | "message", "from": "<peer id>", "text": "<optional>" }
 */
public class VahinMessagingService extends FirebaseMessagingService {

    public static final String CHANNEL_ID_CALLS = "vahin_calls";
    public static final String CHANNEL_ID_MESSAGES = "vahin_messages";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        MainActivity.deliverFcmToken(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);
        Map<String, String> data = message.getData();
        String type = data.get("type");
        String from = data.get("from");
        String text = data.get("text");

        if ("call".equals(type) || "voice-call".equals(type) || "conf".equals(type)) {
            showIncomingCallNotification(type, from);
        } else {
            showMessageNotification(from, text);
        }
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
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

    private void showIncomingCallNotification(String type, String from) {
        ensureChannels();
        String safeFrom = (from == null) ? "Someone" : from;
        boolean isConf = "conf".equals(type);

        Intent fullScreenIntent = new Intent(this, IncomingCallActivity.class);
        fullScreenIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        fullScreenIntent.putExtra("from", safeFrom);
        fullScreenIntent.putExtra("isConf", isConf);

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
            this, safeFrom.hashCode(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(isConf ? "Conference invite" : "Incoming call")
            .setContentText(safeFrom + " is calling you")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(("call-" + safeFrom).hashCode(), builder.build());

        // On many devices setFullScreenIntent() only actually launches the activity
        // when the screen is off/locked. Also try a direct launch so ringing shows
        // immediately if the app process is alive but backgrounded.
        try {
            startActivity(fullScreenIntent);
        } catch (Exception ignored) {
        }
    }

    private void showMessageNotification(String from, String text) {
        ensureChannels();
        String safeFrom = (from == null) ? "Unifest" : from;

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openIntent.putExtra("vahinAction", "message");
        openIntent.putExtra("vahinFrom", safeFrom);

        PendingIntent pi = PendingIntent.getActivity(
            this, safeFrom.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(safeFrom)
            .setContentText(text == null ? "New message" : text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(("msg-" + safeFrom).hashCode(), builder.build());
    }
}
