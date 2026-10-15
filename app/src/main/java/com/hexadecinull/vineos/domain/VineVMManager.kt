package com.hexadecinull.vineos.domain

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hexadecinull.vineos.data.models.AbiCompat
import com.hexadecinull.vineos.data.models.ROMImage
import com.hexadecinull.vineos.data.models.VMInstance
import com.hexadecinull.vineos.data.models.VMStatus
import com.hexadecinull.vineos.data.repository.InstanceRepository
import com.hexadecinull.vineos.native.VineRuntime
import com.hexadecinull.vineos.service.VineService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class VineVMManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val instanceRepo: InstanceRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Both maps are written from the poll loops on Dispatchers.IO while touch/key/diagnostic calls
    // read them from the UI thread, so they have to be concurrent
    private val handles = ConcurrentHashMap<String, Long>()
    private val statusFlows = ConcurrentHashMap<String, MutableStateFlow<VMStatus>>()

    private val _runningInstances = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the instances whose guest process is alive; VineService mirrors the count into its notification. */
    val runningInstances: StateFlow<Set<String>> = _runningInstances.asStateFlow()

    val hostAbis: List<String> = Build.SUPPORTED_ABIS.toList()
    val hostSupports32bit: Boolean by lazy { VineRuntime.hostSupportsAArch32() }

    init {
        val nativeReady = VineRuntime.initialize(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
        )
        if (!nativeReady) {
            Log.w(TAG, "Native runtime failed to initialize; launching guests will not work")
        }

        scope.launch {
            // A fresh process means the previous one's guests are gone, so clear anything the
            // database still shows as booting, running, or paused
            instanceRepo.markStaleInstancesStopped()
        }
    }

    fun isRomCompatible(rom: ROMImage): Boolean = AbiCompat.romRunMode(rom, hostAbis) != AbiCompat.RunMode.UNAVAILABLE

    fun statusFlow(instanceId: String): StateFlow<VMStatus> =
        statusFlows.getOrPut(instanceId) { MutableStateFlow(VMStatus.STOPPED) }.asStateFlow()

    fun getHandle(instanceId: String): Long? = handles[instanceId]

    fun startInstance(instance: VMInstance) {
        scope.launch {
            val flow = statusFlows.getOrPut(instance.id) { MutableStateFlow(VMStatus.STOPPED) }
            flow.value = VMStatus.BOOTING

            startForegroundServiceSafely()

            val handle = VineRuntime.startInstance(
                instanceId = instance.id,
                instancePath = instance.storagePath,
                ramMb = instance.ramMb,
                cpuCores = instance.cpuCores,
            )

            if (handle == 0L) {
                Log.w(TAG, "Guest failed to start for instance ${instance.id}")
                flow.value = VMStatus.ERROR
                instanceRepo.updateStatus(instance.id, VMStatus.ERROR)
                syncRunningInstances()
                stopServiceIfIdle()
                return@launch
            }

            handles[instance.id] = handle
            syncRunningInstances()
            monitorInstance(instance.id, handle, flow)
        }
    }

    // startForegroundService() throws ForegroundServiceStartNotAllowedException when the app is
    // already backgrounded on Android 12+; the guest still runs while the app is foregrounded, so
    // this must not take the whole launch down with it
    private fun startForegroundServiceSafely() {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VineService::class.java).apply { action = VineService.ACTION_START },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not put VineService in the foreground; the guest keeps running only while the app is visible", e)
        }
    }

    fun stopInstance(instance: VMInstance) {
        scope.launch {
            handles.remove(instance.id)?.let { VineRuntime.stopInstance(it) }
            statusFlows.getOrPut(instance.id) { MutableStateFlow(VMStatus.STOPPED) }.value = VMStatus.STOPPED
            instanceRepo.updateStatus(instance.id, VMStatus.STOPPED)
            syncRunningInstances()
            stopServiceIfIdle()
        }
    }

    fun killInstance(instanceId: String) {
        scope.launch {
            handles.remove(instanceId)?.let { VineRuntime.killInstance(it) }
            statusFlows.getOrPut(instanceId) { MutableStateFlow(VMStatus.STOPPED) }.value = VMStatus.STOPPED
            instanceRepo.updateStatus(instanceId, VMStatus.STOPPED)
            syncRunningInstances()
            stopServiceIfIdle()
        }
    }

    /** Deletes an instance's guest storage from disk; used after its database row is removed. */
    suspend fun deleteInstanceData(instance: VMInstance) = withContext(Dispatchers.IO) {
        runCatching { VineRuntime.deleteInstance(instance.id, instance.storagePath) }
            .onFailure { Log.w(TAG, "Native delete failed for instance ${instance.id}", it) }

        // The native path shells out to rm, which can be blocked by SELinux context or a missing
        // /system/bin/rm, so whatever is left is removed directly
        val dir = File(instance.storagePath)
        if (dir.exists() && !dir.deleteRecursively()) {
            Log.w(TAG, "Could not fully remove instance storage at ${instance.storagePath}")
        }
    }

    fun getFramebufferFd(instanceId: String): Int {
        val handle = handles[instanceId] ?: return -1
        return VineRuntime.getFramebufferFd(handle)
    }

    fun sendTouch(instanceId: String, action: Int, x: Float, y: Float) {
        val handle = handles[instanceId] ?: return
        VineRuntime.sendTouchEvent(handle, action, x, y)
    }

    fun sendKey(instanceId: String, keycode: Int, down: Boolean) {
        val handle = handles[instanceId] ?: return
        VineRuntime.sendKeyEvent(handle, keycode, down)
    }

    fun getDiagnostics(instanceId: String): String {
        val handle = handles[instanceId] ?: return "Instance not running"
        val framebufferFd = getFramebufferFd(instanceId)
        return buildString {
            append(VineRuntime.getDiagnostics(handle))
            append("\n--- Host ---\n")
            append("framebuffer: ").append(if (framebufferFd >= 0) "fd $framebufferFd" else "not open").append('\n')
            append("32-bit guests: ")
                .append(if (hostSupports32bit) "native AArch32" else "through qemu-arm")
                .append('\n')
        }
    }

    private fun monitorInstance(instanceId: String, handle: Long, flow: MutableStateFlow<VMStatus>) = scope.launch {
        while (true) {
            delay(POLL_INTERVAL_MS)
            // The instance can be stopped or deleted while this coroutine is sleeping
            if (!handles.containsKey(instanceId)) break

            val status = when (VineRuntime.getInstanceStatus(handle)) {
                STATUS_STOPPED -> VMStatus.STOPPED
                STATUS_BOOTING -> VMStatus.BOOTING
                STATUS_RUNNING -> VMStatus.RUNNING
                STATUS_PAUSED -> VMStatus.PAUSED
                else -> VMStatus.ERROR
            }

            if (status != flow.value) {
                flow.value = status
                // The instance cards read their status from the database, so the runtime's view of
                // the world is mirrored there rather than living only in this StateFlow
                instanceRepo.updateStatus(instanceId, status)
            }

            if (status == VMStatus.STOPPED || status == VMStatus.ERROR) {
                handles.remove(instanceId)
                syncRunningInstances()
                stopServiceIfIdle()
                break
            }
        }
    }

    private fun syncRunningInstances() {
        _runningInstances.value = handles.keys.toSet()
    }

    private fun stopServiceIfIdle() {
        if (handles.isEmpty()) context.stopService(Intent(context, VineService::class.java))
    }

    fun cleanup() {
        scope.cancel()
        VineRuntime.shutdown()
    }

    companion object {
        private const val TAG = "VineVMManager"
        private const val POLL_INTERVAL_MS = 2000L

        // Mirrors vine::ContainerStatus in app/src/main/cpp/container/namespace_manager.h;
        // PAUSED is 4, not 3, and 3 is ERROR
        private const val STATUS_STOPPED = 0
        private const val STATUS_BOOTING = 1
        private const val STATUS_RUNNING = 2
        private const val STATUS_PAUSED = 4
    }
}
