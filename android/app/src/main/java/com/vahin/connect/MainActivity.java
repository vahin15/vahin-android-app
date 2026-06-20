package com.vahin.connect;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int CAM_MIC_PERMISSION_REQUEST = 7001;
    private PermissionRequest pendingWebRequest;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Allow the in-app WebView to grant camera/mic access to getUserMedia()
        // (WebRTC calling) once the user has approved the Android system permission.
        this.bridge.getWebView().setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needsCamera = false, needsMic = false;
                    for (String res : request.getResources()) {
                        if (res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needsCamera = true;
                        if (res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needsMic = true;
                    }
                    boolean camGranted = !needsCamera || ContextCompat.checkSelfPermission(
                        MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    boolean micGranted = !needsMic || ContextCompat.checkSelfPermission(
                        MainActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

                    if (camGranted && micGranted) {
                        request.grant(request.getResources());
                    } else {
                        pendingWebRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
                        }, CAM_MIC_PERMISSION_REQUEST);
                    }
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAM_MIC_PERMISSION_REQUEST && pendingWebRequest != null) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                pendingWebRequest.grant(pendingWebRequest.getResources());
            } else {
                pendingWebRequest.deny();
            }
            pendingWebRequest = null;
        }
    }
}
