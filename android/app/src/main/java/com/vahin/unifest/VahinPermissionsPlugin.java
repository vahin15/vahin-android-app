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

// Exposes the two "special" permissions that Android's own permission-request APIs can't
// surface from JS at all (there's no getUserMedia()-style prompt for either of these —
// they only exist as native Settings screens):
//
//   - USE_FULL_SCREEN_INTENT (Android 14+ / API 34+): without this, incoming calls degrade
//     to a normal heads-up notification instead of ringing full-screen like a real call.
//   - Battery-optimization / OEM autostart exemption: without this, Android (and
//     Xiaomi/Oppo/Vivo/Samsung's own battery managers) can kill the app in the background
//     and FCM delivery silently stops, so calls never ring at all.
//
// MainActivity already best-effort prompts for both once via a one-time AlertDialog, but
// once that's dismissed (even accidentally) there was previously no way back in without
// reinstalling. This plugin lets the Settings screen show live status and re-open either
// system screen on demand, any time.
@CapacitorPlugin(
    name = "VahinPermissions",
    permissions = { @Permission(strings = { android.Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications") }
)
public class VahinPermissionsPlugin extends Plugin {

    // Fires the REAL system "Allow notifications?" dialog (not just a jump to Settings)
    // — this is the one piece the old plugin was missing that a true one-tap chained
    // flow needs. Only works pre-first-denial or once-denied-not-permanently; if Android
    // has already permanently blocked the dialog, this resolves with it still denied and
    // the JS side falls back to openNotificationSettings().
    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33) { // permission didn't exist pre-Android 13
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
        // On Samsung (and most OEMs) this is the one setting the app cannot re-request
        // itself once denied — Android only shows the system permission dialog once per
        // app; after that, tapping the permission API again is silently a no-op and the
        // only way back is this Settings screen (or reinstalling). This is why
        // notifications can appear to "just stop working" after a single accidental
        // swipe-away of that first prompt, with no error anywhere to explain it.
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            getActivity().startActivity(intent);
            call.resolve();
            return;
        } catch (Exception ignored) {
            // fall through
        }
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
            } catch (Exception ignored) {
                // fall through to app-details as a last resort
            }
        }
        openAppDetailsSettings();
        call.resolve();
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        // Same best-effort sequence MainActivity uses on first launch: standard Android
        // battery-exemption dialog, then whichever OEM autostart screen matches this device.
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
        } catch (Exception ignored) {
            // Some OEM builds strip this intent; fall through, still try autostart below.
        }
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

    private void openAppDetailsSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private boolean isFullScreenIntentGranted() {
        if (Build.VERSION.SDK_INT < 34) return true; // not required below API 34
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.canUseFullScreenIntent();
    }

    private boolean isNotificationsGranted() {
        if (Build.VERSION.SDK_INT < 33) return true; // permission didn't exist pre-Android 13
        return androidx.core.content.ContextCompat.checkSelfPermission(
            getContext(), android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }
}