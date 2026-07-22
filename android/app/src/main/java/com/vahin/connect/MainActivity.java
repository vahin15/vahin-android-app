package com.vahin.connect;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends BridgeActivity {

    // NOTE: We intentionally do NOT set a custom WebChromeClient here.
    //
    // Capacitor's BridgeActivity already installs com.getcapacitor.BridgeWebChromeClient
    // on the WebView (see Bridge.java), and that class already:
    //   - grants CAMERA / RECORD_AUDIO to getUserMedia() (WebRTC) requests, prompting
    //     the Android runtime-permission dialog itself when needed, and
    //   - implements onJsAlert / onJsConfirm / onJsPrompt (native alert()/confirm()/
    //     prompt() dialogs) and onShowFileChooser (the system file picker for
    //     <input type="file">).
    //
    // A previous version of this file replaced the WebView's WebChromeClient with a
    // bare `new WebChromeClient(){ ... }` that only implemented onPermissionRequest()
    // for camera/mic. That silently disabled everything else Android's default
    // WebChromeClient normally provides, including window.prompt()/confirm() (the
    // chat "More" menu, block/unblock, etc.) and the file picker (custom ringtone
    // upload). Since BridgeWebChromeClient already grants camera/mic for
    // getUserMedia() out of the box, we don't need a custom one at all.

    private static final int NOTIF_PERMISSION_REQUEST = 8001;
    private static MainActivity activeInstance;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_PERMISSION_REQUEST);
            }
        }

        // Fetch the FCM registration token and hand it to the web app once the page
        // has had a moment to finish loading. The web app exposes window.onFcmToken().
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) return;
            String token = task.getResult();
            new Handler(Looper.getMainLooper()).postDelayed(() -> deliverFcmToken(token), 1500);
        });

        handleIntentExtras(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentExtras(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (activeInstance == this) activeInstance = null;
    }

    // Called when IncomingCallActivity's Accept/Decline buttons (or a "message"
    // notification tap) relaunch MainActivity with these extras.
    private void handleIntentExtras(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("vahinAction");
        if (action == null) return;
        String from = intent.getStringExtra("vahinFrom");
        String js = "window.handleNativeCallAction && window.handleNativeCallAction("
            + toJsString(action) + "," + toJsString(from) + ");";
        runOnUiThread(() -> {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().evaluateJavascript(js, null);
            }
        });
    }

    public static void deliverFcmToken(String token) {
        MainActivity instance = activeInstance;
        if (instance == null || token == null) return;
        String js = "window.onFcmToken && window.onFcmToken(" + toJsString(token) + ");";
        instance.runOnUiThread(() -> {
            if (instance.bridge != null && instance.bridge.getWebView() != null) {
                instance.bridge.getWebView().evaluateJavascript(js, null);
            }
        });
    }

    private static String toJsString(String s) {
        if (s == null) return "null";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
