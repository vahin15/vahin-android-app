package com.vahin.unifest;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/**
 * Receives high-priority data pushes from the Unifest backend via Firebase Cloud Messaging.
 *
 * Expected data payload from the backend (data-only messages, NOT the "notification"
 * field — data-only is required so this runs even when the app is fully killed):
 *   { "type": "call" | "voice-call" | "conf" | "message", "from": "<peer id>", "text": "<optional>" }
 */
public class VahinMessagingService extends FirebaseMessagingService {

    private static final String TAG = "VahinMessagingService";

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

        Log.d(TAG, "onMessageReceived: type=" + type + " from=" + from);

        // Acquire a temporary CPU wake lock so the device does not drop back into deep sleep
        // before the full-screen intent / notification is completely posted.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            try {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unifest:fcm_incoming_call");
                wakeLock.acquire(10_000L); // 10s safe ceiling
            } catch (Exception e) {
                Log.w(TAG, "Failed to acquire wake lock: " + e.getMessage());
            }
        }

        try {
            if ("call".equals(type) || "voice-call".equals(type) || "conf".equals(type)) {
                if (CallNotifier.shouldSkipDuplicateRing(from)) return;
                boolean isConf = "conf".equals(type);
                
                // 1. Immediately post the high-priority full-screen notification (bypasses lock screen)
                CallNotifier.showIncomingCall(this, type, from);
                
                // 2. Hand to Telecom for system-level audio routing and Bluetooth/Auto integration
                VahinTelecom.addIncomingCall(this, from, isConf);
            } else {
                CallNotifier.showMessage(this, from, text);
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception ignored) {}
            }
        }
    }
}