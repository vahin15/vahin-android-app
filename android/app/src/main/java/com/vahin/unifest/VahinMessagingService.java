package com.vahin.unifest;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives high-priority data pushes from the Unifest backend via Firebase Cloud Messaging.
 *
 * Expected data payload from the backend (data-only messages, NOT the "notification"
 * field — data-only is required so this runs even when the app is fully killed):
 *   { "type": "call" | "voice-call" | "conf" | "message", "from": "<peer id>", "text": "<optional>" }
 */
public class VahinMessagingService extends FirebaseMessagingService {

    private static final String TAG = "VahinMessagingService";

    // FIX (slow "Connecting…" after tapping Answer): the Render free-tier backend that
    // ALSO hosts the PeerJS broker only started waking from a cold sleep once the WebView
    // booted and Peer.connect() actually reached it — which only happened after the user
    // tapped Answer. That put Render's full cold-start latency (can be 15-40s+ on the
    // free tier) directly in the critical path of "tap Answer" → "actually connected",
    // which is exactly what looked like a stuck/slow reconnect.
    // Fix: fire a fire-and-forget ping at /health the INSTANT the ring notification
    // arrives — i.e. while the phone is still ringing, seconds before the user even
    // reaches for it — so the backend is already warm (or well into waking up) by the
    // time Peer.connect() actually needs it. This costs nothing if the backend was
    // already warm, and saves most/all of the cold-start delay when it wasn't.
    private static final String BACKEND_HEALTH_URL = "https://vahin-backend.onrender.com/health";
    private static final ExecutorService prewarmExecutor = Executors.newSingleThreadExecutor();

    private static void prewarmBackend() {
        prewarmExecutor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                URL url = new URL(BACKEND_HEALTH_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                Log.d(TAG, "prewarmBackend: /health responded " + code
                    + " in " + (System.currentTimeMillis() - start) + "ms");
            } catch (IOException e) {
                // Best-effort only — if this fails, Peer.connect() will still try on its
                // own later; we've just lost the head start, not broken anything.
                Log.w(TAG, "prewarmBackend: failed after "
                    + (System.currentTimeMillis() - start) + "ms — " + e.getMessage());
            }
        });
    }

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

        // Kick this off FIRST, before anything else below — every millisecond it starts
        // earlier is a millisecond less cold-start latency the user waits through later.
        if ("call".equals(type) || "voice-call".equals(type) || "conf".equals(type)) {
            prewarmBackend();
        }

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