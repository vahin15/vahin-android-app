package com.vahin.unifest;

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
import android.util.Log;
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

    private static final String TAG = "MainActivity";
    private static final int NOTIF_PERMISSION_REQUEST = 8001;
    private static MainActivity activeInstance;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VahinPermissionsPlugin.class);
        super.onCreate(savedInstanceState);
        activeInstance = this;

        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log("MainActivity onCreate");
        } catch (Exception ignored) {}

        Log.d(TAG, "onCreate: registering Telecom PhoneAccount");
        // FIX C: VahinTelecom.register() now logs whether the PhoneAccount is actually
        // accepted by Telecom after registration, and fires window.onTelecomRegistrationFailed
        // into the web layer if the OEM silently blocked it. That lets the JS side show a
        // "calling isn't supported/enabled on this device" warning to the user.
        VahinTelecom.register(this);

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "onCreate: requesting POST_NOTIFICATIONS permission");
                ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_PERMISSION_REQUEST);
            }
        }

        // Fetch the FCM registration token and hand it to the web app once the page
        // has had a moment to finish loading. The web app exposes window.onFcmToken().
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Log.w(TAG, "FCM getToken failed: " + (task.getException() != null
                    ? task.getException().getMessage() : "null result"));
                return;
            }
            String token = task.getResult();
            Log.d(TAG, "FCM token received (length=" + token.length() + ")");
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
        new Handler(Looper.getMainLooper()).postDelayed(this::maybePromptFullScreenIntent, 1600);

        handleIntentExtras(getIntent());
    }

    // FIX FOR USERS: re-check on every resume, not just cold start. This covers two
    // cases the old onCreate-only check missed: (1) the user grants the permission in
    // Settings and taps back — we want that reflected immediately, not on next cold
    // launch; (2) the user reopens the app days later still without having granted it —
    // the cooldown inside maybePromptFullScreenIntent() decides whether to re-ask, so
    // this is safe to call on every resume without becoming a nag on every single one.
    @Override
    public void onResume() {
        super.onResume();
        maybePromptFullScreenIntent();
        maybePromptBatteryExemption();
    }

    // FIX (redundant native ring when both phones are already online): onStart/onStop
    // bracket "any part of this Activity is visible", which is what AppState.foreground
    // means to VahinMessagingService — see AppState.java for the full reasoning. Using
    // onStart/onStop instead of onResume/onPause on purpose: onPause also fires for
    // transient partial-cover cases (a system permission dialog, a share sheet), which
    // would incorrectly flip foreground=false while the app is still clearly "open" from
    // the user's point of view and would wrongly let a redundant native popup back in.
    @Override
    public void onStart() {
        super.onStart();
        AppState.setForeground(true);
    }

    @Override
    public void onStop() {
        super.onStop();
        AppState.setForeground(false);
    }

    // Android 14+ (API 34+) requires the user to explicitly grant USE_FULL_SCREEN_INTENT
    // in Settings — declaring it in the manifest is no longer enough on its own once
    // targetSdkVersion is 34 or higher (this app targets 35). Without this grant, the
    // ringing full-screen call UI silently never appears — the notification still posts,
    // but it degrades to a normal heads-up notification (or nothing, if the app was fully
    // killed), which is why ringing can appear to work in testing on older devices/emulators
    // but silently fail for real users on current Android versions.
    // FIX FOR USERS: previously this asked exactly once, ever. A "Not now" tap (or an
    // accidental dismiss, which is extremely common on a dialog that appears right after
    // install) set a permanent flag and the app never asked again — meaning ringing stayed
    // silently broken forever for that user with no path back except them personally
    // digging through phone settings unprompted. Since this permission is not optional
    // (calling literally cannot ring without it), we now re-prompt on a cooldown instead
    // of a one-shot ask, until the user actually grants it.
    private static final long FSI_PROMPT_COOLDOWN_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private void maybePromptFullScreenIntent() {
        if (Build.VERSION.SDK_INT < 34) return; // permission only exists / is restricted from API 34
        if (isFinishing() || isDestroyed()) return;

        android.app.NotificationManager nm =
            (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // FIX A: log the full-screen intent permission status so it's diagnosable
        boolean canUse = nm != null && nm.canUseFullScreenIntent();
        Log.d(TAG, "maybePromptFullScreenIntent: canUseFullScreenIntent=" + canUse);

        if (nm != null && canUse) return; // already granted — nothing to do, ever again

        android.content.SharedPreferences prefs = getSharedPreferences("vahin_prefs", Context.MODE_PRIVATE);
        long lastShown = prefs.getLong("fsi_prompt_last_shown", 0);
        long now = System.currentTimeMillis();
        if (now - lastShown < FSI_PROMPT_COOLDOWN_MS) return; // asked recently — don't nag every launch

        Log.w(TAG, "maybePromptFullScreenIntent: USE_FULL_SCREEN_INTENT not granted — prompting user");

        new AlertDialog.Builder(this)
            .setTitle("Turn on full-screen calls")
            .setMessage("So Unifest calls ring like a real phone call — even when the app is " +
                "closed or your phone is locked — please allow \"Full screen notifications\" " +
                "on the next screen. Takes a few seconds.")
            .setCancelable(true)
            .setPositiveButton("Allow", (d, w) -> {
                prefs.edit().putLong("fsi_prompt_last_shown", now).apply();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "maybePromptFullScreenIntent: could not open settings — " + e.getMessage());
                    // Some OEM builds may not expose this screen; nothing more we can do.
                }
            })
            .setNegativeButton("Not now", (d, w) -> prefs.edit().putLong("fsi_prompt_last_shown", now).apply())
            .show();
    }

    // FIX FOR USERS: previously this checked prefs.getBoolean("battery_prompt_shown")
    // and, once true, NEVER looked at the real PowerManager state again — permanently,
    // for the life of the install. That flag got set either on first successful grant
    // OR on a single "Not now" tap OR simply because the app happened to already be
    // exempt the first time this ran. None of those guarantee the exemption stays
    // granted forever: a phone system/OS update, a manufacturer battery-manager reset,
    // or the user later un-exempting the app in Settings can all silently revoke it —
    // and this app had no way to notice, so ringing-while-closed would break with zero
    // trace and no path back except the user digging through Settings unprompted. Now
    // mirrors maybePromptFullScreenIntent()'s pattern: re-check the ACTUAL current
    // state every resume, and only use a time cooldown (not a permanent flag) to avoid
    // nagging on every single launch.
    private static final long BATTERY_PROMPT_COOLDOWN_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private void maybePromptBatteryExemption() {
        if (isFinishing() || isDestroyed()) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean alreadyIgnoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        Log.d(TAG, "maybePromptBatteryExemption: isIgnoringBatteryOptimizations=" + alreadyIgnoring);
        if (alreadyIgnoring) return; // genuinely fine right now — nothing to do

        android.content.SharedPreferences prefs = getSharedPreferences("vahin_prefs", Context.MODE_PRIVATE);
        long lastShown = prefs.getLong("battery_prompt_last_shown", 0);
        long now = System.currentTimeMillis();
        if (now - lastShown < BATTERY_PROMPT_COOLDOWN_MS) return; // asked recently — don't nag every launch

        Log.w(TAG, "maybePromptBatteryExemption: battery optimization is ON (not exempt) — prompting user");

        new AlertDialog.Builder(this)
            .setTitle("Allow reliable ringing")
            .setMessage("To make sure calls ring even when Unifest is closed, please allow it " +
                "to run without battery restrictions on the next screen. (If you already did this " +
                "before, your phone's last update may have reset it — this can happen after system " +
                "updates.)")
            .setCancelable(true)
            .setPositiveButton("Allow", (d, w) -> {
                prefs.edit().putLong("battery_prompt_last_shown", now).apply();
                requestIgnoreBatteryOptimizations();
                requestOemAutoStartPermission();
            })
            .setNegativeButton("Not now", (d, w) -> prefs.edit().putLong("battery_prompt_last_shown", now).apply())
            .show();
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "requestIgnoreBatteryOptimizations: exception — " + e.getMessage());
            // Some OEM builds strip this intent; fall through, nothing we can do.
        }
    }

    // Best-effort deep link into each manufacturer's own "autostart" / "protected apps" /
    // "battery" settings screen, since REQUEST_IGNORE_BATTERY_OPTIMIZATIONS alone is often
    // not enough on these skins. Every component name is wrapped so an unrecognized device
    // just silently no-ops instead of crashing.
    private void requestOemAutoStartPermission() {
        requestOemAutoStartPermissionStatic(this);
    }

    // Shared with VahinPermissionsPlugin so the Settings screen's "Allow background
    // running" row can re-trigger the exact same OEM deep-link attempts as the one-time
    // launch prompt, instead of duplicating the vendor component list in two places.
    static void requestOemAutoStartPermissionStatic(android.app.Activity activity) {
        if (activity == null) return;
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
                activity.startActivity(intent);
                Log.d(TAG, "requestOemAutoStartPermissionStatic: launched " + c[0] + "/" + c[1]);
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
        Log.d(TAG, "onDestroy");
    }

    // Called when IncomingCallActivity's Accept/Decline buttons (or a "message"
    // notification tap) relaunch MainActivity with these extras.
    //
    // Fix: when the app was fully killed, tapping Accept launches a fresh
    // MainActivity whose WebView hasn't finished loading index.html yet — at that
    // point window.handleNativeCallAction doesn't exist, so evaluateJavascript()
    // silently does nothing and the tap appears to "just open the app" with no
    // call starting. We now retry with a check for the function's existence
    // (via a JS callback) instead of firing once and hoping the page is ready.
    private void handleIntentExtras(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("vahinAction");
        if (action == null) return;
        String from = intent.getStringExtra("vahinFrom");
        Log.d(TAG, "handleIntentExtras: action=" + action + " from=" + from);
        // Clear so a later recreate() (e.g. rotation) doesn't replay a stale action.
        intent.removeExtra("vahinAction");
        intent.removeExtra("vahinFrom");

        // FIX (Telecom call flow): keep Telecom's own VahinConnection in sync no matter
        // which UI the accept/decline arrived from. IncomingCallActivity's own buttons
        // already call answerFromAppUi()/rejectFromAppUi() themselves before landing
        // here — doing it again is harmless (setActive()/setDisconnected() on an
        // already-synced connection is a no-op). But CallNotifier's Answer/Decline
        // buttons in the notification shade (including Android's own CallStyle buttons)
        // jump straight here with only a vahinAction extra and never touched Telecom at
        // all — without this, answering/declining from the shade told the web app what
        // happened but left the Telecom connection stuck RINGING: wrong Bluetooth/
        // Android Auto state, and Telecom's own ~30s ringing timeout could tear the call
        // down later, out from under the app.
        if ("accept".equals(action) || "decline".equals(action) || "missed".equals(action)) {
            try {
                VahinConnection conn = VahinConnection.getCurrent();
                if (conn != null) {
                    if ("accept".equals(action)) {
                        conn.answerFromAppUi();
                    } else {
                        conn.rejectFromAppUi();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "handleIntentExtras: exception syncing Telecom state — " + e.getMessage(), e);
            }
        }

        deliverNativeCallAction(action, from, 0);
    }

    // ROOT CAUSE of "Answer opens the general/home screen instead of the call screen"
    // (worst on a cold start): the old code's ONLY delivery path was the probe-and-push
    // loop below — it polled for `typeof window.handleNativeCallAction === 'function'`
    // and gave up completely after NATIVE_ACTION_MAX_RETRIES (25 * 200ms = 5s). But
    // window.handleNativeCallAction only becomes defined once the ENTIRE inline
    // <script> in index.html (thousands of lines) has finished parsing and executing.
    // On a genuinely cold start — fresh Android process, WebView engine spin-up,
    // Capacitor bridge init, keyguard dismiss, all happening at once because the user
    // tapped Answer from a locked full-screen call UI — that routinely takes longer
    // than 5 seconds on real (non-flagship) devices. Once the 5s ceiling was hit, the
    // action was silently dropped forever: no retry, no fallback, nothing. The user
    // just ended up wherever boot() puts them (screen-chats) — exactly the reported bug.
    //
    // FIX: below we now ALSO write the action into a raw JS global
    // (window.__vahinPendingNativeAction) the moment the WebView object exists — this
    // requires only that the WebView itself has been created (near-instant, happens in
    // BridgeActivity's super.onCreate()), NOT that the page has finished loading, let
    // alone finished running its own script. That assignment lands in whatever document
    // is currently loaded in the WebView and — because it's the same document that will
    // shortly run index.html's own <script> — survives until that script reaches its own
    // boot sequence, however long that takes, and drains it itself (see index.html:
    // drainPendingNativeAction(), called right after boot()). This removes the 5s
    // ceiling as a hard failure point entirely: delivery no longer depends on winning a
    // race against page-load time.
    //
    // The original probe-and-push path is kept running in parallel (with its retry
    // window substantially extended, as defense in depth for devices where the raw
    // write somehow doesn't stick) — window.handleNativeCallAction() dedupes on its end
    // if both paths end up delivering the same action.
    private static final int NATIVE_ACTION_MAX_RETRIES = 100;  // ~20s total at 200ms — was 5s
    private static final int NATIVE_ACTION_RETRY_DELAY_MS = 200;

    private void deliverNativeCallAction(String action, String from, int attempt) {
        if (isFinishing() || isDestroyed()) return;
        if (bridge == null || bridge.getWebView() == null) {
            retryDeliverNativeCallAction(action, from, attempt);
            return;
        }

        // FIX: raw "mailbox" write — fires on every attempt (cheap, idempotent) as soon
        // as the WebView exists, regardless of whether index.html has finished loading.
        // This is what actually closes the race; see the big comment above.
        try {
            String rawStore = "window.__vahinPendingNativeAction={action:"
                + toJsString(action) + ",from:" + toJsString(from) + ",ts:Date.now()};";
            bridge.getWebView().evaluateJavascript(rawStore, null);
        } catch (Exception e) {
            Log.w(TAG, "deliverNativeCallAction: raw-store evaluateJavascript threw — "
                + e.getMessage());
        }

        // FIX A: probe evaluateJavascript wraps its result callback — if the JS
        // engine isn't ready yet we retry; if it is ready but evaluateJavascript
        // throws, we catch and log the full exception instead of crashing silently.
        try {
            // checkExists=true: evaluate a tiny expression first so we know whether
            // window.handleNativeCallAction is actually defined yet, and only retry
            // if it isn't — instead of blindly firing once like before.
            String probe = "typeof window.handleNativeCallAction";
            bridge.getWebView().evaluateJavascript(probe, (String result) -> {
                boolean ready = result != null && result.contains("function");
                if (ready) {
                    Log.d(TAG, "deliverNativeCallAction: bridge ready at attempt=" + attempt
                        + " — delivering action=" + action + " from=" + from);
                    String js = "window.handleNativeCallAction("
                        + toJsString(action) + "," + toJsString(from) + ");";
                    runOnUiThread(() -> {
                        try {
                            if (bridge != null && bridge.getWebView() != null) {
                                bridge.getWebView().evaluateJavascript(js, resultValue -> {
                                    // FIX A: log the JS result — "undefined" is normal,
                                    // but null can indicate the WebView context was destroyed.
                                    Log.d(TAG, "deliverNativeCallAction: evaluateJavascript result="
                                        + resultValue);
                                });
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "deliverNativeCallAction: evaluateJavascript threw — "
                                + e.getMessage(), e);
                        }
                    });
                    // Don't retry further once we know the page is up — the raw-store
                    // write above has already been delivered at least once by now too.
                } else {
                    if (attempt == 0 || attempt % 5 == 0) {
                        Log.d(TAG, "deliverNativeCallAction: bridge not ready yet, attempt=" + attempt
                            + " probe result=" + result + " — retrying in " + NATIVE_ACTION_RETRY_DELAY_MS + "ms");
                    }
                    retryDeliverNativeCallAction(action, from, attempt);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "deliverNativeCallAction: evaluateJavascript (probe) threw at attempt=" + attempt
                + " — " + e.getMessage(), e);
            retryDeliverNativeCallAction(action, from, attempt);
        }
    }

    private void retryDeliverNativeCallAction(String action, String from, int attempt) {
        if (attempt >= NATIVE_ACTION_MAX_RETRIES) {
            // FIX: even after giving up on the push-probe loop, the raw-store write
            // from earlier attempts is still sitting in window.__vahinPendingNativeAction
            // (evaluated successfully as soon as the WebView existed, well before this
            // ceiling). index.html's own boot sequence will still pick it up whenever it
            // finishes loading — so "giving up" here only stops the redundant push path,
            // it does NOT mean the action itself is lost anymore.
            Log.w(TAG, "deliverNativeCallAction: giving up on push-probe loop after "
                + NATIVE_ACTION_MAX_RETRIES + " attempts — window.handleNativeCallAction "
                + "never became available (action=" + action + " from=" + from + "). "
                + "Raw mailbox write should still be picked up once the page finishes booting.");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> deliverNativeCallAction(action, from, attempt + 1),
            NATIVE_ACTION_RETRY_DELAY_MS);
    }

    public static void deliverFcmToken(String token) {
        MainActivity instance = activeInstance;
        if (instance == null || token == null) return;
        String js = "window.onFcmToken && window.onFcmToken(" + toJsString(token) + ");";
        instance.runOnUiThread(() -> {
            try {
                if (instance.bridge != null && instance.bridge.getWebView() != null) {
                    instance.bridge.getWebView().evaluateJavascript(js, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "deliverFcmToken: evaluateJavascript threw — " + e.getMessage(), e);
            }
        });
    }

    public static void runJsIfAvailable(String js) {
        MainActivity instance = activeInstance;
        if (instance == null) return;
        instance.runOnUiThread(() -> {
            try {
                if (instance.bridge != null && instance.bridge.getWebView() != null) {
                    instance.bridge.getWebView().evaluateJavascript(js, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "runJsIfAvailable: evaluateJavascript threw — " + e.getMessage(), e);
            }
        });
    }

    private static String toJsString(String s) {
        if (s == null) return "null";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}