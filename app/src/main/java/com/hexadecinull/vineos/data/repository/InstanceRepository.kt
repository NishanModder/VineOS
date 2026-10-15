package com.hexadecinull.vineos.data.repository

import com.hexadecinull.vineos.data.models.VMInstance
import com.hexadecinull.vineos.data.models.VMStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class InstanceRepository @Inject constructor(private val dao: VMInstanceDao) {
    fun observeAll(): Flow<List<VMInstance>> = dao.observeAll()

    suspend fun getById(id: String): VMInstance? = dao.getById(id)

    suspend fun save(instance: VMInstance) = dao.insert(instance)

    suspend fun update(instance: VMInstance) = dao.update(instance)

    suspend fun updateStatus(id: String, status: VMStatus) = dao.updateStatus(id, status)

    // Guests are child processes of this app, so a fresh process means nothing is actually running:
    // any status left behind as BOOTING/RUNNING/PAUSED is stale and would otherwise leave the UI
    // showing a running instance with a Stop button that has no handle behind it
    suspend fun markStaleInstancesStopped() = dao.updateStatuses(
        from = listOf(VMStatus.BOOTING, VMStatus.RUNNING, VMStatus.PAUSED),
        to = VMStatus.STOPPED,
    )

    suspend fun touchLastUsed(id: String) = dao.updateLastUsed(id)

    suspend fun delete(instance: VMInstance) = dao.delete(instance)

    suspend fun deleteById(id: String) = dao.deleteById(id)
}
