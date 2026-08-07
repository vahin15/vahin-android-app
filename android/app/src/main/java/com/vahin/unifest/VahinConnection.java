package com.vahin.unifest;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.util.Log;

/**
 * One ringing/active call inside Telecom. onAnswer/onReject fire from the OS (Bluetooth
 * headset button, Android Auto, wearable) — IncomingCallActivity's own buttons also call
 * answerFromAppUi()/rejectFromAppUi() via getCurrent() so both input paths keep Telecom's
 * call state in sync instead of only closing our activity.
 */
public class VahinConnection extends Connection {

    private static final String TAG = "VahinConnection";

    private static VahinConnection current;

    private final Context appContext;
    private final String from;
    private final boolean isConf;

    public VahinConnection(Context ctx, String from, boolean isConf) {
        this.appContext = ctx.getApplicationContext();
        this.from = from;
        this.isConf = isConf;
        setConnectionProperties(PROPERTY_SELF_MANAGED);
        setAudioModeIsVoip(true);
    }

    public static synchronized void setCurrent(VahinConnection c) { current = c; }
    public static synchronized VahinConnection getCurrent() { return current; }

    @Override
    public void onShowIncomingCallUi() {
        // FIX D: wrap in try/catch — if CallNotifier throws (e.g. NotificationManager
        // gone, bad context on first boot, OEM firmware quirk) we log the error and
        // survive instead of crashing the entire Telecom callback chain, which would
        // manifest as the silent "back error" / call UI never appearing.
        try {
            Log.d(TAG, "onShowIncomingCallUi: from=" + from + " isConf=" + isConf);
            CallNotifier.showIncomingCall(appContext, isConf ? "conf" : "call", from);
        } catch (Exception e) {
            Log.e(TAG, "onShowIncomingCallUi: CallNotifier.showIncomingCall threw — "
                + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void onAnswer() {
        try {
            Log.d(TAG, "onAnswer: from=" + from);
            setActive();
            CallNotifier.cancelCallNotification(appContext, from);
            notifyWebApp("accept");
        } catch (Exception e) {
            Log.e(TAG, "onAnswer: exception — " + e.getMessage(), e);
        }
    }

    @Override
    public void onReject() {
        try {
            Log.d(TAG, "onReject: from=" + from);
            setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
            destroy();
            clearIfCurrent();
            CallNotifier.cancelCallNotification(appContext, from);
            notifyWebApp("decline");
        } catch (Exception e) {
            Log.e(TAG, "onReject: exception — " + e.getMessage(), e);
        }
    }

    @Override
    public void onDisconnect() {
        try {
            Log.d(TAG, "onDisconnect: from=" + from);
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
            clearIfCurrent();
            notifyWebApp("end");
        } catch (Exception e) {
            Log.e(TAG, "onDisconnect: exception — " + e.getMessage(), e);
        }
    }

    @Override
    public void onAbort() {
        // Caller cancelled before callee answered — dismiss IncomingCallActivity
        // and cancel its notification so the user doesn't keep seeing a dead call.
        try {
            Log.d(TAG, "onAbort: caller cancelled — from=" + from);
            setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
            destroy();
            clearIfCurrent();

            // Dismiss the call notification (covers the case where the activity isn't on screen)
            CallNotifier.cancelCallNotification(appContext, from);
            // FIX: same id-drift safety net as IncomingCallActivity.cancelCallNotification —
            // sweep the calls channel so a mismatched "from" can never leave a stuck notification.
            try {
                android.app.NotificationManager nm =
                    (android.app.NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                        if (CallNotifier.CHANNEL_ID_CALLS.equals(sbn.getNotification().getChannelId())) {
                            nm.cancel(sbn.getId());
                        }
                    }
                }
            } catch (Exception sweepEx) {
                Log.w(TAG, "onAbort: notification sweep failed — " + sweepEx.getMessage());
            }

            // Signal IncomingCallActivity to finish() if it's currently showing
            Intent intent = new Intent(IncomingCallActivity.ACTION_CALL_CANCELLED);
            intent.setPackage(appContext.getPackageName());
            appContext.sendBroadcast(intent);

            // Tell the web app this was a missed call
            notifyWebApp("missed");
        } catch (Exception e) {
            Log.e(TAG, "onAbort: exception — " + e.getMessage(), e);
        }
    }

    public void answerFromAppUi() { onAnswer(); }
    public void rejectFromAppUi() { onReject(); }

    private void clearIfCurrent() {
        synchronized (VahinConnection.class) {
            if (current == this) current = null;
        }
    }

    private void notifyWebApp(String action) {
        try {
            String safeFrom = from == null ? "" : from.replace("'", "\\'");
            String js = "window.handleNativeCallAction && window.handleNativeCallAction('"
                + action + "','" + safeFrom + "');";
            MainActivity.runJsIfAvailable(js);
        } catch (Exception e) {
            Log.e(TAG, "notifyWebApp: exception sending action=" + action + " — " + e.getMessage(), e);
        }
    }
}