package com.vahin.unifest;

import android.content.Context;
import android.content.Intent;
import android.telecom.Connection;
import android.telecom.DisconnectCause;

/**
 * One ringing/active call inside Telecom. onAnswer/onReject fire from the OS (Bluetooth
 * headset button, Android Auto, wearable) — IncomingCallActivity's own buttons also call
 * answerFromAppUi()/rejectFromAppUi() via getCurrent() so both input paths keep Telecom's
 * call state in sync instead of only closing our activity.
 */
public class VahinConnection extends Connection {

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
        // Telecom's cue to draw our own UI now — this is the hook that used to be
        // "FCM arrived -> CallNotifier.showIncomingCall()" directly.
        CallNotifier.showIncomingCall(appContext, isConf ? "conf" : "call", from);
    }

    @Override
    public void onAnswer() {
        setActive();
        CallNotifier.cancelCallNotification(appContext, from);
        notifyWebApp("accept");
    }

    @Override
    public void onReject() {
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        destroy();
        clearIfCurrent();
        CallNotifier.cancelCallNotification(appContext, from);
        notifyWebApp("decline");
    }

    @Override
    public void onDisconnect() {
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        destroy();
        clearIfCurrent();
        notifyWebApp("end");
    }

    @Override
    public void onAbort() {
        // Caller cancelled before callee answered — dismiss IncomingCallActivity
        // and cancel its notification so the user doesn't keep seeing a dead call.
        setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
        destroy();
        clearIfCurrent();

        // Dismiss the call notification (covers the case where the activity isn't on screen)
        CallNotifier.cancelCallNotification(appContext, from);

        // Signal IncomingCallActivity to finish() if it's currently showing
        Intent intent = new Intent(IncomingCallActivity.ACTION_CALL_CANCELLED);
        intent.setPackage(appContext.getPackageName());
        appContext.sendBroadcast(intent);

        // Tell the web app this was a missed call
        notifyWebApp("missed");
    }

    public void answerFromAppUi() { onAnswer(); }
    public void rejectFromAppUi() { onReject(); }

    private void clearIfCurrent() {
        synchronized (VahinConnection.class) {
            if (current == this) current = null;
        }
    }

    private void notifyWebApp(String action) {
        String safeFrom = from == null ? "" : from.replace("'", "\\'");
        String js = "window.handleNativeCallAction && window.handleNativeCallAction('"
            + action + "','" + safeFrom + "');";
        MainActivity.runJsIfAvailable(js);
    }
}