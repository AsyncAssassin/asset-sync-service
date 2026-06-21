package com.example.assetsync.application.sync

import java.time.Instant
import java.util.UUID

enum class SyncTargetType {
    ADDRESS,
    ACCOUNT,
}

enum class SyncRunStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
}

data class NewSyncRun(
    val id: UUID,
    val targetType: SyncTargetType,
    val targetId: UUID,
    val status: SyncRunStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val eventsSeen: Int,
    val eventsChanged: Int,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SyncRun(
    val id: UUID,
    val targetType: SyncTargetType,
    val targetId: UUID,
    val status: SyncRunStatus,
    val eventsSeen: Int,
    val eventsChanged: Int,
    val lastError: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SyncRunCompletion(
    val id: UUID,
    val status: SyncRunStatus,
    val eventsSeen: Int,
    val eventsChanged: Int,
    val lastError: String?,
    val finishedAt: Instant,
    val updatedAt: Instant,
)

interface SyncRunRepository {
    fun insert(syncRun: NewSyncRun): SyncRun

    fun markCompleted(completion: SyncRunCompletion): SyncRun

    fun findById(syncRunId: UUID): SyncRun?
}

class SyncRunNotFoundException(
    val syncRunId: UUID,
) : RuntimeException("Sync run was not found.")

class WatchedAddressByIdNotFoundException(
    val addressId: UUID,
) : RuntimeException("Active watched address was not found.")

class SyncProviderUnavailableException(
    val syncRun: SyncRun,
) : RuntimeException("Provider is unavailable.")
