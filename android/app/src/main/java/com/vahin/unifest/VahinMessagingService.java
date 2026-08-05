package com.vahin.unifest;

import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/**
 * Receives data pushes from the Unifest backend via Firebase Cloud Messaging.
 *
 * Expected data payload from the backend (data-only messages, NOT the "notification"
 * field — data-only is required so this runs even when the app is fully killed):
 *   { "type": "call" | "conf" | "message", "from": "<peer id>", "text": "<optional>" }
 *
 * Notification-building itself lives in CallNotifier, shared with SignalService's
 * always-on WebSocket path so both delivery mechanisms show identical notifications.
 */
public class VahinMessagingService extends FirebaseMessagingService {

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
            if (CallNotifier.shouldSkipDuplicateRing(from)) return;
            boolean isConf = "conf".equals(type);
            boolean handedToTelecom = VahinTelecom.addIncomingCall(this, from, isConf);
            if (!handedToTelecom) {
                CallNotifier.showIncomingCall(this, type, from); // fallback, Telecom refused
            }
        } else {
            CallNotifier.showMessage(this, from, text);
        }
    }
}