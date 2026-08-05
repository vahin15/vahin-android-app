package com.vahin.unifest;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
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
        if (myId == null || token == null) return;
        Request request = new Request.Builder().url(WS_URL).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                reconnectAttempt = 0;
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "hello");
                    hello.put("id", myId);
                    hello.put("token", token);
                    ws.send(hello.toString());
                } catch (Exception ignored) {}
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                scheduleReconnect();
            }
        });
    }

    private void handleMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type");

            if ("auth-error".equals(type)) {
                stopSelf();
                return;
            }

            if ("ring".equals(type)) {
                String callType = msg.optString("callType");
                String from     = msg.optString("from", null);
                String msgText  = msg.optString("text", null);

                if ("call".equals(callType) || "voice-call".equals(callType) || "conf".equals(callType)) {
                    if (CallNotifier.shouldSkipDuplicateRing(from)) return;
                    boolean isConf = "conf".equals(callType);
                    // Route through Telecom first (gives real ringer + OS awareness).
                    // Fall back to direct notification only if Telecom refuses.
                    boolean handedToTelecom = VahinTelecom.addIncomingCall(SignalService.this, from, isConf);
                    if (!handedToTelecom) {
                        CallNotifier.showIncomingCall(SignalService.this, callType, from);
                    }
                } else {
                    CallNotifier.showMessage(SignalService.this, from, msgText);
                }
            }
        } catch (Exception ignored) {}
    }

    private void scheduleReconnect() {
        if (stopping) return;
        reconnectAttempt++;
        long delayMs = Math.min(60_000, 2000L * (1 << Math.min(reconnectAttempt, 5)));
        handler.postDelayed(this::connect, delayMs);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        try {
            if (socket != null) socket.close(1000, "service stopping");
        } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}