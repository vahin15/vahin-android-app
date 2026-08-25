package com.vahin.unifest;

import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.util.Base64;

@CapacitorPlugin(
    name = "VahinPermissions",
    permissions = { @Permission(strings = { android.Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications") }
)
public class VahinPermissionsPlugin extends Plugin {

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33) {
            call.resolve(new JSObject().put("granted", true));
            return;
        }
        if (isNotificationsGranted()) {
            call.resolve(new JSObject().put("granted", true));
            return;
        }
        requestPermissionForAlias("notifications", call, "notifPermCallback");
    }

    @PermissionCallback
    private void notifPermCallback(PluginCall call) {
        boolean granted = getPermissionState("notifications") == PermissionState.GRANTED;
        call.resolve(new JSObject().put("granted", granted));
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("fullScreenIntentGranted", isFullScreenIntentGranted());
        ret.put("fullScreenIntentApplicable", Build.VERSION.SDK_INT >= 34);
        ret.put("batteryExemptionGranted", isIgnoringBatteryOptimizations());
        ret.put("notificationsGranted", isNotificationsGranted());
        ret.put("notificationsApplicable", Build.VERSION.SDK_INT >= 33);
        // BULLETPROOF RINGING: CallNotifier's calls channel sets setBypassDnd(true), but
        // that flag is silently IGNORED by Android unless the user has separately granted
        // this app "Do Not Disturb access" (a totally different permission from
        // notifications/battery — there is no public API to request it directly, only to
        // open the settings screen for it, same restriction as Samsung's battery sleep
        // toggle below). Without it, any DND/Focus/Sleep mode the user has active — which
        // is extremely common (bedtime schedules, meeting-time auto-DND) — silently
        // suppresses the incoming-call notification entirely: the code runs, the
        // notification posts, and the OS just never surfaces it. This was previously never
        // checked or requested anywhere, so it was a silent, undiagnosable "sometimes it
        // just doesn't ring" gap on any phone with a DND schedule.
        ret.put("dndAccessGranted", isDndAccessGranted());
        ret.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
        ret.put("brand", Build.BRAND == null ? "" : Build.BRAND);
        ret.put("model", Build.MODEL == null ? "" : Build.MODEL);
        ret.put("sdkInt", Build.VERSION.SDK_INT);
        call.resolve(ret);
    }

    @PluginMethod
    public void openDndAccessSettings(PluginCall call) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception ignored) {}
        call.resolve();
    }

    private boolean isDndAccessGranted() {
        try {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationPolicyAccessGranted();
        } catch (Exception e) {
            return false;
        }
    }

    @PluginMethod
    public void openOemAutoStartSettings(PluginCall call) {
        MainActivity.requestOemAutoStartPermissionStatic(getActivity());
        call.resolve();
    }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            getActivity().startActivity(intent);
            call.resolve();
            return;
        } catch (Exception ignored) {}
        openAppDetailsSettings();
        call.resolve();
    }

    @PluginMethod
    public void openFullScreenIntentSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(intent);
                call.resolve();
                return;
            } catch (Exception ignored) {}
        }
        openAppDetailsSettings();
        call.resolve();
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
        } catch (Exception ignored) {}
        MainActivity.requestOemAutoStartPermissionStatic(getActivity());
        call.resolve();
    }

    @PluginMethod
    public void startSignalService(PluginCall call) {
        String myId = call.getString("myId");
        String token = call.getString("token");
        if (myId == null || token == null) {
            call.reject("myId and token are required");
            return;
        }
        Intent intent = new Intent(getContext(), SignalService.class);
        intent.putExtra("myId", myId);
        intent.putExtra("token", token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
        call.resolve();
    }

    @PluginMethod
    public void stopSignalService(PluginCall call) {
        getContext().stopService(new Intent(getContext(), SignalService.class));
        // Clear the credentials SignalService persists for OS-triggered restarts (see
        // SignalService.onStartCommand). Without this, logging out on the web side and
        // stopping the service still leaves the old session sitting in SharedPreferences,
        // so a later system restart with a null intent would silently reconnect as the
        // previous user.
        try {
            getContext().getSharedPreferences("vahin_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("signal_my_id")
                .remove("signal_token")
                .apply();
        } catch (Exception ignored) {}
        call.resolve();
    }

    /**
     * Called from JS whenever a call ends, is answered, or is declined FROM THE WEB
     * SIDE (e.g. the in-call screen's End Call button, or the callee receiving a
     * 'call-cancel' data-channel message when the caller hangs up before answer).
     * The native Accept/Decline buttons already cancel the notification themselves
     * (IncomingCallActivity / VahinConnection) — this covers every other path that
     * needs the native call UI dismissed from the JS side.
     *
     * FIX: previously this only cancelled the notification. If IncomingCallActivity
     * (the full-screen ringing UI) was the thing actually on screen when the caller
     * cancelled, cancelling just the notification left the full-screen activity
     * stuck ringing indefinitely with no way for the user to dismiss it. Now this
     * also broadcasts ACTION_CALL_CANCELLED, the same signal VahinConnection.onAbort()
     * sends, so IncomingCallActivity's own cancelReceiver finishes it immediately —
     * whichever of the two (notification or full-screen activity) is actually showing.
     *
     * JS call:
     *   Capacitor.Plugins.VahinPermissions.dismissCallNotification({ from: "peerId" })
     */
    @PluginMethod
    public void dismissCallNotification(PluginCall call) {
        String from = call.getString("from", "");
        if (!from.isEmpty()) {
            CallNotifier.cancelCallNotification(getContext(), from);
            try {
                Intent cancelIntent = new Intent(IncomingCallActivity.ACTION_CALL_CANCELLED);
                cancelIntent.setPackage(getContext().getPackageName());
                cancelIntent.putExtra("from", from);
                getContext().sendBroadcast(cancelIntent);
            } catch (Exception e) {
                android.util.Log.w("VahinPermissionsPlugin",
                    "dismissCallNotification: failed to broadcast cancel — " + e.getMessage());
            }
            // FIX (Telecom call flow): this used to only clean up the notification and
            // the full-screen activity. The web app calls this whenever an incoming call
            // ends WITHOUT ever being answered — the caller cancelling (call-cancel
            // signal) or the user declining from the in-app incoming overlay — but
            // VahinConnection/Telecom was never told, so the call stayed stuck RINGING
            // from the OS's point of view: wrong Bluetooth/Android Auto state, and
            // Telecom's own ~30s ringing timeout could tear it down unpredictably out
            // from under the app later.
            //
            // FIX (ringing reliability, round 2): index.html's endCall() — the in-call
            // "End" button on BOTH sides, and the handler for the remote 'call-end'
            // data-channel message — never called this method at all, so an ACTIVE
            // (already-answered) Telecom connection was never disconnected either. Since
            // self-managed accounts only permit one call at a time, that leftover ACTIVE
            // connection silently blocked every later addNewIncomingCall() until the app
            // process was killed — the "ringing worked last time, not this time" bug.
            // endCall() now calls this method unconditionally when a call ends, so we
            // branch on whatever state Telecom is actually in: RINGING -> reject (old
            // behavior, unchanged), anything else that isn't already DISCONNECTED ->
            // endFromAppUi() to properly tear down an active/dialing call.
            try {
                VahinConnection conn = VahinConnection.getCurrent();
                if (conn != null) {
                    int state = conn.getState();
                    if (state == android.telecom.Connection.STATE_RINGING) {
                        conn.rejectFromAppUi();
                    } else if (state != android.telecom.Connection.STATE_DISCONNECTED) {
                        conn.endFromAppUi();
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("VahinPermissionsPlugin",
                    "dismissCallNotification: exception syncing Telecom state — " + e.getMessage());
            }
        }
        call.resolve();
    }

    /**
     * Called from JS whenever the user picks a custom ringtone (or clears it).
     * Saves the raw audio bytes to a private file so IncomingCallActivity can play
     * the user's MP3 via MediaPlayer without needing localStorage (which is
     * inaccessible from Java). Pass base64Data="" to clear/reset to default.
     *
     * JS call:
     *   Capacitor.Plugins.VahinPermissions.saveCustomRingtone({ base64Data: "..." })
     */
    @PluginMethod
    public void saveCustomRingtone(PluginCall call) {
        String base64Data = call.getString("base64Data", "");
        File ringFile = getCustomRingtoneFile(getContext());
        try {
            if (base64Data == null || base64Data.isEmpty()) {
                // Clear — delete the file so native falls back to system default
                if (ringFile.exists()) ringFile.delete();
            } else {
                // Strip data-URL prefix if present (e.g. "data:audio/mp3;base64,...")
                String raw = base64Data.contains(",") ? base64Data.split(",", 2)[1] : base64Data;
                byte[] bytes = Base64.decode(raw, Base64.DEFAULT);
                try (OutputStream os = new FileOutputStream(ringFile)) {
                    os.write(bytes);
                }
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to save ringtone: " + e.getMessage());
        }
    }

    /** Returns the private file path used for the custom ringtone. */
    public static File getCustomRingtoneFile(Context ctx) {
        return new File(ctx.getFilesDir(), "custom_ringtone.mp3");
    }

    /**
     * Returns the in-memory diagnostic log (see DebugLog.java) — the key call-flow
     * checkpoints (notification posted, activity launched, timeout fired, Telecom
     * onAbort, etc.) recorded in order with timestamps. Lets a call be diagnosed
     * from inside the app itself, no adb/PC required.
     *
     * JS call:
     *   Capacitor.Plugins.VahinPermissions.getDiagnosticLog() -> { log: "..." }
     */
    @PluginMethod
    public void getDiagnosticLog(PluginCall call) {
        call.resolve(new JSObject().put("log", DebugLog.getAll()));
    }

    @PluginMethod
    public void clearDiagnosticLog(PluginCall call) {
        DebugLog.clear();
        call.resolve();
    }

    // FIX (redundant native ring when both phones are already online): index.html calls
    // this every time its own peerReady flag flips (see setPeerReady() in the JS), so
    // AppState.isPeerReady() always reflects whether the PeerJS broker socket is
    // actually open right now — not just that the app happens to be on screen. Read by
    // VahinMessagingService.onMessageReceived() to decide whether an incoming call push
    // can safely skip the native full-screen popup because the in-app ring will handle it.
    @PluginMethod
    public void setPeerConnected(PluginCall call) {
        boolean connected = call.getBoolean("connected", false);
        AppState.setPeerReady(connected);
        DebugLog.log("AppState", "setPeerConnected: " + connected);
        call.resolve();
    }

    // FIX (ROOT CAUSE of "voice not going through even though the call connects"):
    // the web layer (index.html) has always called
    // Capacitor.Plugins.VahinPermissions.setSpeakerphoneOn() the moment a call's media
    // connects, and again whenever the user taps the Speaker button — but this native
    // method never actually existed. Capacitor's JS plugin proxy doesn't care whether a
    // native method exists (it dynamically proxies ANY method name), so the JS-side
    // truthy check `Capacitor.Plugins.VahinPermissions.setSpeakerphoneOn` always passed
    // and the call was sent to native — which had nothing registered for it, silently
    // rejected the promise, and the JS ".catch(function(){})" swallowed that rejection
    // completely. Net effect: the app never once actually switched Android's real audio
    // route. On many devices the WebView's own WebRTC audio session ends up parked on
    // the earpiece (very quiet, easy to mistake for "no audio at all" when the phone
    // isn't held right up to your ear) instead of the loudspeaker the UI's Speaker
    // button claims is on — and because AudioManager's mode was also never switched to
    // MODE_IN_COMMUNICATION or given real audio focus, that routing could be further
    // disturbed by whatever the OS/other apps last left the audio session in, which
    // fits "the last two to three times" — this wasn't a one-off crash, it was a
    // silently-broken feature the whole time, just intermittently masked depending on
    // whatever state the phone's audio system happened to already be in.
    @PluginMethod
    public void setSpeakerphoneOn(PluginCall call) {
        boolean on = call.getBoolean("on", true);
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                call.reject("AudioManager unavailable");
                return;
            }
            // A live call must run in MODE_IN_COMMUNICATION — this is what tells
            // Android "this is a voice call", which is what actually makes the
            // earpiece/speaker split (and echo cancellation) work correctly. Without
            // it the WebView's audio track can end up treated like ordinary media
            // playback, with no reliable, controllable route at all.
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            requestCallAudioFocus(am);
            am.setSpeakerphoneOn(on);
            Log.d(TAG_AUDIO, "setSpeakerphoneOn: on=" + on + " mode set to MODE_IN_COMMUNICATION");
            call.resolve(new JSObject().put("success", true));
        } catch (Exception e) {
            Log.e(TAG_AUDIO, "setSpeakerphoneOn: exception — " + e.getMessage(), e);
            call.reject("setSpeakerphoneOn failed: " + e.getMessage());
        }
    }

    // Called from index.html's endCall()/endConf() once a call is fully over — puts
    // AudioManager back to its normal (non-call) state. Skipping this after a call was
    // the other half of the same bug class: leaving the device stuck in
    // MODE_IN_COMMUNICATION with an unreleased audio focus grant between calls, which
    // is exactly the kind of leftover state that would make audio increasingly
    // unreliable across "the last two to three calls" rather than failing the same way
    // every time.
    @PluginMethod
    public void resetAudioRouting(PluginCall call) {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setSpeakerphoneOn(false);
                am.setMode(AudioManager.MODE_NORMAL);
                abandonCallAudioFocus(am);
                Log.d(TAG_AUDIO, "resetAudioRouting: mode reset to MODE_NORMAL, focus abandoned");
            }
        } catch (Exception e) {
            Log.w(TAG_AUDIO, "resetAudioRouting: exception — " + e.getMessage());
        }
        call.resolve();
    }

    private static final String TAG_AUDIO = "VahinAudioRouting";
    private static AudioFocusRequest activeFocusRequest;
    private static final AudioManager.OnAudioFocusChangeListener NOOP_FOCUS_LISTENER =
        focusChange -> { /* we hold focus for the duration of the call; nothing to react to */ };

    private void requestCallAudioFocus(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
                activeFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(NOOP_FOCUS_LISTENER)
                    .build();
                am.requestAudioFocus(activeFocusRequest);
            } else {
                //noinspection deprecation
                am.requestAudioFocus(NOOP_FOCUS_LISTENER, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception e) {
            Log.w(TAG_AUDIO, "requestCallAudioFocus: exception — " + e.getMessage());
        }
    }

    private void abandonCallAudioFocus(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (activeFocusRequest != null) {
                    am.abandonAudioFocusRequest(activeFocusRequest);
                    activeFocusRequest = null;
                }
            } else {
                //noinspection deprecation
                am.abandonAudioFocus(NOOP_FOCUS_LISTENER);
            }
        } catch (Exception e) {
            Log.w(TAG_AUDIO, "abandonCallAudioFocus: exception — " + e.getMessage());
        }
    }

    /**
     * Saves a base64-encoded file to cache/shared storage and opens it with the system viewer or chooser.
     */
    @PluginMethod
    public void saveAndOpenFile(PluginCall call) {
        String base64Data = call.getString("base64Data", "");
        String fileName = call.getString("fileName", "file");
        String mimeType = call.getString("mimeType", "application/octet-stream");

        try {
            if (base64Data == null || base64Data.isEmpty()) {
                call.reject("base64Data is required");
                return;
            }
            String raw = base64Data.contains(",") ? base64Data.split(",", 2)[1] : base64Data;
            byte[] bytes = Base64.decode(raw, Base64.DEFAULT);

            File cacheDir = new File(getContext().getCacheDir(), "shared_files");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File destFile = new File(cacheDir, fileName);
            try (OutputStream os = new FileOutputStream(destFile)) {
                os.write(bytes);
            }

            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                destFile
            );

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(contentUri, mimeType);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                getContext().startActivity(viewIntent);
            } catch (ActivityNotFoundException ex) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(mimeType);
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(Intent.createChooser(shareIntent, "Open " + fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }

            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to save and open file: " + e.getMessage());
        }
    }

    private void openAppDetailsSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
        } catch (ActivityNotFoundException ignored) {}
    }

    private boolean isFullScreenIntentGranted() {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.canUseFullScreenIntent();
    }

    private boolean isNotificationsGranted() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return androidx.core.content.ContextCompat.checkSelfPermission(
            getContext(), android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }

    @PluginMethod
    public void getAppVersionInfo(PluginCall call) {
        try {
            PackageManager pm = getContext().getPackageManager();
            PackageInfo pInfo = pm.getPackageInfo(getContext().getPackageName(), 0);
            long versionCode = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = pInfo.getLongVersionCode();
            } else {
                versionCode = pInfo.versionCode;
            }
            JSObject ret = new JSObject();
            ret.put("versionName", pInfo.versionName != null ? pInfo.versionName : "1.0");
            ret.put("versionCode", versionCode);
            ret.put("packageName", getContext().getPackageName());
            call.resolve(ret);
        } catch (Exception e) {
            JSObject fallback = new JSObject();
            fallback.put("versionName", "1.0");
            fallback.put("versionCode", 1);
            fallback.put("packageName", getContext().getPackageName());
            call.resolve(fallback);
        }
    }

    @PluginMethod
    public void logCrashlytics(PluginCall call) {
        String message = call.getString("message", "");
        if (!message.isEmpty()) {
            try {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log(message);
            } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void recordException(PluginCall call) {
        String message = call.getString("message", "");
        if (!message.isEmpty()) {
            try {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(new Exception(message));
            } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void setCrashlyticsUserId(PluginCall call) {
        String userId = call.getString("userId", "");
        try {
            if (userId != null && !userId.isEmpty()) {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setUserId(userId);
            }
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void setCrashlyticsCustomKey(PluginCall call) {
        String key = call.getString("key", "");
        String value = call.getString("value", "");
        if (!key.isEmpty()) {
            try {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCustomKey(key, value);
            } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void deleteAccountLocalData(PluginCall call) {
        try {
            // Stop active signal service session data
            getContext().getSharedPreferences("vahin_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();

            // Clear custom ringtone
            File ringFile = getCustomRingtoneFile(getContext());
            if (ringFile.exists()) ringFile.delete();

            // Clear shared files cache
            File cacheDir = new File(getContext().getCacheDir(), "shared_files");
            if (cacheDir.exists() && cacheDir.isDirectory()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        try { f.delete(); } catch (Exception ignored) {}
                    }
                }
            }
            call.resolve(new JSObject().put("success", true));
        } catch (Exception e) {
            call.resolve(new JSObject().put("success", false).put("error", e.getMessage()));
        }
    }
}