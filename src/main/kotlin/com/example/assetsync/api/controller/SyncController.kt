package com.example.assetsync.api.controller

import com.example.assetsync.api.dto.SyncRunResponse
import com.example.assetsync.api.dto.toResponse
import com.example.assetsync.application.sync.SyncApplicationService
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class SyncController(
    private val syncApplicationService: SyncApplicationService,
) {

    @PostMapping("/addresses/{addressId}/sync")
    fun syncAddress(
        @PathVariable addressId: UUID,
    ): ResponseEntity<SyncRunResponse> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(syncApplicationService.syncAddress(addressId).toResponse())

    @PostMapping("/accounts/{accountId}/sync")
    fun syncAccount(
        @PathVariable accountId: UUID,
    ): ResponseEntity<SyncRunResponse> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(syncApplicationService.syncAccount(accountId).toResponse())

    @GetMapping("/sync-runs/{syncRunId}")
    fun getSyncRun(
        @PathVariable syncRunId: UUID,
    ): SyncRunResponse =
        syncApplicationService.getSyncRun(syncRunId).toResponse()
}
