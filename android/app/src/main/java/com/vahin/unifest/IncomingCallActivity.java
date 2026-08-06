package com.vahin.unifest;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

/**
 * Full-screen incoming call UI shown over the lock screen.
 *
 * Ring behaviour:
 *   - If the user has set a custom ringtone (saved as a file by VahinPermissionsPlugin
 *     .saveCustomRingtone), that file is played with STREAM_RING audio attributes.
 *   - Otherwise the device's default ringtone is used.
 *   Both paths use USAGE_VOICE_COMMUNICATION_SIGNALLING + STREAM_RING so the ringer
 *   volume slider (not notification volume) controls the call ring level, matching
 *   exactly what the built-in phone dialer does.
 *
 * Caller-cancel:
 *   VahinConnection.onAbort() broadcasts ACTION_CALL_CANCELLED when the remote side
 *   hangs up before the callee answers. cancelReceiver catches it and calls finish()
 *   so the screen goes away immediately instead of ringing until timeout.
 *
 * Notification:
 *   The call notification is cancelled as soon as this activity is visible (so the
 *   shade doesn't show a duplicate) and again when the user accepts or rejects.
 */
public class IncomingCallActivity extends AppCompatActivity {

    private static final String TAG = "IncomingCallActivity";

    /** Broadcast sent by VahinConnection.onAbort() when the caller hangs up mid-ring. */
    public static final String ACTION_CALL_CANCELLED = "com.vahin.unifest.CALL_CANCELLED";

    private MediaPlayer ringtonePlayer;
    private Vibrator vibrator;
    private String from;

