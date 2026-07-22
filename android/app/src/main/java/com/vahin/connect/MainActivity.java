package com.vahin.connect;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
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

        // Ask, once, to be exempted from battery optimization. Android kills background
        // processes and can block FCM delivery unless the app is whitelisted — this is
        // especially aggressive on Xiaomi (MIUI), Oppo (ColorOS), Vivo (FuntouchOS) and
        // Samsung, which is why ringing can silently fail on those phones even though the
        // code path is otherwise correct. Standard Android exposes one system API for
        // this (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); OEM "autostart"/"protected apps"
        // screens have no public API, so we best-effort deep-link to each vendor's known
        // settings screen and silently do nothing if it's not present on the device.
        new Handler(Looper.getMainLooper()).postDelayed(this::maybePromptBatteryExemption, 1200);

        handleIntentExtras(getIntent());
    }

    private void maybePromptBatteryExemption() {
        if (isFinishing() || isDestroyed()) return;
        String prefsName = "vahin_prefs";
        android.content.SharedPreferences prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        if (prefs.getBoolean("battery_prompt_shown", false)) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean alreadyIgnoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        if (alreadyIgnoring) {
            prefs.edit().putBoolean("battery_prompt_shown", true).apply();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Allow reliable ringing")
            .setMessage("To make sure calls ring even when Vahin Connect is closed, please allow it " +
                "to run without battery restrictions on the next screen.")
            .setCancelable(true)
            .setPositiveButton("Allow", (d, w) -> {
                prefs.edit().putBoolean("battery_prompt_shown", true).apply();
                requestIgnoreBatteryOptimizations();
                requestOemAutoStartPermission();
            })
            .setNegativeButton("Not now", (d, w) -> prefs.edit().putBoolean("battery_prompt_shown", true).apply())
            .show();
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            // Some OEM builds strip this intent; fall through, nothing we can do.
        }
    }

    // Best-effort deep link into each manufacturer's own "autostart" / "protected apps" /
    // "battery" settings screen, since REQUEST_IGNORE_BATTERY_OPTIMIZATIONS alone is often
    // not enough on these skins. Every component name is wrapped so an unrecognized device
    // just silently no-ops instead of crashing.
    private void requestOemAutoStartPermission() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        String[][] candidates;
        if (manufacturer.contains("xiaomi")) {
            candidates = new String[][]{
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"}
            };
        } else if (manufacturer.contains("oppo")) {
            candidates = new String[][]{
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"}
            };
        } else if (manufacturer.contains("vivo")) {
            candidates = new String[][]{
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"}
            };
        } else if (manufacturer.contains("samsung")) {
            candidates = new String[][]{
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"}
            };
        } else {
            candidates = new String[0][];
        }

        for (String[] c : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(c[0], c[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return; // stop at the first one that launches successfully
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next candidate
            }
        }
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
