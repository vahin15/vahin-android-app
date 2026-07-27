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
@CapacitorPlugin(name = "VahinPermissions")
public class VahinPermissionsPlugin extends Plugin {

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("fullScreenIntentGranted", isFullScreenIntentGranted());
        ret.put("fullScreenIntentApplicable", Build.VERSION.SDK_INT >= 34);
        ret.put("batteryExemptionGranted", isIgnoringBatteryOptimizations());
        call.resolve(ret);
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

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }
}
