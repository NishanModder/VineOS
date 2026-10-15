package com.hexadecinull.vineos.ui.viewmodel

import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hexadecinull.vineos.data.models.VMStatus
import com.hexadecinull.vineos.domain.VineVMManager
import com.hexadecinull.vineos.native.VineRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel
class VMDisplayViewModel @Inject constructor(private val vmManager: VineVMManager) : ViewModel() {

    var isBooting by mutableStateOf(false)
        private set

    private var instanceId: String? = null
    private var instanceHandle = 0L
    private var statusJob: Job? = null
    private var attachJob: Job? = null
    private var surface: Surface? = null

    fun attach(id: String) {
        instanceId = id
        statusJob = viewModelScope.launch {
            vmManager.statusFlow(id).collect { status ->
                isBooting = status == VMStatus.BOOTING
            }
        }
    }

    fun detach() {
        statusJob?.cancel()
        statusJob = null
        // The SurfaceView normally reports its surface as destroyed before this runs, but if the
        // composable leaves the composition first the native render loop would keep holding a
        // window that no longer exists
        releaseSurface()
        instanceId = null
    }

    fun onSurfaceReady(newSurface: Surface) {
        surface = newSurface
        attachJob?.cancel()
        // Attaching can fail simply because the guest hasn't created its framebuffer device yet,
        // which is the normal case right after a launch, so this keeps retrying instead of
        // rendering nothing for the rest of the session
        attachJob = viewModelScope.launch {
            repeat(ATTACH_ATTEMPTS) { attempt ->
                if (attachAndStart()) return@launch
                if (attempt < ATTACH_ATTEMPTS - 1) delay(ATTACH_RETRY_MS)
            }
            Log.w(TAG, "Gave up attaching the guest surface for instance ${instanceId ?: "?"}")
        }
    }

    fun onSurfaceDestroyed() {
        attachJob?.cancel()
        attachJob = null
        releaseSurface()
    }

    fun sendTouch(action: Int, x: Float, y: Float) {
        val id = instanceId ?: return
        vmManager.sendTouch(id, action, x, y)
    }

    fun sendBack() = sendAndroidKey(KeyEvent.KEYCODE_BACK)

    fun sendHome() = sendAndroidKey(KeyEvent.KEYCODE_HOME)

    fun sendRecents() = sendAndroidKey(KeyEvent.KEYCODE_APP_SWITCH)

    private fun sendAndroidKey(keycode: Int) {
        val id = instanceId ?: return
        vmManager.sendKey(id, keycode, true)
        vmManager.sendKey(id, keycode, false)
    }

    private fun attachAndStart(): Boolean {
        val id = instanceId ?: return false
        val currentSurface = surface ?: return false
        val handle = vmManager.getHandle(id) ?: return false

        VineRuntime.attachSurface(handle, currentSurface)
        val started = VineRuntime.startRendering(handle)
        if (started) instanceHandle = handle
        return started
    }

    private fun releaseSurface() {
        val handle = if (instanceHandle != 0L) instanceHandle else instanceId?.let { vmManager.getHandle(it) } ?: 0L
        if (handle != 0L) {
            VineRuntime.stopRendering(handle)
            VineRuntime.detachSurface(handle)
        }
        instanceHandle = 0L
        surface = null
    }

    companion object {
        private const val TAG = "VMDisplayViewModel"

        // The guest needs a while to bring up its framebuffer after launch; ~20s of retries covers
        // a slow boot without spinning forever on an instance that will never provide one
        private const val ATTACH_ATTEMPTS = 20
        private const val ATTACH_RETRY_MS = 1000L
    }
}
