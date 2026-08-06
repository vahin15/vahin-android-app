package com.vahin.unifest;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Always-on WebSocket to the backend's /presence hub — a second, independent delivery
 * path alongside FCM so calls arrive even when Google Play Services is unreliable.
 *
 * Fix: incoming call events now go through VahinTelecom.addIncomingCall() (same as
 * VahinMessagingService) instead of directly to CallNotifier. This gives the OS proper
 * awareness of the call: real ringer audio, DND bypass, Bluetooth/Auto routing, and
 * the system call UI. CallNotifier is kept as a fallback if Telecom refuses.
 */
public class SignalService extends Service {

    private static final String TAG = "SignalService";

    private static final String CHANNEL_ID = "vahin_signal";
    private static final int NOTIF_ID = 4200;
    private static final String WS_URL = "wss://vahin-backend.onrender.com/presence";

    private OkHttpClient client;
    private WebSocket socket;
    private String myId;
    private String token;
    private int reconnectAttempt = 0;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean stopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        client = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            myId  = intent.getStringExtra("myId");
            token = intent.getStringExtra("token");
        }
        Log.d(TAG, "onStartCommand: myId=" + myId + " (token present=" + (token != null) + ")");
        startForeground(NOTIF_ID, buildForegroundNotification());
        stopping = false;
        connect();
        return START_STICKY;
    }

    private android.app.Notification buildForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Always-on connection", NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("Keeps Unifest reachable for calls without relying on push notifications alone");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Unifest")
            .setContentText("Ready to receive calls")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private void connect() {
        if (myId == null || token == null) {
            Log.w(TAG, "connect: myId or token is null — not connecting");
            return;
        }
        Log.d(TAG, "connect: attempt=" + reconnectAttempt + " url=" + WS_URL);
        Request request = new Request.Builder().url(WS_URL).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                reconnectAttempt = 0;
                Log.d(TAG, "WebSocket onOpen: connected");
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "hello");
                    hello.put("id", myId);
                    hello.put("token", token);
                    ws.send(hello.toString());
                } catch (Exception e) {
                    Log.e(TAG, "WebSocket onOpen: failed to send hello — " + e.getMessage(), e);
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                // FIX A: wrap message handler — a crash here would silently kill the WS
                // thread and stop all future incoming calls without any visible error.
                try {
                    handleMessage(text);
                } catch (Exception e) {
                    Log.e(TAG, "WebSocket onMessage: unhandled exception processing message — "
                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "WebSocket onClosed: code=" + code + " reason=" + reason);
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.w(TAG, "WebSocket onFailure: " + (t != null ? t.getMessage() : "null") +
                    " reconnectAttempt=" + reconnectAttempt);
                scheduleReconnect();
            }
        });
    }

    private void handleMessage(String text) {
        // FIX A: parse exceptions now produce a visible log line rather than being
        // silently swallowed in the outer catch-all.
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "handleMessage: JSON parse error — " + e.getMessage() + " raw=" + text);
            return;
        }

        String type = msg.optString("type");
        Log.d(TAG, "handleMessage: type=" + type);

        if ("auth-error".equals(type)) {
            Log.w(TAG, "handleMessage: auth-error from server — stopping SignalService");
            stopSelf();
            return;
        }

        if ("ring".equals(type)) {
            String callType = msg.optString("callType");
            String from     = msg.optString("from", null);
            String msgText  = msg.optString("text", null);

            Log.d(TAG, "handleMessage: ring event — callType=" + callType + " from=" + from);

            if ("call".equals(callType) || "voice-call".equals(callType) || "conf".equals(callType)) {
                if (CallNotifier.shouldSkipDuplicateRing(from)) {
                    Log.d(TAG, "handleMessage: duplicate ring suppressed for from=" + from);
                    return;
                }
                boolean isConf = "conf".equals(callType);
                // Route through Telecom first (gives real ringer + OS awareness).
                // Fall back to direct notification only if Telecom refuses.
                Log.d(TAG, "handleMessage: handing incoming call to VahinTelecom — from=" + from);
                boolean handedToTelecom = VahinTelecom.addIncomingCall(SignalService.this, from, isConf);
                if (!handedToTelecom) {
                    Log.w(TAG, "handleMessage: VahinTelecom refused call — falling back to CallNotifier for from=" + from);
                    CallNotifier.showIncomingCall(SignalService.this, callType, from);
                }
            } else {
                Log.d(TAG, "handleMessage: message event from=" + from);
                CallNotifier.showMessage(SignalService.this, from, msgText);
            }
        }
    }

    private void scheduleReconnect() {
        if (stopping) return;
        reconnectAttempt++;
        long delayMs = Math.min(60_000, 2000L * (1 << Math.min(reconnectAttempt, 5)));
        Log.d(TAG, "scheduleReconnect: attempt=" + reconnectAttempt + " delayMs=" + delayMs);
        handler.postDelayed(this::connect, delayMs);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopping = true;
        try {
            if (socket != null) socket.close(1000, "service stopping");
        } catch (Exception e) {
            Log.w(TAG, "onDestroy: exception closing socket — " + e.getMessage());
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}