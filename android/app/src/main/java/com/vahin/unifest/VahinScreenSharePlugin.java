package com.vahin.unifest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * JS-facing screen-share control surface. index.html's toggleScreenShare()
 * calls start()/stop() on this plugin and listens for "frame" events, which it
 * draws onto an offscreen <canvas> and turns into a real MediaStreamTrack via
 * canvas.captureStream() — see that function for the WebRTC-side half of this.
 *
 * start() shows the standard Android "Start recording or casting?" system
 * permission dialog (via MediaProjectionManager.createScreenCaptureIntent()),
 * then hands the resulting token to ScreenCaptureService, which owns the
 * actual capture loop and streams frames back to us through the in-process
 * ScreenCaptureService.FrameListener callback registered below.
 */
@CapacitorPlugin(name = "VahinScreenShare")
public class VahinScreenSharePlugin extends Plugin {
    private static final String TAG = "VahinScreenSharePlugin";
    private boolean capturing = false;

    @PluginMethod
    public void start(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            call.reject("Screen sharing needs Android 5.0 or newer");
            return;
        }
        if (capturing) {
            call.resolve(new JSObject().put("started", true));
            return;
        }
        MediaProjectionManager mgr =
            (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            call.reject("Screen capture is not available on this device");
            return;
        }
        try {
            saveCall(call);
            startActivityForResult(call, mgr.createScreenCaptureIntent(), "handlePermissionResult");
        } catch (Exception e) {
            DebugLog.log(TAG, "start: could not launch capture intent — " + e.getMessage());
            call.reject("Could not start screen sharing: " + e.getMessage());
        }
    }

    @ActivityCallback
    private void handlePermissionResult(PluginCall call, androidx.activity.result.ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            DebugLog.log(TAG, "handlePermissionResult: user declined the screen-share permission");
            call.resolve(new JSObject().put("started", false).put("reason", "permission-denied"));
            return;
        }

        ScreenCaptureService.setFrameListener(new ScreenCaptureService.FrameListener() {
            @Override
            public void onFrame(String base64Jpeg, int width, int height) {
                JSObject data = new JSObject();
                data.put("jpeg", base64Jpeg);
                data.put("width", width);
                data.put("height", height);
                notifyListeners("frame", data);
            }
            @Override
            public void onCaptureEnded(String reason) {
                capturing = false;
                JSObject data = new JSObject();
                data.put("reason", reason == null ? "ended" : reason);
                notifyListeners("captureEnded", data);
            }
        });

        Intent svc = new Intent(getContext(), ScreenCaptureService.class);
        svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.getResultCode());
        svc.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.getData());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(svc);
            } else {
                getContext().startService(svc);
            }
            capturing = true;
            call.resolve(new JSObject().put("started", true));
        } catch (Exception e) {
            DebugLog.log(TAG, "handlePermissionResult: could not start ScreenCaptureService — " + e.getMessage());
            call.reject("Could not start screen sharing: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        capturing = false;
        ScreenCaptureService.setFrameListener(null);
        try {
            getContext().stopService(new Intent(getContext(), ScreenCaptureService.class));
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void isSupported(PluginCall call) {
        call.resolve(new JSObject().put(
            "supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP));
    }
}