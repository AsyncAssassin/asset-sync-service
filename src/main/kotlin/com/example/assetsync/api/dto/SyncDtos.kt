package com.example.assetsync.api.dto

import com.example.assetsync.application.sync.SyncRun
import java.time.Instant
import java.util.UUID

data class SyncRunResponse(
    val id: UUID,
    val targetType: String,
    val targetId: UUID,
    val status: String,
    val eventsSeen: Int,
    val eventsChanged: Int,
    val lastError: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun SyncRun.toResponse(): SyncRunResponse =
    SyncRunResponse(
        id = id,
        targetType = targetType.name,
        targetId = targetId,
        status = status.name,
        eventsSeen = eventsSeen,
        eventsChanged = eventsChanged,
        lastError = lastError,
        startedAt = startedAt,
        finishedAt = finishedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
