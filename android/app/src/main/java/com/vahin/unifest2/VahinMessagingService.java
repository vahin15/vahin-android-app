package com.vahin.unifest2;

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

                // FIX ("ringing" appearing even when both phones are already online): a
                // call push can still be sent by the caller's presence probe even when the
                // callee is genuinely reachable (e.g. a slow round-trip to the Render
                // broker makes the caller's 500ms P2P probe time out before the WebSocket
                // actually answers) — that's a false negative on the SENDER's side, and
                // trying to make that probe perfectly reliable is a losing battle. Fixing
                // it here on the RECEIVING side instead is robust regardless of why the
                // push was sent: if this device is both in the foreground AND its PeerJS
                // broker socket is actually open right now (AppState.canSkipNativePopup()),
                // the real WebRTC call signal (peer.on('call') in index.html) is either
                // already showing the in-app ring or about to land within moments — the
                // native full-screen popup + Telecom call would just be a redundant second
                // ring stacked on top of the one already on screen. Skip it and let the
                // in-app ring alone handle it, exactly as if this had arrived over the
                // already-open P2P connection instead of FCM.
                if (AppState.canSkipNativePopup()) {
                    DebugLog.log(TAG, "onMessageReceived: app foreground + peer connected — "
                        + "skipping native popup, in-app ring will handle it (from=" + from + ")");
                    return;
                }

                boolean isConf = "conf".equals(type);

                // BUG FIX (ring flicker / double-vibrate / occasional silent ring on the
                // FCM path specifically): this used to call CallNotifier.showIncomingCall()
                // directly AND VahinTelecom.addIncomingCall() unconditionally, every time.
                // But when Telecom accepts the call, it calls back into
                // VahinConnection.onShowIncomingCallUi() (see VahinConnectionService ->
                // VahinConnection), which ALSO calls CallNotifier.showIncomingCall() for the
                // exact same "from". That posted the same notification/full-screen-intent
                // TWICE in quick succession on every FCM-delivered call — a second
                // MediaPlayer/ringtone getting created while the first was still starting,
                // a second full-screen-intent PendingIntent firing right as the first
                // IncomingCallActivity was still constructing itself. That race is exactly
                // what "the ring plays for a moment, cuts out, stutters, or the screen
                // flickers/doesn't come up" looks like from the outside — pure luck of
                // timing, not a fundamentally broken ring. SignalService.java's WebSocket
                // ring path already got this right: hand to Telecom FIRST, and only call
                // CallNotifier directly as the fallback if Telecom refuses. Mirror that
                // exact pattern here so both delivery paths (WS and FCM) behave identically
                // and never double-post.
                boolean handedToTelecom = VahinTelecom.addIncomingCall(this, from, isConf);
                if (!handedToTelecom) {
                    Log.w(TAG, "onMessageReceived: VahinTelecom refused call — "
                        + "falling back to CallNotifier for from=" + from);
                    CallNotifier.showIncomingCall(this, type, from);
                }
            } else if ("group-invite".equals(type)) {
                String groupId = data.get("groupId");
                String groupName = data.get("groupName");
                String safeTitle = (groupName != null && !groupName.isEmpty()) ? groupName : "Group Invitation";
                String safeBody = (from != null ? from : "Someone") + " added you to " + safeTitle;
                CallNotifier.showGroupNotification(this, safeTitle, safeBody, "group-invite", groupId != null ? groupId : from);
            } else if ("group-msg".equals(type)) {
                String groupId = data.get("groupId");
                String groupName = data.get("groupName");
                String safeTitle = (groupName != null && !groupName.isEmpty()) ? groupName : "Group Message";
                String safeBody = (from != null ? (from + ": ") : "") + (text != null ? text : "New message");
                CallNotifier.showGroupNotification(this, safeTitle, safeBody, "group-msg", groupId != null ? groupId : from);
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