package com.vahin.unifest2;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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

    // FIX (Telecom call flow): set by answerFromAppUi()/rejectFromAppUi() — tells
    // onAnswer()/onReject() this action originated from IncomingCallActivity's own
    // buttons, which ALREADY starts MainActivity with a vahinAction extra right after
    // calling us (see finishWithAction()). Without this flag onAnswer()/onReject() also
    // fired notifyWebApp() themselves, delivering the same action to the web app TWICE
    // when the WebView was already warm: the second "accept" would arrive after the
    // first had already cleared incomingCall, fall into the "call hasn't arrived yet"
    // branch, and pop a spurious extra "Connecting call…" toast over an already-connected
    // call. When this is true we skip our own delivery and let the Activity's own
    // startActivity() do it exactly once.
    private boolean viaAppUi = false;

    // FIX (root cause of "ring only lasts ~30s"): IncomingCallActivity's own 180s
    // RING_TIMEOUT_MS Handler only exists once that Activity has actually been
    // created. But Android deliberately downgrades our full-screen intent to a
    // plain heads-up notification whenever the screen is already on/unlocked
    // (see the comment in CallNotifier.showIncomingCall — this is enforced by the
    // OS and cannot be overridden by the app), which is exactly the "I was outside
    // the app" case being reported. In that case IncomingCallActivity NEVER runs,
    // so its timeout never gets scheduled, and the ring falls back to whatever the
    // bare notification/channel does on its own — observed as cutting off far
    // short of what we intend. This Handler is owned by the Connection itself,
    // which Telecom always keeps alive for the whole ringing period regardless of
    // which UI (if any) actually got shown, so it now sets the real ceiling on
    // ring duration either way. Same 240s value IncomingCallActivity uses, so
    // whichever path answers/declines first just cancels this harmlessly.
    private static final long RING_TIMEOUT_MS = 240_000; // 240 seconds (4 minutes)
    private final Handler ringTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable ringTimeoutRunnable;

    private void cancelRingTimeout() {
        if (ringTimeoutRunnable != null) {
            ringTimeoutHandler.removeCallbacks(ringTimeoutRunnable);
        }
    }

    public VahinConnection(Context ctx, String from, boolean isConf) {
        this.appContext = ctx.getApplicationContext();
        this.from = from;
        this.isConf = isConf;
        this.ringTimeoutRunnable = () -> {
            try {
                Log.d(TAG, "ringTimeoutRunnable: " + RING_TIMEOUT_MS + "ms elapsed with no "
                    + "answer/decline — treating as missed call. from=" + this.from);
                DebugLog.log(TAG, "VahinConnection ring TIMEOUT fired — from=" + this.from);
                setDisconnected(new DisconnectCause(DisconnectCause.MISSED));
                destroy();
                clearIfCurrent();
                CallNotifier.cancelCallNotification(this.appContext, this.from);
                Intent intent = new Intent(IncomingCallActivity.ACTION_CALL_CANCELLED);
                intent.setPackage(this.appContext.getPackageName());
                this.appContext.sendBroadcast(intent);
                notifyWebApp("missed");
            } catch (Exception e) {
                Log.e(TAG, "ringTimeoutRunnable: exception — " + e.getMessage(), e);
            }
        };
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
            // Always scheduled here — this callback fires whether or not Android lets
            // the full-screen IncomingCallActivity actually appear (see comment above).
            cancelRingTimeout();
            ringTimeoutHandler.postDelayed(ringTimeoutRunnable, RING_TIMEOUT_MS);
        } catch (Exception e) {
            Log.e(TAG, "onShowIncomingCallUi: CallNotifier.showIncomingCall threw — "
                + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void onAnswer() {
        try {
            Log.d(TAG, "onAnswer: from=" + from + " viaAppUi=" + viaAppUi);
            cancelRingTimeout();
            setActive();
            CallNotifier.cancelCallNotification(appContext, from);
            if (viaAppUi) {
                // IncomingCallActivity.finishWithAction() is already opening Unifest
                // with the "accept" extra right after this call returns — don't also
                // fire it from here or the web app receives "accept" twice (see the
                // viaAppUi field comment above).
                Log.d(TAG, "onAnswer: viaAppUi — skipping our own delivery, "
                    + "IncomingCallActivity is already opening Unifest");
            } else {
                // Answered from outside our own UI — Bluetooth headset button, Android
                // Auto, a wearable. Nothing else is going to open/foreground Unifest for
                // us here, so we do it the same reliable way IncomingCallActivity does:
                // start MainActivity with the action as an extra so it survives a cold
                // start (Unifest fully killed) and retries until the WebView is ready,
                // instead of a fire-and-forget JS eval that silently no-ops if the
                // WebView isn't alive yet.
                notifyWebAppRobust("accept");
            }
        } catch (Exception e) {
            Log.e(TAG, "onAnswer: exception — " + e.getMessage(), e);
        }
    }

    @Override
    public void onReject() {
        try {
            Log.d(TAG, "onReject: from=" + from + " viaAppUi=" + viaAppUi);
            cancelRingTimeout();
            setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
            destroy();
            clearIfCurrent();
            CallNotifier.cancelCallNotification(appContext, from);
            if (viaAppUi) {
                // Same reasoning as onAnswer() above — IncomingCallActivity delivers
                // "decline" itself right after this returns.
                Log.d(TAG, "onReject: viaAppUi — skipping our own delivery");
            } else {
                notifyWebAppRobust("decline");
            }
        } catch (Exception e) {
            Log.e(TAG, "onReject: exception — " + e.getMessage(), e);
        }
    }

    @Override
    public void onDisconnect() {
        try {
            Log.d(TAG, "onDisconnect: from=" + from);
            cancelRingTimeout();
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
            clearIfCurrent();
            // Always OS/Telecom-initiated (no Activity flow delivers this one for us),
            // so use the robust open-Unifest path rather than a fire-and-forget eval.
            notifyWebAppRobust("end");
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
            DebugLog.log(TAG, "Telecom onAbort() called by OS — from=" + from
                + " (this is Android's own Telecom framework ending the call, not our app code)");
            cancelRingTimeout();
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

    public void answerFromAppUi() { viaAppUi = true; onAnswer(); }
    public void rejectFromAppUi() { viaAppUi = true; onReject(); }

    // FIX (ringing reliability): called from VahinPermissionsPlugin.dismissCallNotification
    // when the WEB side ends an already-ACTIVE call (the in-call "End" button, or the
    // 'call-end' data-channel message from the other side). Before this fix, endCall() in
    // index.html only tore down PeerJS/WebRTC state — it never told Telecom the call was
    // over. Telecom self-managed accounts only allow ONE call at a time by default, so a
    // Connection left dangling in STATE_ACTIVE silently blocks every future
    // addNewIncomingCall() (VahinTelecom.addIncomingCall() throws IllegalStateException,
    // logged as "another self-managed call is already ringing") until the process is
    // killed and restarted. That is the intermittent "ringing worked before, now it
    // doesn't" behavior. This does NOT call notifyWebAppRobust() — unlike onDisconnect(),
    // the web side already knows the call ended (it's the one telling us), so re-opening
    // Unifest here would be redundant/wrong.
    public void endFromAppUi() {
        try {
            Log.d(TAG, "endFromAppUi: web side ended an active call, from=" + from);
            cancelRingTimeout();
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
            clearIfCurrent();
            CallNotifier.cancelCallNotification(appContext, from);
        } catch (Exception e) {
            Log.e(TAG, "endFromAppUi: exception — " + e.getMessage(), e);
        }
    }

    private void clearIfCurrent() {
        synchronized (VahinConnection.class) {
            if (current == this) current = null;
        }
    }

    // FIX (Telecom call flow): opens/foregrounds Unifest with vahinAction/vahinFrom
    // extras — the exact mechanism IncomingCallActivity.finishWithAction() and
    // CallNotifier's notification Answer/Decline buttons already rely on. MainActivity
    // picks these up in onCreate()/onNewIntent() -> handleIntentExtras() ->
    // deliverNativeCallAction(), which retries against the WebView until
    // window.handleNativeCallAction is actually defined. That's what makes "tap Answer"
    // reliably open Unifest, show "Connecting…", and kick off the existing WebRTC
    // reconnect even from a cold start — a plain evaluateJavascript() call (see
    // notifyWebApp() below) does nothing if the WebView isn't alive yet.
    private void notifyWebAppRobust(String action) {
        try {
            Log.d(TAG, "notifyWebAppRobust: opening Unifest to deliver action=" + action + " from=" + from);
            Intent openMain = new Intent(appContext, MainActivity.class);
            openMain.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            openMain.putExtra("vahinAction", action);
            openMain.putExtra("vahinFrom", from);
            appContext.startActivity(openMain);
        } catch (Exception e) {
            Log.e(TAG, "notifyWebAppRobust: exception starting MainActivity — " + e.getMessage(), e);
            // Last-resort fallback — only reaches the web app if it happens to already
            // be alive and ready, but better than nothing if startActivity() itself failed.
            notifyWebApp(action);
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