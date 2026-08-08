package com.vahin.unifest;

import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
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
        ret.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
        call.resolve(ret);
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
}