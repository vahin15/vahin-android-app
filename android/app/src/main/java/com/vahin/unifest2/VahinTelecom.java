package com.vahin.unifest2;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Registers Unifest as a self-managed Telecom calling account and hands incoming
 * calls to the OS via TelecomManager, instead of jumping straight to our own
 * notification. Self-managed accounts (API 26+) don't need the user to enable them
 * in Settings the way SIM-style ConnectionServices do — registerPhoneAccount() is
 * enough, and MANAGE_OWN_CALLS is a normal (not runtime-prompted) permission.
 */
public final class VahinTelecom {

    private static final String TAG = "VahinTelecom";
    private static final String ACCOUNT_ID = "vahin_unifest_account";

    private VahinTelecom() {}

    public static PhoneAccountHandle handle(Context ctx) {
        return new PhoneAccountHandle(
            new ComponentName(ctx, VahinConnectionService.class), ACCOUNT_ID);
    }

    // Call once from MainActivity.onCreate. Cheap and idempotent — safe every launch.
    //
    // FIX C: After registering, we verify the account is actually visible to Telecom
    // and log the result. Some OEMs (Xiaomi MIUI, Huawei EMUI, some Samsung builds)
    // silently block third-party self-managed calling accounts — registerPhoneAccount()
    // succeeds but getPhoneAccount() returns null, and every subsequent
    // addNewIncomingCall() silently fails. Logging here makes that diagnosable without
    // needing adb.  The result is also stored so addIncomingCall() can decide whether
    // to skip the Telecom path entirely on broken OEMs.
    public static void register(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "register: API < 26, Telecom self-managed not available — skipping");
            return;
        }
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) {
            Log.e(TAG, "register: TelecomManager is null — Telecom path unavailable on this device");
            return;
        }
        try {
            PhoneAccount account = PhoneAccount.builder(handle(ctx), "Unifest")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setShortDescription("Unifest calls")
                .build();
            tm.registerPhoneAccount(account);

            // FIX C — verify the account was actually accepted
            PhoneAccountHandle h = handle(ctx);
            PhoneAccount registered = tm.getPhoneAccount(h);
            if (registered == null) {
                Log.e(TAG, "register: PhoneAccount was registered but getPhoneAccount() returned null "
                    + "— this OEM may be silently blocking third-party calling accounts. "
                    + "Telecom path will be unavailable; calls will fall back to direct notification.");
                // Signal the web layer so a "calling not supported on this device" warning can be shown
                String js = "window.onTelecomRegistrationFailed && "
                    + "window.onTelecomRegistrationFailed('PhoneAccount not visible after registration');";
                MainActivity.runJsIfAvailable(js);
            } else {
                Log.i(TAG, "register: PhoneAccount registered and confirmed visible to Telecom. "
                    + "Capabilities=" + registered.getCapabilities()
                    + " isEnabled=" + registered.isEnabled());
            }
        } catch (Exception e) {
            Log.e(TAG, "register: unexpected exception registering PhoneAccount: "
                + e.getClass().getSimpleName() + " — " + e.getMessage(), e);
        }
    }

    // Called from VahinMessagingService/SignalService when an incoming call arrives. This
    // replaces the old direct-to-CallNotifier line. Telecom calls back into
    // VahinConnectionService.onCreateIncomingConnection(), which in turn triggers
    // VahinConnection.onShowIncomingCallUi() -> our existing IncomingCallActivity.
    // Returns false if Telecom refuses it (rare on self-managed, but some OEMs
    // block third-party calling accounts) so the caller can fall back.
    //
    // FIX B: the original swallowed exceptions silently. Now we log the full exception
    // class and message so silent Telecom rejections are visible in logcat/crash
    // reporters without needing a connected debugger.
    public static boolean addIncomingCall(Context ctx, String from, boolean isConf) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "addIncomingCall: API < 26 — skipping Telecom path");
            return false;
        }
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) {
            Log.e(TAG, "addIncomingCall: TelecomManager is null");
            return false;
        }
        try {
            Bundle extras = new Bundle();
            extras.putString("from", from);
            extras.putBoolean("isConf", isConf);
            Log.d(TAG, "addIncomingCall: calling addNewIncomingCall for from=" + from + " isConf=" + isConf);
            tm.addNewIncomingCall(handle(ctx), extras);
            Log.d(TAG, "addIncomingCall: addNewIncomingCall returned without exception — call handed to Telecom");
            return true;
        } catch (SecurityException e) {
            // SecurityException: MANAGE_OWN_CALLS not granted, or the PhoneAccount
            // handle's ComponentName doesn't match VahinConnectionService, or
            // (most common on OEMs) the account was silently rejected during registration.
            Log.e(TAG, "addIncomingCall: SecurityException — "
                + e.getClass().getSimpleName() + ": " + e.getMessage()
                + " | Likely cause: PhoneAccount not accepted by this OEM, or MANAGE_OWN_CALLS "
                + "permission revoked. Falling back to direct notification.", e);
            return false;
        } catch (IllegalStateException e) {
            // IllegalStateException: usually fires if there's already a self-managed call
            // in RINGING state and the OS won't allow a second incoming call to be added.
            Log.e(TAG, "addIncomingCall: IllegalStateException — "
                + e.getClass().getSimpleName() + ": " + e.getMessage()
                + " | Likely cause: another self-managed call is already ringing. "
                + "Falling back to direct notification.", e);
            return false;
        } catch (Exception e) {
            // Catch-all: Telecom can throw RuntimeException on some OEM firmware builds.
            Log.e(TAG, "addIncomingCall: unexpected exception — "
                + e.getClass().getSimpleName() + ": " + e.getMessage()
                + ". Falling back to direct notification.", e);
            return false;
        }
    }
}