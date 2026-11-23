package app.locationjournal;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Controls the device torch and reports only states the camera service confirms.
 */
public final class TorchController {
    public interface Listener {
        void onState(String state);
    }

    private final CameraManager cameras;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CameraManager.TorchCallback callback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (!cameraId.equals(TorchController.this.cameraId)) return;
            available = true;
            lit = enabled;
            emit(enabled ? "on" : "off");
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (!cameraId.equals(TorchController.this.cameraId)) return;
            available = false;
            lit = false;
            emit("busy");
        }
    };

    private Listener listener = state -> { };
    private String cameraId;
    private boolean registered;
    private boolean available = true;
    private boolean lit;

    public TorchController(Context context) {
        cameras = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? state -> { } : listener;
    }

    public void start() {
        cameraId = findFlashCamera();
        if (cameraId == null) {
            emit("unsupported");
            return;
        }
        if (!registered) {
            cameras.registerTorchCallback(callback, handler);
            registered = true;
        }
    }

    public String state() {
        if (cameraId == null) return "unsupported";
        if (!available) return "busy";
        return lit ? "on" : "off";
    }

    /** Ask the camera service to change the torch. The callback reports the real result. */
    public String request(boolean on) {
        if (cameraId == null) return "unsupported";
        if (!available && on) return "busy";
        try {
            cameras.setTorchMode(cameraId, on);
            return "requested";
        } catch (CameraAccessException error) {
            available = false;
            lit = false;
            return "busy";
        } catch (IllegalArgumentException error) {
            return "unsupported";
        }
    }

    public void turnOff() {
        if (cameraId == null || !lit) return;
        try {
            cameras.setTorchMode(cameraId, false);
        } catch (Exception ignored) {
            lit = false;
            emit("off");
        }
    }

    public void stop() {
        turnOff();
        if (registered) {
            cameras.unregisterTorchCallback(callback);
            registered = false;
        }
    }

    private void emit(String state) {
        listener.onState(state);
    }

    private String findFlashCamera() {
        try {
            String fallback = null;
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics characteristics = cameras.getCameraCharacteristics(id);
                Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (!Boolean.TRUE.equals(flash)) continue;
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
                if (fallback == null) fallback = id;
            }
            return fallback;
        } catch (CameraAccessException error) {
            return null;
        }
    }
}
