package com.vahin.connect;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen "someone is calling" UI, launched from VahinMessagingService via a
 * full-screen intent notification. Shows over the lock screen like a real phone call.
 */
public class IncomingCallActivity extends AppCompatActivity {

    private MediaPlayer ringtonePlayer;
    private Vibrator vibrator;
    private String from;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        }

        setContentView(R.layout.activity_incoming_call);

        from = getIntent().getStringExtra("from");
        boolean isConf = getIntent().getBooleanExtra("isConf", false);

        TextView nameView = findViewById(R.id.incoming_call_name);
        TextView subView = findViewById(R.id.incoming_call_sub);
        nameView.setText(from == null ? "Unknown" : from);
        subView.setText(isConf ? "Conference invite \u00b7 Unifest" : "Incoming call \u00b7 Unifest");

        findViewById(R.id.btn_incoming_accept).setOnClickListener(v -> finishWithAction("accept"));
        findViewById(R.id.btn_incoming_decline).setOnClickListener(v -> finishWithAction("decline"));

        startRingAndVibrate();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && from != null) nm.cancel(("call-" + from).hashCode());
    }

    private void startRingAndVibrate() {
        try {
            ringtonePlayer = new MediaPlayer();
            ringtonePlayer.setDataSource(
                this, RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE));
            ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepare();
            ringtonePlayer.start();
        } catch (Exception ignored) {
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) {
            long[] pattern = {0, 500, 300, 500, 300};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    private void stopRingAndVibrate() {
        if (ringtonePlayer != null) {
            try {
                ringtonePlayer.stop();
                ringtonePlayer.release();
            } catch (Exception ignored) {
            }
            ringtonePlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    private void finishWithAction(String action) {
        stopRingAndVibrate();
        Intent openMain = new Intent(this, MainActivity.class);
        openMain.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openMain.putExtra("vahinAction", action);
        openMain.putExtra("vahinFrom", from);
        startActivity(openMain);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRingAndVibrate();
    }
}
