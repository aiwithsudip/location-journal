package app.locationjournal.tracking

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class TorchStatus(val available: Boolean, val on: Boolean, val state: String)

@Singleton
class TorchController @Inject constructor(@ApplicationContext context: Context) {
    private val appContext = context.applicationContext
    private val cameras = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(TorchStatus(false, false, "unsupported"))
    val status: StateFlow<TorchStatus> = _status.asStateFlow()

    private var cameraId: String? = null
    private var registered = false
    private var lit = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            lit = enabled
            _status.value = TorchStatus(true, enabled, if (enabled) "on" else "off")
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            lit = false
            _status.value = TorchStatus(false, false, "busy")
        }
    }

    fun start() {
        cameraId = findFlashCamera()
        if (cameraId == null) {
            _status.value = TorchStatus(false, false, "unsupported")
            return
        }
        if (!registered) {
            cameras.registerTorchCallback(callback, handler)
            registered = true
        }
        _status.value = TorchStatus(true, lit, if (lit) "on" else "off")
    }

    fun setEnabled(on: Boolean): String {
        val id = cameraId ?: return "unsupported"
        return try {
            cameras.setTorchMode(id, on)
            if (!on) {
                lit = false
                _status.value = TorchStatus(true, false, "off")
            }
            if (on) "requested" else "off"
        } catch (_: CameraAccessException) {
            "busy"
        } catch (_: IllegalArgumentException) {
            "unsupported"
        } catch (_: SecurityException) {
            "denied"
        }
    }

    fun turnOff() {
        if (!lit) return
        setEnabled(false)
    }

    fun stop() {
        turnOff()
        if (registered) {
            cameras.unregisterTorchCallback(callback)
            registered = false
        }
    }

    private fun findFlashCamera(): String? {
        return try {
            var fallback: String? = null
            for (id in cameras.cameraIdList) {
                val flash = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                if (flash != true) continue
                val facing = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
                if (fallback == null) fallback = id
            }
            fallback
        } catch (_: CameraAccessException) {
            null
        }
    }
}