    private final BroadcastReceiver cancelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Caller hung up — stop ringing, cancel notification, close.
            Log.d(TAG, "cancelReceiver: ACTION_CALL_CANCELLED received — finishing");
            stopRingAndVibrate();
            cancelCallNotification();
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // FIX D: wrap the entire onCreate body. If anything here crashes (bad window
        // flags on a locked-down OEM, null layout view, MediaPlayer throwing on startup)
        // we log the full exception and finish() cleanly instead of showing an ANR
        // dialog or "back error" to the user.
        try {
            Log.d(TAG, "onCreate: starting IncomingCallActivity");

            // Show over lock screen, keep screen on.
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    if (km != null) km.requestDismissKeyguard(this, null);
                } catch (Exception e) {
                    Log.w(TAG, "onCreate: requestDismissKeyguard failed — " + e.getMessage(), e);
                }
            }

            setContentView(R.layout.activity_incoming_call);

            from = getIntent().getStringExtra("from");
            boolean isConf = getIntent().getBooleanExtra("isConf", false);
            Log.d(TAG, "onCreate: from=" + from + " isConf=" + isConf);

            TextView nameView = findViewById(R.id.incoming_call_name);
            TextView subView  = findViewById(R.id.incoming_call_sub);
            if (nameView != null) nameView.setText(from == null ? "Unknown" : from);
            if (subView  != null) subView.setText(isConf
                ? "Conference invite \u00b7 Unifest"
                : "Incoming call \u00b7 Unifest");

            if (findViewById(R.id.btn_incoming_accept) != null) {
                findViewById(R.id.btn_incoming_accept).setOnClickListener(v -> finishWithAction("accept"));
            }
            if (findViewById(R.id.btn_incoming_decline) != null) {
                findViewById(R.id.btn_incoming_decline).setOnClickListener(v -> finishWithAction("decline"));
            }

            // Cancel the heads-up notification immediately — the full-screen UI IS the UI now.
            cancelCallNotification();

            // Register for caller-cancelled broadcast (fired from VahinConnection.onAbort).
            IntentFilter filter = new IntentFilter(ACTION_CALL_CANCELLED);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(cancelReceiver, filter);
                }
            } catch (Exception e) {
                Log.e(TAG, "onCreate: failed to register cancelReceiver — " + e.getMessage(), e);
            }

            startRingAndVibrate();
            Log.d(TAG, "onCreate: complete");

        } catch (Exception e) {
            Log.e(TAG, "onCreate: fatal exception — "
                + e.getClass().getSimpleName() + ": " + e.getMessage()
                + ". Finishing activity to avoid ANR/crash dialog.", e);
            // Clean up what we can and close — a dead IncomingCallActivity is better
            // than the "back error" ANR dialog or a frozen screen.
            try { stopRingAndVibrate(); } catch (Exception ignored) {}
            try { cancelCallNotification(); } catch (Exception ignored) {}
            finish();
        }
    }

    // Index into the fallback chain: 0 = custom ringtone, 1 = default ringtone,
    // 2 = default notification sound, 3 = hardcoded system URI, -1 = give up (vibrate only).
    private int ringFallbackStep = 0;

    private void startRingAndVibrate() {
        playRingStep(0);
        startVibrateLoop();
    }

    // Tries each candidate ringtone source in order and falls through to the next
    // one if MediaPlayer fails to prepare or errors out. Uses prepareAsync() (NOT
    // the old blocking prepare()) so a slow or malformed custom MP3 can never freeze
    // the main thread — a synchronous prepare() that takes too long is exactly the
    // kind of thing that trips Android's ANR ("app isn't responding") watchdog,
    // which would show up as an error dialog and a ring that never actually starts.
    //
    // FIX A: MediaPlayer setup is now fully logged — every step and every error path
    // emits a Log.d/Log.e so that if this silently falls through to vibrate-only,
    // the logcat tells us exactly which step failed and why.
    private void playRingStep(int step) {
        ringFallbackStep = step;
        Uri ringtoneUri = resolveRingtoneUri(step);
        if (ringtoneUri == null) {
            // No more candidates — vibration-only is still active from startVibrateLoop().
            Log.w(TAG, "playRingStep: all ringtone candidates exhausted at step=" + step
                + " — vibrating only");
            return;
        }

        Log.d(TAG, "playRingStep: step=" + step + " uri=" + ringtoneUri);
        releasePlayer();
        try {
            ringtonePlayer = new MediaPlayer();
            ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .build());
            ringtonePlayer.setLooping(true);
            ringtonePlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "playRingStep: onPrepared at step=" + step + " — starting playback");
                mp.start();
            });
            ringtonePlayer.setOnErrorListener((mp, what, extra) -> {
                // FIX A: log the MediaPlayer error code and extra so silent playback
                // failures are diagnosable (what/extra codes are documented in MediaPlayer.java).
                Log.e(TAG, "playRingStep: MediaPlayer.onError at step=" + step
                    + " what=" + what + " extra=" + extra
                    + " — falling through to next ringtone candidate");
                // Move to the next candidate in the chain.
                runOnUiThread(() -> playRingStep(step + 1));
                return true; // we handled it, don't let MediaPlayer call onCompletion too
            });
            ringtonePlayer.setDataSource(this, ringtoneUri);
            ringtonePlayer.prepareAsync(); // non-blocking — onPreparedListener starts playback
        } catch (Exception e) {
            // FIX A: setDataSource() itself can throw synchronously for a bad/missing file —
            // log it, then move on to the next candidate immediately instead of ringing silently.
            Log.e(TAG, "playRingStep: exception at step=" + step + " setting data source "
                + ringtoneUri + " — " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            playRingStep(step + 1);
        }
    }

    private Uri resolveRingtoneUri(int step) {
        switch (step) {
            case 0: {
                try {
                    File customFile = VahinPermissionsPlugin.getCustomRingtoneFile(this);
                    if (customFile.exists() && customFile.length() > 0) {
                        Log.d(TAG, "resolveRingtoneUri: step 0 — using custom ringtone: " + customFile.getPath());
                        return Uri.fromFile(customFile);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "resolveRingtoneUri: step 0 custom ringtone check failed — " + e.getMessage());
                }
                return resolveRingtoneUri(1); // no custom ringtone set — skip straight to default
            }
            case 1: {
                try {
                    Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE);
                    Log.d(TAG, "resolveRingtoneUri: step 1 — default ringtone uri=" + uri);
                    return uri;
                } catch (Exception e) {
                    Log.w(TAG, "resolveRingtoneUri: step 1 getActualDefaultRingtoneUri threw — " + e.getMessage());
                    return null;
                }
            }
            case 2: {
                try {
                    Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION);
                    Log.d(TAG, "resolveRingtoneUri: step 2 — default notification uri=" + uri);
                    return uri;
                } catch (Exception e) {
                    Log.w(TAG, "resolveRingtoneUri: step 2 getActualDefaultRingtoneUri threw — " + e.getMessage());
                    return null;
                }
            }
            case 3:
                // Hardcoded last-resort system URI — covers devices where
                // getActualDefaultRingtoneUri() itself returns null (silent-by-default
                // profiles, some custom ROMs).
                Log.d(TAG, "resolveRingtoneUri: step 3 — using hardcoded DEFAULT_RINGTONE_URI");
                return android.provider.Settings.System.DEFAULT_RINGTONE_URI;
            default:
                return null; // exhausted every candidate — vibration only from here
        }
    }

    private void startVibrateLoop() {
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) {
                long[] pattern = {0, 800, 400};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    //noinspection deprecation
                    vibrator.vibrate(pattern, 0);
                }
                Log.d(TAG, "startVibrateLoop: vibration started");
            } else {
                Log.w(TAG, "startVibrateLoop: Vibrator service is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "startVibrateLoop: exception — " + e.getMessage(), e);
        }
    }

    private void releasePlayer() {
        if (ringtonePlayer != null) {
            try {
                ringtonePlayer.setOnPreparedListener(null);
                ringtonePlayer.setOnErrorListener(null);
                if (ringtonePlayer.isPlaying()) ringtonePlayer.stop();
                ringtonePlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "releasePlayer: exception during release — " + e.getMessage());
            }
            ringtonePlayer = null;
        }
    }

    private void stopRingAndVibrate() {
        releasePlayer();
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception e) {
                Log.w(TAG, "stopRingAndVibrate: vibrator.cancel() threw — " + e.getMessage());
            }
            vibrator = null;
        }
    }

    private void cancelCallNotification() {
        try {
            CallNotifier.cancelCallNotification(this, from);
        } catch (Exception e) {
            Log.w(TAG, "cancelCallNotification: exception — " + e.getMessage(), e);
        }
    }

    private void finishWithAction(String action) {
        try {
            Log.d(TAG, "finishWithAction: action=" + action + " from=" + from);
            stopRingAndVibrate();
            cancelCallNotification();

            // Keep Telecom in sync — covers Bluetooth headset / Android Auto answer buttons.
            try {
                VahinConnection conn = VahinConnection.getCurrent();
                if (conn != null) {
                    if ("accept".equals(action)) conn.answerFromAppUi();
                    else conn.rejectFromAppUi();
                } else {
                    Log.w(TAG, "finishWithAction: VahinConnection.getCurrent() is null — "
                        + "Telecom state may be out of sync (call already cancelled by OS?)");
                }
            } catch (Exception e) {
                Log.e(TAG, "finishWithAction: exception syncing Telecom state — " + e.getMessage(), e);
            }

            // Bring MainActivity to front with the action so the web app can react.
            Intent openMain = new Intent(this, MainActivity.class);
            openMain.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            openMain.putExtra("vahinAction", action);
            openMain.putExtra("vahinFrom", from);
            startActivity(openMain);
            finish();

        } catch (Exception e) {
            Log.e(TAG, "finishWithAction: exception — " + e.getMessage(), e);
            // Still try to close the activity — don't leave it stuck on screen
            try { finish(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            String newFrom = intent.getStringExtra("from");
            if (newFrom != null) {
                from = newFrom;
                TextView nameView = findViewById(R.id.incoming_call_name);
                if (nameView != null) nameView.setText(from);
                Log.d(TAG, "onNewIntent: updated from=" + from);
            }
        } catch (Exception e) {
            Log.w(TAG, "onNewIntent: exception — " + e.getMessage(), e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopRingAndVibrate();
        try { unregisterReceiver(cancelReceiver); } catch (Exception ignored) {}
    }
}